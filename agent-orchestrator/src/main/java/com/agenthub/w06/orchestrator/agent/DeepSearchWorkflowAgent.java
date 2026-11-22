/*
 * DeepSearchWorkflowAgent — 深度调研 agent 的框架原生 Workflow 实现（S13.4）。
 *
 * 形态判定（基于代码证据）：控制流由代码固定（规划→搜索→抓取→落盘→评估→循环），
 * LLM 只在固定节点被调用、不决定控制流 → Workflow 形态（非 ReAct/DeepAgent）。
 *
 * 框架原生机制（Python spike 逐一验证后移植 Java）：
 *   - LoopComponent + LoopGroup：3 轮循环由框架管理（loop_type=number）
 *   - 数据流契约：add_connection 只定顺序；数据经 inputs_schema 的 ${comp.field} 引用
 *   - 跨轮状态：循环体组件实例变量（框架每轮复用同一 executable 实例，spike 实证）
 *   - output_schema 用 ${round_exec.xxx} 引用收集每轮结果
 *
 * 循环体 RoundExecComponent 每轮：
 *   LLM 规划查询(HTTP /v1/llm) → 质量过滤 → web_search → 白名单排序去重
 *   → web_fetch top3 → 低质过滤 → snapshot_commit 落盘（HTTP checker /v1/tools/call）
 *   → LLM 评估充分性 → 返回本轮产物
 *
 * fail-safe：任何异常返回 null，调用方降级旧 Java 循环逻辑（不阻断流水线）。
 */
