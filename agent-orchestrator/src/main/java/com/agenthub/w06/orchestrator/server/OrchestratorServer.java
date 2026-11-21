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
        AGENT = OrchestratorAgentFactory.build(props, (digest, page) -> {
            // 门禁 rail 的快照解析：编排器容器暂无快照目录，返回 null 走 MATERIAL_MISSING 语义
            // （真实快照经 S7 后续接入 checker HTTP resolver）
            return null;
        }, workspace);
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
                java.util.Map<String, Object> out = AGENT.invoke(
                        java.util.Map.of("query", message));
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
