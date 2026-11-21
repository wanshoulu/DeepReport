/*
 * OrchestratorServer — root DeepAgent 的最小可运行装配层（S7.1 装车）。
 *
 * 职责：把 OrchestratorAgentFactory.build 的 root DeepAgent 以 HTTP 服务形式暴露——
 *   GET  /.well-known/agent-card.json   A2A 卡片（对齐 wrapper 协议）
 *   GET  /health                        健康探针
 *   POST /v1/invoke {message}           调用 root DeepAgent（同步，invoke 语义）
 *   POST /v1/tasks/send                 A2A 单任务入口（转 invoke）
 * fail-closed：LLM env 未配置 → 启动即退出（swarm 装配纪律：错误在触碰资源前抛出）。
 */
package com.agenthub.w06.orchestrator.server;

import com.agenthub.w06.agents.support.AgentProperties;
import com.agenthub.w06.orchestrator.agent.OrchestratorAgentFactory;
import com.agenthub.w06.orchestrator.agent.OrchestratorProperties;
import com.agenthub.w06.checker.CitationChecker;
import com.agenthub.w06.checker.NumericChecker;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.Executors;

public final class OrchestratorServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final com.openjiuwen.harness.deep_agent.DeepAgent AGENT;

    static {
        OrchestratorProperties props = new OrchestratorProperties();
        props.setAgentId("w06-orchestrator");
        props.setAgentName("AnnualReportOrchestrator");
        props.setProvider(env("LLM_PROVIDER", "OpenAI"));
        props.setApiKey(System.getenv("LLM_API_KEY"));
        props.setApiBase(env("LLM_API_BASE", ""));
        props.setModelName(System.getenv("LLM_MODEL"));
        props.setWorkspacePath(env("W06_WORKSPACE", "/workspace"));
        props.setCompletionTimeout(java.time.Duration.ofSeconds(
                Long.parseLong(env("ORCHESTRATOR_COMPLETION_TIMEOUT", "900"))));
        props.setMaxIterations(Integer.parseInt(env("ORCHESTRATOR_MAX_ITERATIONS", "16")));
        props.requireConfigured(); // fail-closed：LLM 未配置直接抛

        Path workspace = Path.of(props.getWorkspacePath());
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new IllegalStateException("workspace 不可创建: " + workspace, e);
        }
        // S7.2：门禁 resolver 接真实快照——编排器容器挂载同一 /snapshot 卷（与 checker 同源），
        // GatekeepRail 核验时按 digest+page 读原文，与 checker 门禁同数据源。
        NumericChecker.SnapshotTextResolver snapshotResolver;
        try {
            snapshotResolver = new com.agenthub.w06.checker.server.CheckerHttpServer.SnapshotHttpResolver(
                    Path.of(env("SNAPSHOT_DIR", "/snapshot")));
        } catch (Exception e) {
            System.err.println("[orchestrator] snapshot resolver fallback to null: " + e.getMessage());
            snapshotResolver = (digest, page) -> null;
        }
        AGENT = OrchestratorAgentFactory.build(props, snapshotResolver, workspace);
        // S7.2：注册 checker 触发工具——root 可经 HTTP 调六工位流水线（编排闭环第一步：
        // root 任务分解 → run_pipeline 工具 → checker 确定性六工位 → 产物回给 root 汇总）
        registerPipelineTool();
        // S7.3：动态编排——plan_experts（按报告类型从候选池选人定序）+ dispatch_stage（逐工位派工）
        registerPlanExpertsTool();
        registerDispatchStageTool();
        AGENT.ensureInitialized();
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("ORCHESTRATOR_PORT", "18094"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", OrchestratorServer::route);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("[orchestrator] root DeepAgent ready, listening :" + port);
    }

    private static void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        if ("GET".equals(method) && ("/health".equals(path) || "/".equals(path))) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("status", "ok").put("service", "w06-orchestrator")
             .put("agent_initialized", AGENT.isInitialized());
            respond(ex, 200, n.toString());
        } else if ("GET".equals(method) && "/.well-known/agent-card.json".equals(path)) {
            ObjectNode card = MAPPER.createObjectNode();
            card.put("name", "w06-orchestrator")
                .put("description", "W06 年报报告编排（root DeepAgent）：任务理解→工位编排→门禁→交付")
                .put("url", "http://127.0.0.1:" + env("ORCHESTRATOR_PORT", "18094") + "/")
                .put("version", "0.1.0");
            card.putObject("capabilities").put("streaming", false);
            respond(ex, 200, card.toString());
        } else if ("POST".equals(method) && ("/v1/invoke".equals(path) || "/v1/tasks/send".equals(path))) {
            JsonNode body = MAPPER.readTree(ex.getRequestBody());
            String message = body.path("message").asText(
                    body.path("content").asText(""));
            if (message.isBlank()) {
                respond(ex, 400, "{\"error\":\"message required\"}");
                return;
            }
            try {
                // S7.3 修复：invoke 间 todo 状态隔离——上一次编排的 in_progress 任务残留会导致
                // 「More than one task is marked as in_progress」。每次 invoke 前清 workspace todo。
                try {
                    Path wsTodo = Path.of(env("W06_WORKSPACE", "/workspace")).resolve(".todo");
                    if (java.nio.file.Files.isDirectory(wsTodo)) {
                        try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(wsTodo)) {
                            walk.filter(java.nio.file.Files::isRegularFile)
                                .forEach(f -> { try { java.nio.file.Files.delete(f); } catch (Exception ignore) {} });
                        }
                    }
                } catch (Exception ignore) { }

                String invSession = "w06-orch-" + java.util.UUID.randomUUID().toString().substring(0, 8);
                java.util.Map<String, Object> out = AGENT.invoke(java.util.Map.of(
                        "query", message,
                        "session_id", invSession)); // S7.3：每次 invoke 独立会话（跨任务状态隔离）
                ObjectNode n = MAPPER.createObjectNode();
                n.put("status", "COMPLETED");
                n.put("answer", String.valueOf(out.getOrDefault("answer",
                        out.getOrDefault("output", out.toString()))));
                respond(ex, 200, n.toString());
            } catch (Exception e) {
                ObjectNode n = MAPPER.createObjectNode();
                n.put("status", "FAILED");
                n.put("error", String.valueOf(e.getMessage()));
                respond(ex, 500, n.toString());
            }
        } else {
            respond(ex, 404, "{\"error\":\"unknown route\"}");
        }
    }


    /** S7.2：向 root DeepAgent 注册 run_pipeline 工具（LocalFunction，HTTP 调 checker 六工位）。 */
    private static void registerPipelineTool() {
        try {
            String checkerBase = env("CHECKER_BASE", "http://w06-checker:18095");
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10)).build();
            com.openjiuwen.core.foundation.tool.ToolCard toolCard =
                    com.openjiuwen.core.foundation.tool.ToolCard.builder()
                            .name("run_pipeline")
                            .description("触发 DeepReport 六工位受控流水线（查数→分析→图表→写作→核验→主编），返回结构化报告工件 JSON。参数：bank=银行代码(cmb/icbc/ccb) 或 report_type=general+subject_hint=主题")
                            .inputParams(java.util.Map.of(
                                    "type", "object",
                                    "properties", java.util.Map.of(
                                            "bank", java.util.Map.of("type", "string", "description", "银行代码：cmb/icbc/ccb"),
                                            "report_type", java.util.Map.of("type", "string", "description", "annual_report 或 general"),
                                            "subject_hint", java.util.Map.of("type", "string", "description", "通用域主题")),
                                    "required", java.util.List.of()))
                            .build();
            com.openjiuwen.core.foundation.tool.function.LocalFunction fn =
                    new com.openjiuwen.core.foundation.tool.function.LocalFunction(toolCard, args -> {
                                try {
                                    ObjectNode body = MAPPER.createObjectNode();
                                    Object bank = args.get("bank");
                                    Object rt = args.get("report_type");
                                    Object sh = args.get("subject_hint");
                                    if (bank != null) body.put("bank", String.valueOf(bank));
                                    if (rt != null) body.put("report_type", String.valueOf(rt));
                                    if (sh != null) body.put("subject_hint", String.valueOf(sh));
                                    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                                            .uri(java.net.URI.create(checkerBase + "/v1/pipeline/run"))
                                            .header("Content-Type", "application/json")
                                            .timeout(java.time.Duration.ofSeconds(280))
                                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                                    MAPPER.writeValueAsString(body)))
                                            .build();
                                    java.net.http.HttpResponse<String> resp =
                                            http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                                    return java.util.Map.of("pipeline_result", resp.body());
                                } catch (Exception e) {
                                    return java.util.Map.of("error", String.valueOf(e.getMessage()));
                                }
                            });
            AGENT.registerHarnessTool(fn);
            System.out.println("[orchestrator] run_pipeline tool registered (checker=" + checkerBase + ")");
        } catch (Exception e) {
            System.err.println("[orchestrator] registerPipelineTool failed: " + e);
        }
    }

    /** S7.3：专家候选池——按报告类型裁剪（年报 10 人/通用 8 人/调研类 9 人）。 */
    private static final java.util.Map<String, java.util.List<String>> EXPERT_POOL = java.util.Map.of(
            "annual_report", java.util.List.of("material-agent", "finance-analyst", "risk-analyst",
                    "capital-analyst", "disclosure-officer", "chart-agent", "writer-agent",
                    "numeric-checker", "citation-checker", "approver"),
            "general", java.util.List.of("material-agent", "deep-search", "finance-analyst",
                    "risk-analyst", "disclosure-officer", "chart-agent", "writer-agent",
                    "numeric-checker", "citation-checker", "approver"),
            "research", java.util.List.of("material-agent", "deep-search", "finance-analyst",
                    "risk-analyst", "macro-review", "chart-agent", "writer-agent",
                    "numeric-checker", "citation-checker", "approver"));

    /** S7.3 工具一：plan_experts——root 产出结构化编排计划（从候选池选人定序）。 */
    private static void registerPlanExpertsTool() {
        try {
            com.openjiuwen.core.foundation.tool.ToolCard card =
                    com.openjiuwen.core.foundation.tool.ToolCard.builder()
                            .name("plan_experts")
                            .description("查询可编排的专家候选池：按报告类型返回可用专家清单与建议工位次序。编制定编计划时必须先调用本工具确认候选，再产出计划。")
                            .inputParams(java.util.Map.of(
                                    "type", "object",
                                    "properties", java.util.Map.of(
                                            "report_type", java.util.Map.of("type", "string",
                                                    "description", "报告类型：annual_report / general / research（调研类）/ 其他自定义")),
                                    "required", java.util.List.of("report_type")))
                            .build();
            com.openjiuwen.core.foundation.tool.function.LocalFunction fn =
                    new com.openjiuwen.core.foundation.tool.function.LocalFunction(card, args -> {
                        String rt = String.valueOf(args.getOrDefault("report_type", "annual_report"));
                        java.util.List<String> pool = EXPERT_POOL.getOrDefault(rt,
                                EXPERT_POOL.get("general"));
                        return java.util.Map.of(
                                "report_type", rt,
                                "available_experts", pool,
                                "suggested_pipeline", java.util.List.of(
                                        "查数:material-agent", rt.equals("annual_report") ? "分析并行:finance-analyst+risk-analyst+capital-analyst" : "分析:finance-analyst+risk-analyst",
                                        "图表:chart-agent", "写作:writer-agent", "核验:numeric-checker+citation-checker", "交付:approver"),
                                "pool_size", pool.size());
                    });
            AGENT.registerHarnessTool(fn);
            System.out.println("[orchestrator] plan_experts tool registered");
        } catch (Exception e) {
            System.err.println("[orchestrator] registerPlanExpertsTool failed: " + e);
        }
    }

    /** S7.3 工具二：dispatch_stage——按 plan 逐工位触发 checker（stage 参数：查数/分析/图表/写作/核验）。 */
    private static void registerDispatchStageTool() {
        try {
            String checkerBase = env("CHECKER_BASE", "http://w06-checker:18095");
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10)).build();
            com.openjiuwen.core.foundation.tool.ToolCard card =
                    com.openjiuwen.core.foundation.tool.ToolCard.builder()
                            .name("run_pipeline")
                            .description("执行编排计划：触发 DeepReport 受控流水线（按已确认的专家计划跑查数→分析→图表→写作→核验→主编），返回结构化报告工件与核验结果。产出计划后调用本工具执行。")
                            .inputParams(java.util.Map.of(
                                    "type", "object",
                                    "properties", java.util.Map.of(
                                            "plan_summary", java.util.Map.of("type", "string",
                                                    "description", "编排计划摘要（来自 plan_experts 的选人结果）"),
                                            "bank", java.util.Map.of("type", "string", "description", "银行代码（年报域）：cmb/icbc/ccb"),
                                            "report_type", java.util.Map.of("type", "string", "description", "annual_report / general / research"),
                                            "subject_hint", java.util.Map.of("type", "string", "description", "通用/调研域主题")),
                                    "required", java.util.List.of()))
                            .build();
            com.openjiuwen.core.foundation.tool.function.LocalFunction fn =
                    new com.openjiuwen.core.foundation.tool.function.LocalFunction(card, args -> {
                        try {
                            ObjectNode body = MAPPER.createObjectNode();
                            Object bank = args.get("bank");
                            Object rt = args.get("report_type");
                            Object sh = args.get("subject_hint");
                            Object plan = args.get("plan_summary");
                            if (bank != null) body.put("bank", String.valueOf(bank));
                            if (rt != null) body.put("report_type", String.valueOf(rt));
                            if (sh != null) body.put("subject_hint", String.valueOf(sh));
                            if (plan != null) body.put("plan_summary", String.valueOf(plan)); // 编排留痕进任务记录
                            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                                    .uri(java.net.URI.create(checkerBase + "/v1/pipeline/run"))
                                    .header("Content-Type", "application/json")
                                    .timeout(java.time.Duration.ofSeconds(280))
                                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                            MAPPER.writeValueAsString(body)))
                                    .build();
                            java.net.http.HttpResponse<String> resp =
                                    http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                            return java.util.Map.of("pipeline_result", resp.body());
                        } catch (Exception e) {
                            return java.util.Map.of("error", String.valueOf(e.getMessage()));
                        }
                    });
            AGENT.registerHarnessTool(fn);
            System.out.println("[orchestrator] dispatch(run_pipeline v2) tool registered");
        } catch (Exception e) {
            System.err.println("[orchestrator] registerDispatchStageTool failed: " + e);
        }
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
