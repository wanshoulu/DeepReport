package com.agenthub.w06.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Executors;

/**
 * A2A wrapper 启动层（0.1.0）
 *
 * 对外讲 A2A 协议（agent-card + tasks/send 单任务演示链），对内把任务落到
 * checker 服务（:18095）执行确定性核验：
 *   tasks/send → 读正例工件 → 转 POST /v1/checks/numeric + /v1/checks/citation
 *             → 回传「工件 + 双核验结果 + 内容指纹 + 工作台报告链接」
 *
 * 纯 JDK HttpServer + Jackson，无框架；容器内通过 w06-net 网络名访问 checker。
 */
public final class A2aWrapperServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final String A2A_PORT = env("A2A_PORT", "18094");
    private static final String CHECKER_BASE = env("CHECKER_BASE", "http://w06-checker:18095");
    private static final String DEMO_DIR = env("DEMO_DIR", "/app/demo");
    private static final String WORKBENCH_URL = env("WORKBENCH_URL", "");

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(A2A_PORT);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", A2aWrapperServer::route);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("[a2a-wrapper] listening on :" + port + " (checker=" + CHECKER_BASE + ")");
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    private static void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        // 网关前缀归一化：反向代理常带 /a2a 前缀，strip 后与根路径同语义
        if (path.equals("/a2a")) {
            path = "/";
        } else if (path.startsWith("/a2a/")) {
            path = path.substring("/a2a".length());
        }
        String method = ex.getRequestMethod();
        try {
            if ("GET".equals(method) && ("/.well-known/agent-card.json".equals(path) || "/agent-card.json".equals(path))) {
                respond(ex, 200, agentCard(ex));
            } else if ("POST".equals(method) && ("/v1/tasks/send".equals(path) || "/tasks/send".equals(path))) {
                respond(ex, 200, tasksSend(ex));
            } else if ("GET".equals(method) && "/health".equals(path)) {
                ObjectNode n = MAPPER.createObjectNode();
                n.put("status", "ok").put("service", "a2a-wrapper").put("version", "0.1.0")
                        .put("checker_base", CHECKER_BASE);
                respond(ex, 200, n);
            } else if ("GET".equals(method) && "/".equals(path)) {
                ObjectNode n = MAPPER.createObjectNode();
                n.put("service", "w06-annual-report-agent A2A wrapper");
                ArrayNode eps = n.putArray("endpoints");
                eps.add("GET  /.well-known/agent-card.json");
                eps.add("POST /v1/tasks/send");
                eps.add("GET  /health");
                respond(ex, 200, n);
            } else {
                ObjectNode n = MAPPER.createObjectNode();
                n.put("error", "not found: " + method + " " + path);
                respond(ex, 404, n);
            }
        } catch (Exception e) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            respond(ex, 500, n);
        }
    }

    /** agent-card：url 由请求 Host 头推导，外部访问地址始终正确 */
    private static ObjectNode agentCard(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        String base = host != null ? "http://" + host + "/" : "http://localhost:" + A2A_PORT + "/";
        ObjectNode card = MAPPER.createObjectNode();
        card.put("name", "w06-annual-report-agent");
        card.put("description", "通用报告生成智能体（年报域首发，扩展支持调研/分析等报告类型）："
                + "多专业 agent 产出结构化工件（指标+引用+章节），经确定性数值核验与引用溯源后受控交付；"
                + "交付批准与工件内容指纹绑定。");
        card.put("url", base);
        card.put("version", "0.1.0");
        ObjectNode caps = card.putObject("capabilities");
        caps.put("streaming", false).put("pushNotifications", false).put("stateTransitionHistory", false);
        ArrayNode in = card.putArray("defaultInputModes");
        in.add("application/json");
        ArrayNode out = card.putArray("defaultOutputModes");
        out.add("application/json");
        ObjectNode skill = card.putArray("skills").addObject();
        skill.put("id", "generate_annual_report");
        skill.put("name", "受控报告生成");
        skill.put("description", "输入报告主题（年报域为企业与报告期），产出经数值与引用双重核验的"
                + "结构化报告工件，附带核验结果、内容指纹与工作台报告链接。");
        ArrayNode tags = skill.putArray("tags");
        tags.add("annual-report").add("general-report").add("verification").add("citations");
        ObjectNode provider = card.putObject("provider");
        provider.put("organization", "agent-hub-alpha");
        provider.put("url", "https://github.com/chaosxingxc-orion/agent-hub-alpha");
        return card;
    }

    /** tasks/send：单任务演示链 —— 正例工件 → 双核验 → 结果与指纹回传 */
    private static ObjectNode tasksSend(HttpExchange ex) throws Exception {
        byte[] bodyBytes = ex.getRequestBody().readAllBytes();
        JsonNode req = bodyBytes.length > 0 ? MAPPER.readTree(bodyBytes) : MAPPER.createObjectNode();

        String taskId = req.at("/params/task/task_id").asText(
                req.at("/params/id").asText("w06-demo-2025"));
        String inputText = req.at("/params/task/input/message/parts/0/text").asText("");

        byte[] artifact = Files.readAllBytes(Path.of(DEMO_DIR, "positive-artifact.json"));

        ObjectNode result = MAPPER.createObjectNode();
        result.put("task_id", taskId);
        result.put("context_id", "w06-annual-report");
        ObjectNode status = result.putObject("status");
        status.put("state", "completed");
        status.put("timestamp", Instant.now().toString());

        ArrayNode artifacts = result.putArray("artifacts");
        ObjectNode art = artifacts.addObject();
        art.put("artifact_id", "artifact-1");
        art.put("name", "annual-report-artifact");
        ArrayNode parts = art.putArray("parts");
        ObjectNode part = parts.addObject();
        part.put("kind", "json");
        part.set("json", MAPPER.readTree(artifact));

        ObjectNode meta = art.putObject("metadata");
        try {
            JsonNode numeric = postJson(CHECKER_BASE + "/v1/checks/numeric", artifact);
            JsonNode citation = postJson(CHECKER_BASE + "/v1/checks/citation", artifact);
            JsonNode digest = postJson(CHECKER_BASE + "/v1/artifacts/digest", artifact);
            boolean passed = "PASSED".equals(numeric.path("status").asText())
                    && "PASSED".equals(citation.path("status").asText());
            meta.put("verification_passed", passed);
            meta.set("numeric_check", numeric);
            meta.set("citation_check", citation);
            meta.put("artifact_digest", digest.path("artifact_digest").asText(""));
        } catch (Exception e) {
            meta.put("verification_passed", false);
            meta.put("checker_error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        meta.put("reference_answer", "/demo/reference-answer.json");
        meta.put("workbench_report", workbenchUrl(ex));

        ObjectNode root = MAPPER.createObjectNode();
        root.put("jsonrpc", "2.0");
        if (req.hasNonNull("id")) {
            root.set("id", req.get("id"));
        } else {
            root.put("id", "1");
        }
        root.set("result", result);
        if (!inputText.isEmpty()) {
            ObjectNode rm = result.putObject("metadata");
            rm.put("input_text", inputText);
        }
        return root;
    }

    /** 工作台地址：优先环境变量；否则由请求 Host 推导（wrapper 端口 18084 → checker 对外 18083） */
    private static String workbenchUrl(HttpExchange ex) {
        if (!WORKBENCH_URL.isEmpty()) {
            return WORKBENCH_URL;
        }
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null) {
            return "http://localhost:18083/";
        }
        if (host.endsWith(":18084")) {
            return "http://" + host.substring(0, host.length() - ":18084".length()) + ":18083/";
        }
        return "http://" + host + "/";
    }

    private static JsonNode postJson(String url, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return MAPPER.readTree(resp.body());
    }

    private static void respond(HttpExchange ex, int code, ObjectNode node) throws IOException {
        byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private A2aWrapperServer() {
    }
}