package com.agenthub.w06.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.WorkflowCard;
import com.openjiuwen.core.workflow.WorkflowOutput;
import com.openjiuwen.core.workflow.ComponentComposable;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.core.workflow.components.flow.StartComponent;
import com.openjiuwen.core.workflow.components.flow.EndComponent;
import com.openjiuwen.core.workflow.components.flow.loop.LoopComponent;
// S13.4：用父类 component.loop.LoopGroup——桥接类 components.flow.loop.LoopGroup 的
// addWorkflowComp 存在无限递归 bug（Java 双语移植缺陷，spike CASE2 实证），父类正常。
import com.openjiuwen.core.workflow.component.loop.LoopGroup;
import com.openjiuwen.core.workflow.component.EndConfig;
import com.openjiuwen.core.session.BaseSession;
import com.openjiuwen.core.session.internal.WorkflowSession;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DeepSearchWorkflowAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> AUTH_DOMAINS = List.of(
            "arxiv.org", "ieee.org", "acm.org", "github.com", "docs.", "developer.",
            "springer.com", "openreview.net", "usenix.org", "zh.wikipedia.org");

    private final String llmUrl;      // 编排器自身的 /v1/llm 端点（单次 LLM 调用）
    private final String toolsUrl;    // checker /v1/tools/call（web_search/web_fetch/snapshot_commit）
    private final int maxRounds;

    public DeepSearchWorkflowAgent(String llmUrl, String toolsUrl, int maxRounds) {
        this.llmUrl = llmUrl;
        this.toolsUrl = toolsUrl;
        this.maxRounds = maxRounds;
    }

    /** 运行 deep-search Workflow，返回结构化结果；失败返回 null（调用方降级）。 */
    public ObjectNode run(String subject) {
        try {
            RoundExecComponent roundComp = new RoundExecComponent(subject, llmUrl, toolsUrl);
            LoopGroup lg = new LoopGroup();
            lg.addWorkflowComp("round_exec", new RoundComposable(roundComp));
            lg.start_nodes(List.of("round_exec"));
            lg.end_nodes(List.of("round_exec"));

            Workflow wf = new Workflow(new WorkflowCard("deep-search-wf", "深度调研 Workflow（框架原生）"));
            wf.setStartComp("node_start", new StartComponent(), Map.of("subject", "${subject}"), null);
            // loop config：number 型固定 maxRounds 轮；output_schema ${} 引用收集每轮结果
            Map<String, Object> loopCfg = new LinkedHashMap<>();
            loopCfg.put("loop_type", "number");
            loopCfg.put("loop_number", maxRounds);
            wf.addWorkflowComp("node_loop",
                    new LoopComponent(lg, Map.of(
                            "committed", "${round_exec.committed_this_round}",
                            "gathered", "${round_exec.gathered}",
                            "sufficient", "${round_exec.sufficient}",
                            "round_no", "${round_exec.round_no}")),
                    loopCfg, null);
            wf.setEndComp("node_end", new EndComponent(new EndConfig("{{result}}")),
                    Map.of("result", "${node_loop.gathered}"), null);
            wf.addConnection("node_start", "node_loop");
            wf.addConnection("node_loop", "node_end");

            WorkflowSession session = new WorkflowSession("deep-search-"
                    + Integer.toHexString(subject.hashCode()));
            WorkflowOutput out = wf.invoke(Map.of("subject", subject), session, null);
            if (out == null || out.getState() == null) return null;
            // 结果主体从 roundComp 实例取（跨轮状态在实例变量，spike 实证可靠）
            return roundComp.buildResult(out);
        } catch (Throwable e) {
            System.err.println("[DeepSearchWorkflowAgent] workflow failed: " + e);
            return null;
        }
    }

    // ==================== 循环体组件 ====================

    /** ComponentComposable 包装（框架要求 composable→executable 两层）。 */
    static final class RoundComposable implements ComponentComposable {
        private final RoundExecComponent exec;
        RoundComposable(RoundExecComponent exec) { this.exec = exec; }
        @Override public com.openjiuwen.core.graph.Executable<?, ?> toExecutable() { return exec; }
    }

    /** 一轮深搜：LLM 规划 → 搜索 → 抓取 → 落盘 → LLM 评估。跨轮状态存实例变量。 */
    static final class RoundExecComponent extends ComponentExecutable<Object, Object> {
        private final String subject;
        private final String llmUrl;
        private final String toolsUrl;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        // 跨轮状态（框架复用同一实例）
        private final StringBuilder gathered = new StringBuilder();
        private final Set<String> seenUrls = new HashSet<>();
        private final List<String> committedAll = new ArrayList<>();
        private final List<ObjectNode> roundNodes = new ArrayList<>();
        private int roundNo = 0;
        private int totalCommitted = 0;
        private boolean sufficient = false;
        private int llmCalls = 0;
        private int skippedLow = 0;

        RoundExecComponent(String subject, String llmUrl, String toolsUrl) {
            this.subject = subject; this.llmUrl = llmUrl; this.toolsUrl = toolsUrl;
        }

        @Override
        public Object onInvoke(Object inputs, BaseSession session, Object... extras) {
            roundNo++;
            ObjectNode rd = MAPPER.createObjectNode();
            rd.put("round", roundNo);
            try {
                // a) LLM 规划本轮查询（预算 8 次内）
                List<String> queries = planQueries();
                rd.put("plan_source", queries.isEmpty() ? "LLM 不可达，降级默认查询" : "框架 LLM 规划");
                if (queries.isEmpty()) {
                    queries = List.of(subject + " 技术架构 原理", subject + " 评测 对比 实践", subject + " 官方文档");
                }
                // 质量防线：长度≥8 且含主题词
                List<String> kept = new ArrayList<>();
                for (String q : queries) {
                    String qq = q == null ? "" : q.trim();
                    boolean hasSubjectWord = qq.contains(subject)
                            || java.util.Arrays.stream(subject.split("[\\s，,的与和]+"))
                                    .anyMatch(w -> w.length() >= 2 && qq.contains(w));
                    if (qq.length() >= 8 && hasSubjectWord) kept.add(qq);
                }
                if (!kept.isEmpty()) queries = kept;
                rd.set("queries", MAPPER.valueToTree(queries));

                // b) web_search 聚合
                List<String[]> hits = new ArrayList<>();
                for (String q : queries) {
                    JsonNode sr = toolCall("web_search", Map.of("query", q, "max_results", 4));
                    if (sr == null) continue;
                    for (JsonNode res : sr.path("results")) {
                        String link = res.path("link").asText(res.path("url").asText(""));
                        if (link.isEmpty() || !seenUrls.add(link)) continue;
                        hits.add(new String[]{res.path("title").asText(""), link});
                    }
                }
                rd.put("search_hits", hits.size());
                // 白名单域优先
                hits.sort((x, y) -> Boolean.compare(
                        AUTH_DOMAINS.stream().anyMatch(d -> y[1].contains(d)),
                        AUTH_DOMAINS.stream().anyMatch(d -> x[1].contains(d))));

                // c) 抓取 top≤3 → 低质过滤 → snapshot_commit
                int c = 0;
                for (int i = 0; i < hits.size() && c < 3; i++) {
                    String[] hit = hits.get(i);
                    try {
                        JsonNode fr = toolCall("web_fetch", Map.of("url", hit[1]));
                        if (fr == null) continue;
                        String text = fr.path("text").asText("");
                        if (!fr.path("ok").asBoolean(false) || text.isBlank()) continue;
                        String title = fr.path("title").asText(hit[0].isEmpty() ? hit[1] : hit[0]);
                        String tl = title.toLowerCase();
                        boolean lowQuality = title.contains("的意思与解释") || title.contains("拼音")
                                || tl.contains("has been blocked") || title.contains("立即登录")
                                || title.contains("登录/注册") || title.trim().length() < 8
                                || tl.contains("sign in") || title.contains("请登录");
                        if (lowQuality) { skippedLow++; continue; }
                        Map<String, Object> pg = Map.of("text", text, "origin_page", 1);
                        JsonNode cr = toolCall("snapshot_commit", Map.of(
                                "url", hit[1], "title", title,
                                "authority", authorityForUrl(hit[1]),
                                "pages", List.of(pg)));
                        if (cr != null && cr.path("ok").asBoolean(false)) {
                            c++; totalCommitted++;
                            committedAll.add(title + " — " + hit[1]);
                            gathered.append("· ").append(title).append("：")
                                    .append(text, 0, Math.min(text.length(), 300)).append('\n');
                        }
                    } catch (Exception ignore) { /* 单条失败不阻断（fail-open） */ }
                }
                rd.put("committed", c);

                // d) LLM 评估充分性（最后一轮不评估）
                if (roundNo < maxRoundsLocal()) {
                    sufficient = evalSufficiency();
                    rd.put("sufficient", sufficient);
                }
                roundNodes.add(rd);
            } catch (Exception e) {
                rd.put("error", String.valueOf(e.getMessage()));
                roundNodes.add(rd);
            }
            // 输出（供 LoopComponent output_schema ${round_exec.xxx} 收集）
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("round_no", roundNo);
            out.put("committed_this_round", rd.path("committed").asInt(0));
            out.put("gathered", gathered.toString());
            out.put("sufficient", sufficient);
            return out;
        }

        private int maxRoundsLocal() { return Integer.parseInt(System.getenv().getOrDefault("DEEP_SEARCH_MAX_ROUNDS", "3")); }

        private List<String> planQueries() {
            if (llmCalls++ >= 8) return List.of();
            String prompt = "你是深度调研 agent（deep-search）。研究主题：" + subject
                    + "。已有资料要点：" + (gathered.length() == 0 ? "（无，首轮）"
                            : gathered.substring(0, Math.min(gathered.length(), 1500)))
                    + "。请规划本轮 2 个互补的联网搜索查询。要求：每个查询≥10字、含主题关键词、"
                    + "使用具体技术名词（禁止泛化词），避免与已有资料重复。"
                    + "只输出 JSON：{\"queries\": [\"查询1\", \"查询2\"]}";
            String resp = llmCall(prompt);
            if (resp == null) return List.of();
            try {
                int b = resp.indexOf('{'), e = resp.lastIndexOf('}');
                if (b < 0 || e <= b) return List.of();
                JsonNode n = MAPPER.readTree(resp.substring(b, e + 1));
                List<String> qs = new ArrayList<>();
                for (JsonNode q : n.path("queries")) qs.add(q.asText());
                return qs;
            } catch (Exception ex) { return List.of(); }
        }

        private boolean evalSufficiency() {
            if (llmCalls++ >= 8) return false;
            String prompt = "深度调研充分性评估。研究主题：" + subject
                    + "。已收集资料要点：" + (gathered.length() == 0 ? "（尚无有效资料）" : gathered.toString())
                    + "。作为深度调研/投研报告的资料基础是否已充分？只输出 JSON：{\"sufficient\": true 或 false, \"reason\": \"一句话理由\"}";
            String resp = llmCall(prompt);
            if (resp == null) return false;
            try {
                int b = resp.indexOf('{'), e = resp.lastIndexOf('}');
                if (b < 0 || e <= b) return false;
                return MAPPER.readTree(resp.substring(b, e + 1)).path("sufficient").asBoolean(false);
            } catch (Exception ex) { return false; }
        }

        private String llmCall(String prompt) {
            try {
                ObjectNode body = MAPPER.createObjectNode();
                body.put("prompt", prompt);
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(llmUrl))
                        .header("Content-Type", "application/json").timeout(Duration.ofSeconds(90))
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))).build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) return null;
                JsonNode n = MAPPER.readTree(resp.body());
                return "OK".equals(n.path("status").asText()) ? n.path("text").asText("") : null;
            } catch (Exception e) { return null; }
        }

        private JsonNode toolCall(String tool, Map<String, ?> args) {
            try {
                ObjectNode body = MAPPER.createObjectNode();
                body.put("tool_name", tool);
                body.set("arguments", MAPPER.valueToTree(args));
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(toolsUrl))
                        .header("Content-Type", "application/json").timeout(Duration.ofSeconds(120))
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))).build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                return resp.statusCode() == 200 ? MAPPER.readTree(resp.body()) : null;
            } catch (Exception e) { return null; }
        }

        private static String authorityForUrl(String url) {
            String u = url.toLowerCase();
            if (u.contains("arxiv.org") || u.contains("ieee.org") || u.contains("acm.org")
                    || u.contains("openreview") || u.contains("usenix.org")) return "PRIMARY_RESEARCH";
            if (u.contains("github.com") || u.contains("docs.") || u.contains("developer.")
                    || u.contains("springer.com")) return "PRIMARY_VENDOR";
            if (u.contains("wikipedia")) return "SECONDARY_ENCYCLOPEDIA";
            return "TERTIARY_AGGREGATED";
        }

        /** 汇总 Workflow 产物（rounds 明细在实例变量，比从 loop 输出解析可靠）。 */
        ObjectNode buildResult(WorkflowOutput out) {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("agent", "deep-search");
            result.put("engine", "openjiuwen-Workflow(LoopComponent)");
            result.set("rounds", MAPPER.valueToTree(roundNodes));
            result.set("committed", MAPPER.valueToTree(committedAll));
            result.put("round_count", roundNo);
            result.put("committed_count", totalCommitted);
            result.put("skipped_low_quality", skippedLow);
            if (sufficient) result.put("stopped_reason", "框架 LLM 评估资料已充分，提前收束标记");
            result.put("workflow_state", String.valueOf(out.getState()));
            return result;
        }
    }
}
