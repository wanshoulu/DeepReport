/*
 * AgentChatClient — openjiuwen agent chat 端点的 Java 客户端。
 *
 * 框架合规：不硬接 LLM API，所有 LLM 逻辑在 openjiuwen DeepAgent 内。
 * 本类只负责 HTTP 调用 skill-app-agent 的 /v1/chat (SSE) + 解析响应。
 *
 * 降级策略：连接失败/超时 → 返回 null，调用方回退模板正文。
 */

package com.agenthub.w06.checker.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class AgentChatClient {

    /**
     * Swarm journal 对齐（内容寻址缓存）：LLM 调用按 SHA256(prompt) 记录到
     * STATIC_DIR/state/chat-journal/<hash>.json。重跑同一 prompt（同任务书/同查询规划）
     * 命中即复用，不调外部 agent——中断重跑不重复烧 token。
     * 语义与 agent-core journal.py 一致：key=内容寻址，sig=输入全文；
     * CHAT_JOURNAL_ENABLED=false 关闭；TTL 过期（默认 24h）视为 miss。
     */
    private static final java.nio.file.Path JOURNAL_DIR =
            java.nio.file.Path.of(System.getenv().getOrDefault("STATIC_DIR", "/app"))
                    .resolve("state/chat-journal");
    private static final boolean JOURNAL_ENABLED = !"false".equalsIgnoreCase(
            System.getenv().getOrDefault("CHAT_JOURNAL_ENABLED", "true"));
    private static final long JOURNAL_TTL_MS =
            Long.parseLong(System.getenv().getOrDefault("CHAT_JOURNAL_TTL_HOURS", "24")) * 3600_000L;

    /** S12.3c：缓存 key = sha256(sessionId + prompt)。此前只用 prompt，
     *  导致同银行同指标两次长文任务（任务书相同）互相命中缓存。 */
    private static String journalKey(String prompt, String sessionId) {
        return sha256Hex((sessionId == null ? "" : sessionId) + "\n" + prompt);
    }

    private static String journalLookup(String prompt, String sessionId) {
        if (!JOURNAL_ENABLED) return null;
        try {
            String key = journalKey(prompt, sessionId);
            java.nio.file.Path f = JOURNAL_DIR.resolve(key + ".json");
            if (!java.nio.file.Files.isRegularFile(f)) return null;
            com.fasterxml.jackson.databind.JsonNode rec = MAPPER.readTree(f.toFile());
            long at = rec.path("at").asLong(0);
            if (System.currentTimeMillis() - at > JOURNAL_TTL_MS) return null; // TTL 过期 miss
            String answer = rec.path("answer").asText("");
            System.err.println("AgentChatClient: journal CACHE_HIT " + key.substring(0, 12)
                    + " (prompt " + prompt.length() + " chars)");
            return answer.isEmpty() ? null : answer;
        } catch (Exception e) {
            return null; // journal 故障不影响主链路
        }
    }

    private static void journalPut(String prompt, String answer, String sessionId) {
        if (!JOURNAL_ENABLED || answer == null || answer.isEmpty()) return;
        try {
            java.nio.file.Files.createDirectories(JOURNAL_DIR);
            ObjectNode rec = MAPPER.createObjectNode();
            rec.put("at", System.currentTimeMillis());
            rec.put("prompt_chars", prompt.length());
            rec.put("session_id", sessionId);
            rec.put("answer", answer);
            String key = journalKey(prompt, sessionId);
            java.nio.file.Path tmp = JOURNAL_DIR.resolve(key + ".tmp");
            java.nio.file.Files.writeString(tmp, MAPPER.writeValueAsString(rec),
                    java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.move(tmp, JOURNAL_DIR.resolve(key + ".json"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("AgentChatClient: journal put failed: " + e.getMessage());
        }
    }

    private static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** M11：一次 chat 的执行留痕——agent 实际加载的技能、调用过的工具（SSE 事件捕获）。 */
    record ChatTrace(java.util.List<String> skillsLoaded, java.util.List<String> toolsCalled) {
        static ChatTrace empty() { return new ChatTrace(java.util.List.of(), java.util.List.of()); }
        boolean isEmpty() { return skillsLoaded.isEmpty() && toolsCalled.isEmpty(); }
        String brief() {
            java.util.List<String> parts = new java.util.ArrayList<>();
            if (!skillsLoaded.isEmpty()) parts.add("技能加载: " + String.join(", ", skillsLoaded));
            if (!toolsCalled.isEmpty()) parts.add("工具调用: " + String.join(", ", toolsCalled));
            return String.join("；", parts);
        }
    }

    /** 最近一次 chat 的留痕（线程内可见；checker 为单流水线线程模型，够用）。 */
    static volatile ChatTrace LAST_TRACE = ChatTrace.empty();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String AGENT_URL = System.getenv()
            .getOrDefault("AGENT_CHAT_URL", "http://skill-app-agent:8310/v1/chat");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 调用 skill-app-agent chat 端点（独立一次性会话）。
     */
    static String chat(String message) {
        return chat(message, "w06-pipeline-" + System.currentTimeMillis());
    }

    /**
     * 调用 skill-app-agent chat 端点，返回 agent 生成的文本。
     * 框架记忆支撑：openjiuwen 按 sessionId 维护会话上下文，同一生成流程内的
     * 任务书分派 / 缺节续写 / 违规反思修订复用同一 sessionId，agent 即可看到
     * 此前的任务书与自己已产出的成稿。
     *
     * @param message   发送给 agent 的完整消息（任务书 / 续写指令 / 违规反馈）
     * @param sessionId 会话 id（同一生成流程内复用）
     * @return agent 生成的文本；连接失败/超时 → null（调用方降级）
     */
    static String chat(String message, String sessionId) {
        LAST_TRACE = ChatTrace.empty(); // M11：每次会话重置留痕
        String cached = journalLookup(message, sessionId); // Swarm journal 对齐：内容寻址缓存优先
        if (cached != null) return cached;
        LAST_TRACE = ChatTrace.empty(); // M11：每次会话重置留痕
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("message", message);
            body.put("session_id", sessionId);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(AGENT_URL))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(300))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<InputStream> resp = HTTP.send(req,
                    HttpResponse.BodyHandlers.ofInputStream());

            // 解析 SSE 流：event: <type>\ndata: <json>\n\n
            StringBuilder answer = new StringBuilder();
            String currentEvent = "";
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event: ")) {
                        currentEvent = line.substring(7).trim();
                    } else if (line.startsWith("data: ")) {
                        String json = line.substring(6);
                        JsonNode data = MAPPER.readTree(json);
                        switch (currentEvent) {
                            case "token" -> answer.append(
                                    data.path("content").asText(""));
                            case "result" -> {
                                String ans = data.path("answer").asText("");
                                if (!ans.isEmpty()) {
                                    answer.setLength(0);
                                    answer.append(ans);
                                }
                            }
                            case "error" -> {
                                System.err.println("AgentChatClient: agent error: "
                                        + data.path("error").asText());
                                return null;
                            }
                            case "skill_load" -> { // M11：捕获实际加载的技能进留痕
                                String sk = data.path("skill").asText("");
                                if (!sk.isEmpty()) {
                                    java.util.List<String> l = new java.util.ArrayList<>(LAST_TRACE.skillsLoaded());
                                    if (!l.contains(sk)) l.add(sk);
                                    LAST_TRACE = new ChatTrace(l, LAST_TRACE.toolsCalled());
                                }
                            }
                            case "tool" -> { // M11：捕获实际调用的工具进留痕
                                String tn = data.path("tool").asText("");
                                if (!tn.isEmpty()) {
                                    java.util.List<String> l = new java.util.ArrayList<>(LAST_TRACE.toolsCalled());
                                    if (!l.contains(tn)) l.add(tn);
                                    LAST_TRACE = new ChatTrace(LAST_TRACE.skillsLoaded(), l);
                                }
                            }
                            default -> { /* phase, hb, done — 忽略 */ }
                        }
                    }
                }
            }
            String result = answer.toString().trim();
            journalPut(message, result, sessionId); // Swarm journal 对齐：成功响应落盘
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            System.err.println("AgentChatClient: " + e.getClass().getName() + ": " + e.getMessage());
            return null;
        }
    }
}
