/*
 * CheckerHttpServer — W06 核验服务的 HTTP 启动层（纯 JDK HttpServer，无 Spring）。
 *
 * 路由：
 *  GET  /health                          健康检查
 *  GET  /v1/sources                      信源注册表（manifest sources[]，真实 sha256）
 *  GET  /v1/snapshot/manifest            快照 manifest 全文（页 digest / 释义表 / 指标索引）
 *  GET  /v1/snapshot/pages/{n}            按页码读快照页文本（含该页 digest）
 *  POST /v1/checks/numeric               数值纪律检查（N1–N7）
 *  POST /v1/checks/citation              引用核验（含信源注册表与支持度）
 *  GET  /demo/{positive|error}-artifact.json  演示工件（受控样例）
 *  GET  /demo/reference-answer.json      W01 标准答案
 *  POST /v1/artifacts/digest             工件规范化 digest（Jackson 重序列化字节 → sha256）
 *  GET  /v1/approvals?task_id=           查询某任务的最新交付批准
 *  POST /v1/approvals?approver=...       申请交付批准：服务端重跑两项检查，
 *                                       任一非 PASSED → 409 REFUSED（错误草稿不能获得批准）
 *  GET  /v1/tasks                        报告库：历史任务列表（刷新不丢，服务端内存态）
 *  GET  /v1/tasks/{id}                   任务详情（task 记录 + 完整工件）
 *  POST /v1/tasks                        登记任务 {task,artifact}：服务端指派编号、
 *                                       同步工件 task_id、计算规范化 digest（upsert）
 *  GET  /v1/agents                       Agent Hub：{runtime, catalog}
 *  POST /v1/agents/install {agent_id}    市场目录 agent 安装注册 → 加入运行时
 *  POST /v1/agents/load {agent_id,task_id} 运行时 agent 按任务动态按需加载（记录最近加载）
 *  GET  /                                 workbench.html（工作台，五页签版）
 *
 * 环境变量：CHECKER_PORT（默认 18095）、SNAPSHOT_DIR（默认 /snapshot）、
 *          STATIC_DIR（默认 /app，含 workbench.html / testpage.html / demo/）
 */

package com.agenthub.w06.checker.server;

import com.agenthub.w06.checker.CitationChecker;
import com.agenthub.w06.checker.NumericChecker;
import com.agenthub.w06.contract.AnnualReportArtifacts;
import com.agenthub.w06.contract.CheckResult;
import com.agenthub.w06.contract.SourceEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class CheckerHttpServer {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final NumericChecker NUMERIC = new NumericChecker();
    static final CitationChecker CITATION = new CitationChecker();
    static final Map<String, ObjectNode> APPROVALS = new HashMap<>(); // task_id -> 最新批准
    static final AtomicInteger APPROVAL_SEQ = new AtomicInteger();

    // 报告库（任务历史，内存态：容器存活期内刷新不丢）
    static final Map<String, ObjectNode> TASKS = new LinkedHashMap<>();               // task_id -> 任务记录
    static final Map<String, AnnualReportArtifacts> TASK_ARTIFACTS = new HashMap<>();  // task_id -> 交付工件
    static final AtomicInteger TASK_SEQ = new AtomicInteger(2);                       // 预置 001/002，新任务从 003 递增

    // Agent Hub：运行时（已注册，可动态加载）+ 市场目录（可安装注册）
    static final Map<String, ObjectNode> AGENT_RUNTIME = new LinkedHashMap<>();
    static final Map<String, ObjectNode> AGENT_CATALOG = new LinkedHashMap<>();

    // ———————————————————— 快照解析器 ————————————————————

    /**
     * 页文本解析：byDigest 精确命中（sha256(页文本)）；
     * digest 为信源 content_sha256 时按「信源注册页」定位；
     * 都未命中则按页码回退（页文本必须真实存在，绝不凭空生成）。
     */
    static final class SnapshotHttpResolver implements NumericChecker.SnapshotTextResolver {
        final Map<String, String> byPageDigest = new HashMap<>();
        final Map<Integer, String> byPageNumber = new TreeMap<>();
        final Map<String, Set<Integer>> sourcePages = new HashMap<>();

        SnapshotHttpResolver(Path snapshotDir) throws IOException {
            Path pagesDir = snapshotDir.resolve("pages");
            if (Files.isDirectory(pagesDir)) {
                try (var stream = Files.list(pagesDir)) {
                    for (var f : stream.filter(p -> p.getFileName().toString()
                                    .matches("page-\\d+\\.txt"))
                            .sorted().toList()) {
                        String text = Files.readString(f, StandardCharsets.UTF_8);
                        int num = Integer.parseInt(f.getFileName().toString()
                                .replace("page-", "").replace(".txt", ""));
                        byPageNumber.put(num, text);
                        byPageDigest.put(sha256(text), text);
                    }
                }
            }
            JsonNode manifest = MAPPER.readTree(snapshotDir.resolve("manifest.json").toFile());
            for (JsonNode src : manifest.path("sources")) {
                Set<Integer> pages = new HashSet<>();
                for (JsonNode p : src.path("pages")) {
                    pages.add(p.asInt());
                }
                sourcePages.put(src.path("content_sha256").asText(), pages);
            }
        }

        @Override
        public String textOf(String digest, int page) {
            if (digest != null) {
                String hit = byPageDigest.get(digest);
                if (hit != null) {
                    return hit;
                }
                Set<Integer> pages = sourcePages.get(digest);
                if (pages != null && pages.contains(page)) {
                    return byPageNumber.get(page);
                }
            }
            return byPageNumber.get(page);
        }
    }

    static List<SourceEntry> loadSourceEntries(Path snapshotDir) throws IOException {
        JsonNode manifest = MAPPER.readTree(snapshotDir.resolve("manifest.json").toFile());
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode src : manifest.path("sources")) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("source_id", src.path("source_id").asText());
            node.put("title", src.path("title").asText());
            node.put("authority", src.path("authority").asText());
            node.put("origin_url", src.path("origin_url").asText());
            node.put("fetched_at", src.path("fetched_at").asText());
            node.put("content_sha256", src.path("content_sha256").asText());
            ArrayNode pages = node.putArray("pages");
            for (JsonNode p : src.path("pages")) {
                pages.add(p.asInt());
            }
            out.add(node);
        }
        SourceEntry[] entries = new SourceEntry[out.size()];
        for (int i = 0; i < out.size(); i++) {
            entries[i] = MAPPER.treeToValue(out.get(i), SourceEntry.class);
        }
        return List.of(entries);
    }

    // ———————————————————— main 与路由 ————————————————————

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("CHECKER_PORT", "18095"));
        Path snapshotDir = Path.of(System.getenv().getOrDefault("SNAPSHOT_DIR", "/snapshot"));
        Path staticDir = Path.of(System.getenv().getOrDefault("STATIC_DIR", "/app"));

        SnapshotHttpResolver resolver = new SnapshotHttpResolver(snapshotDir);
        List<SourceEntry> sources = loadSourceEntries(snapshotDir);
        preloadTasks(staticDir, resolver, sources);   // 报告库预置 2 条历史任务
        seedAgents();                                 // Agent Hub：9 运行时 + 3 市场目录

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));

        server.createContext("/", ex -> {
            try {
                route(ex, snapshotDir, staticDir, resolver, sources);
            } catch (Exception e) {
                respond(ex, 500, errorNode("internal_error", String.valueOf(e)));
            } finally {
                ex.close();
            }
        });

        server.start();
        System.out.println("W06 checker server up on :" + port
                + "  snapshot=" + snapshotDir + "  sources=" + sources.size()
                + "  numeric=" + NumericChecker.CHECKER_VERSION);
    }

    private static void route(HttpExchange ex, Path snapshotDir, Path staticDir,
                              SnapshotHttpResolver resolver, List<SourceEntry> sources) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        if ("/health".equals(path)) {
            ObjectNode ok = MAPPER.createObjectNode();
            ok.put("status", "ok");
            ok.put("service", "w06-checker");
            ok.put("numeric_checker_version", NumericChecker.CHECKER_VERSION);
            ok.put("citation_checker_version", CitationChecker.CHECKER_VERSION);
            respond(ex, 200, ok);
            return;
        }
        if ("/v1/sources".equals(path)) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (SourceEntry s : sources) {
                ObjectNode n = MAPPER.createObjectNode();
                n.put("source_id", s.sourceId());
                n.put("title", s.title());
                n.put("authority", s.authority() == null ? null : s.authority().name());
                n.put("content_sha256", s.contentSha256());
                ArrayNode pages = n.putArray("pages");
                if (s.pages() != null) {
                    s.pages().forEach(pages::add);
                }
                arr.add(n);
            }
            respond(ex, 200, arr);
            return;
        }
        if ("/v1/snapshot/manifest".equals(path)) {
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            respondRaw(ex, 200, Files.readAllBytes(snapshotDir.resolve("manifest.json")));
            return;
        }
        if (path.startsWith("/v1/snapshot/pages/")) {
            int page = Integer.parseInt(path.substring("/v1/snapshot/pages/".length()));
            String text = resolver.textOf(null, page);
            if (text == null) {
                respond(ex, 404, errorNode("page_not_found", "快照无第 " + page + " 页"));
                return;
            }
            ObjectNode n = MAPPER.createObjectNode();
            n.put("page", page);
            n.put("digest", sha256(text));
            n.put("text", text);
            respond(ex, 200, n);
            return;
        }
        if ("/v1/checks/numeric".equals(path) && "POST".equals(method)) {
            handleCheck(ex, resolver, draft -> NUMERIC.check(draft, resolver));
            return;
        }
        if ("/v1/checks/citation".equals(path) && "POST".equals(method)) {
            handleCheck(ex, resolver, draft -> CITATION.check(draft, resolver, sources));
            return;
        }
        if (path.startsWith("/demo/") && "GET".equals(method)) {
            String name = path.substring("/demo/".length());
            if (!List.of("positive-artifact.json", "error-artifact.json",
                    "reference-answer.json", "metric-spec.json").contains(name)) {
                respond(ex, 404, errorNode("not_found", "未知演示工件: " + name));
                return;
            }
            Path f = staticDir.resolve("demo").resolve(name);
            if (!Files.isRegularFile(f)) {
                respond(ex, 404, errorNode("not_found", "演示工件缺失: " + name));
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            respondRaw(ex, 200, Files.readAllBytes(f));
            return;
        }
        if ("/v1/artifacts/digest".equals(path) && "POST".equals(method)) {
            AnnualReportArtifacts draft = MAPPER.readValue(ex.getRequestBody(), AnnualReportArtifacts.class);
            String digest = "sha256:" + sha256(MAPPER.writeValueAsBytes(draft));
            ObjectNode n = MAPPER.createObjectNode();
            n.put("task_id", draft.taskId());
            n.put("artifact_digest", digest);
            respond(ex, 200, n);
            return;
        }
        if ("/v1/approvals".equals(path)) {
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
            if ("GET".equals(method)) {
                ObjectNode approval = APPROVALS.get(query.getOrDefault("task_id", ""));
                respond(ex, 200, approval == null ? emptyApproval(query.get("task_id")) : approval);
                return;
            }
            if ("POST".equals(method)) {
                handleApproval(ex, resolver, sources, query.get("approver"));
                return;
            }
        }
        if ("/v1/tasks".equals(path)) {
            if ("GET".equals(method)) {
                ArrayNode arr = MAPPER.createArrayNode();
                for (ObjectNode t : TASKS.values()) arr.add(t.deepCopy());
                respond(ex, 200, arr);
                return;
            }
            if ("POST".equals(method)) {
                handleTaskRegister(ex);
                return;
            }
        }
        if (path.startsWith("/v1/tasks/") && "GET".equals(method)) {
            String id = path.substring("/v1/tasks/".length());
            ObjectNode task = TASKS.get(id);
            AnnualReportArtifacts artifact = TASK_ARTIFACTS.get(id);
            if (task == null || artifact == null) {
                respond(ex, 404, errorNode("task_not_found", "报告库无任务 " + id));
                return;
            }
            ObjectNode out = MAPPER.createObjectNode();
            out.set("task", task.deepCopy());
            out.set("artifact", MAPPER.valueToTree(artifact));
            respond(ex, 200, out);
            return;
        }
        if ("/v1/agents".equals(path) && "GET".equals(method)) {
            ObjectNode out = MAPPER.createObjectNode();
            ArrayNode runtime = out.putArray("runtime");
            for (ObjectNode a : AGENT_RUNTIME.values()) runtime.add(a.deepCopy());
            ArrayNode catalog = out.putArray("catalog");
            for (ObjectNode a : AGENT_CATALOG.values()) catalog.add(a.deepCopy());
            respond(ex, 200, out);
            return;
        }
        if ("/v1/agents/install".equals(path) && "POST".equals(method)) {
            handleAgentInstall(ex);
            return;
        }
        if ("/v1/agents/load".equals(path) && "POST".equals(method)) {
            handleAgentLoad(ex);
            return;
        }
        if ("/".equals(path) || "/workbench.html".equals(path) || "/testpage.html".equals(path)) {
            String file = "/".equals(path) ? "workbench.html" : path.substring(1);
            Path f = staticDir.resolve(file);
            if (!Files.isRegularFile(f)) {
                respond(ex, 404, errorNode("not_found", file + " 未部署"));
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            respondRaw(ex, 200, Files.readAllBytes(f));
            return;
        }
        respond(ex, 404, errorNode("not_found", "未知路由: " + method + " " + path));
    }

    // ———————————————————— 检查与批准 ————————————————————

    private static void handleCheck(HttpExchange ex, SnapshotHttpResolver resolver,
                                    java.util.function.Function<AnnualReportArtifacts, CheckResult> checker)
            throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        AnnualReportArtifacts draft;
        try {
            draft = MAPPER.readValue(body, AnnualReportArtifacts.class);
        } catch (Exception e) {
            respond(ex, 400, errorNode("bad_request", "工件反序列化失败: " + e.getMessage()));
            return;
        }
        try {
            respond(ex, 200, MAPPER.valueToTree(checker.apply(draft)));
        } catch (Exception e) {
            respond(ex, 500, errorNode("check_failed", String.valueOf(e)));
        }
    }

    /**
     * 交付批准（受控交付链）：
     * 服务端对提交工件重跑 numeric + citation 两项检查，任一非 PASSED → 409 REFUSED；
     * 全部 PASSED → 记录批准（绑定工件规范化 digest，内容一改 digest 即变、旧批准失效）。
     */
    private static void handleApproval(HttpExchange ex, SnapshotHttpResolver resolver,
                                       List<SourceEntry> sources, String approver) throws IOException {
        if (approver == null || approver.isBlank()) {
            respond(ex, 400, errorNode("bad_request", "缺少 approver 参数（批准须具名）"));
            return;
        }
        AnnualReportArtifacts draft;
        try {
            draft = MAPPER.readValue(ex.getRequestBody(), AnnualReportArtifacts.class);
        } catch (Exception e) {
            respond(ex, 400, errorNode("bad_request", "工件反序列化失败: " + e.getMessage()));
            return;
        }
        CheckResult numeric;
        CheckResult citation;
        try {
            numeric = NUMERIC.check(draft, resolver);
            citation = CITATION.check(draft, resolver, sources);
        } catch (Exception e) {
            respond(ex, 500, errorNode("check_failed", String.valueOf(e)));
            return;
        }
        boolean allPassed = "PASSED".equals(String.valueOf(numeric.status()))
                && "PASSED".equals(String.valueOf(citation.status()));
        String digest = "sha256:" + sha256(MAPPER.writeValueAsBytes(draft));
        if (!allPassed) {
            ObjectNode refused = MAPPER.createObjectNode();
            refused.put("status", "REFUSED");
            refused.put("message", "错误草稿不能获得交付批准（服务端重跑检查未全部 PASSED）");
            refused.put("artifact_digest", digest);
            refused.set("numeric", MAPPER.valueToTree(numeric));
            refused.set("citation", MAPPER.valueToTree(citation));
            respond(ex, 409, refused);
            return;
        }
        ObjectNode approval = MAPPER.createObjectNode();
        approval.put("approval_id", "APR-" + String.format("%04d", APPROVAL_SEQ.incrementAndGet()));
        approval.put("task_id", draft.taskId());
        approval.put("approver", approver);
        approval.put("approved_at", Instant.now().toString());
        approval.put("artifact_digest", digest);
        ObjectNode checks = approval.putObject("checks");
        ObjectNode nc = checks.putObject("numeric");
        nc.put("checker_id", numeric.checkerId());
        nc.put("checker_version", numeric.checkerVersion());
        nc.put("status", String.valueOf(numeric.status()));
        ObjectNode cc = checks.putObject("citation");
        cc.put("checker_id", citation.checkerId());
        cc.put("checker_version", citation.checkerVersion());
        cc.put("status", String.valueOf(citation.status()));
        approval.put("status", "APPROVED");
        APPROVALS.put(draft.taskId(), approval);
        // 批准同步进报告库任务记录（若该任务已登记）：状态 APPROVED、digest 更新、工件以批准版本为准
        ObjectNode task = TASKS.get(draft.taskId());
        if (task != null) {
            task.put("status", "APPROVED");
            task.put("artifact_digest", digest);
            task.put("updated_at", Instant.now().toString());
            task.set("checks", checksNode(numeric, citation));
            task.set("approval", approval.deepCopy());
            TASK_ARTIFACTS.put(draft.taskId(), draft);
        }
        respond(ex, 200, approval);
    }

    private static ObjectNode emptyApproval(String taskId) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("task_id", taskId);
        n.put("status", "NONE");
        n.put("message", "该任务尚无交付批准记录");
        return n;
    }

    // ———————————————————— 报告库（任务历史）与 Agent Hub ————————————————————

    /**
     * 预置两条历史任务（报告库初始记录）：
     *  TASK-2025-AR-001 正例 —— 启动时真实重跑两项核验（均 PASSED），绑定预置批准 APR-0001；
     *  TASK-2025-AR-002 受控错误样例 —— 真实重跑数值核验（N7 拦截 FAILED），状态 REFUSED 未交付。
     */
    private static void preloadTasks(Path staticDir, SnapshotHttpResolver resolver,
                                     List<SourceEntry> sources) throws IOException {
        Path posF = staticDir.resolve("demo").resolve("positive-artifact.json");
        Path errF = staticDir.resolve("demo").resolve("error-artifact.json");
        if (!Files.isRegularFile(posF) || !Files.isRegularFile(errF)) {
            System.out.println("WARN: demo 工件缺失，报告库未预置历史任务");
            return;
        }
        ObjectNode posArt = (ObjectNode) MAPPER.readTree(posF.toFile());
        AnnualReportArtifacts pos = MAPPER.treeToValue(posArt, AnnualReportArtifacts.class);
        CheckResult posNum = NUMERIC.check(pos, resolver);
        CheckResult posCit = CITATION.check(pos, resolver, sources);
        String posDigest = "sha256:" + sha256(MAPPER.writeValueAsBytes(pos));

        ObjectNode t1 = MAPPER.createObjectNode();
        t1.put("task_id", "TASK-2025-AR-001");
        t1.put("company", pos.company());
        t1.put("report_period", pos.reportPeriod());
        t1.put("status", "APPROVED");
        t1.put("summary", "2025 年度经营简报：8 项指标 + 4 节正文，两项核验 PASSED，Alice 具名批准交付");
        t1.put("artifact_digest", posDigest);
        t1.put("created_at", "2026-03-28T09:30:00Z");
        t1.put("updated_at", "2026-03-28T09:41:00Z");
        t1.set("checks", checksNode(posNum, posCit));
        ObjectNode apr = MAPPER.createObjectNode();
        apr.put("approval_id", "APR-0001");
        apr.put("task_id", "TASK-2025-AR-001");
        apr.put("approver", "Alice");
        apr.put("approved_at", "2026-03-28T09:41:00Z");
        apr.put("artifact_digest", posDigest);
        apr.put("status", "APPROVED");
        apr.set("checks", checksNode(posNum, posCit));
        t1.set("approval", apr);
        TASKS.put("TASK-2025-AR-001", t1);
        TASK_ARTIFACTS.put("TASK-2025-AR-001", pos);
        APPROVALS.put("TASK-2025-AR-001", apr);
        APPROVAL_SEQ.set(1);

        ObjectNode errArt = (ObjectNode) MAPPER.readTree(errF.toFile());
        errArt.put("task_id", "TASK-2025-AR-002");
        AnnualReportArtifacts err = MAPPER.treeToValue(errArt, AnnualReportArtifacts.class);
        CheckResult errNum = NUMERIC.check(err, resolver);
        CheckResult errCit = CITATION.check(err, resolver, sources);
        ObjectNode t2 = MAPPER.createObjectNode();
        t2.put("task_id", "TASK-2025-AR-002");
        t2.put("company", err.company());
        t2.put("report_period", err.reportPeriod());
        t2.put("status", "REFUSED");
        t2.put("summary", "受控错误样例：拨备变动误写成「下降20.19%」，被 N7 变动口径规则拦截，未获交付");
        t2.put("artifact_digest", "sha256:" + sha256(MAPPER.writeValueAsBytes(err)));
        t2.put("created_at", "2026-03-28T10:05:00Z");
        t2.put("updated_at", "2026-03-28T10:07:00Z");
        t2.set("checks", checksNode(errNum, errCit));
        TASKS.put("TASK-2025-AR-002", t2);
        TASK_ARTIFACTS.put("TASK-2025-AR-002", err);
        System.out.println("报告库预置 2 条历史任务（AR-001 已批准 / AR-002 已拦截）");
    }

    /** 报告库登记：服务端指派任务编号、同步工件 task_id、计算规范化 digest（upsert 语义）。 */
    private static void handleTaskRegister(HttpExchange ex) throws IOException {
        JsonNode body;
        try {
            body = MAPPER.readTree(ex.getRequestBody());
        } catch (Exception e) {
            respond(ex, 400, errorNode("bad_request", "请求体解析失败: " + e.getMessage()));
            return;
        }
        if (!body.path("task").isObject() || !body.path("artifact").isObject()) {
            respond(ex, 400, errorNode("bad_request", "请求体须为 {task:{…}, artifact:{…}}"));
            return;
        }
        JsonNode metaIn = body.path("task");
        String id = metaIn.path("task_id").asText("");
        if (id.isBlank()) {
            id = String.format("TASK-2025-AR-%03d", TASK_SEQ.incrementAndGet());
        }
        ObjectNode artNode = (ObjectNode) body.path("artifact").deepCopy();
        artNode.put("task_id", id);
        AnnualReportArtifacts artifact;
        try {
            artifact = MAPPER.treeToValue(artNode, AnnualReportArtifacts.class);
        } catch (Exception e) {
            respond(ex, 400, errorNode("bad_request", "工件反序列化失败: " + e.getMessage()));
            return;
        }
        String digest = "sha256:" + sha256(MAPPER.writeValueAsBytes(artifact));
        ObjectNode task = TASKS.get(id);
        if (task == null) {
            task = MAPPER.createObjectNode();
            task.put("task_id", id);
            task.put("created_at", Instant.now().toString());
        }
        task.put("company", artifact.company());
        task.put("report_period", artifact.reportPeriod());
        task.put("status", metaIn.path("status").asText("GENERATED"));
        task.put("artifact_digest", digest);
        task.put("updated_at", Instant.now().toString());
        String summary = metaIn.path("summary").asText("");
        if (!summary.isBlank()) {
            task.put("summary", summary);
        }
        TASKS.put(id, task);
        TASK_ARTIFACTS.put(id, artifact);
        respond(ex, 200, task.deepCopy());
    }

    /** 两项核验结果的结构化摘要（供任务记录与批准记录内嵌）。 */
    static ObjectNode checksNode(CheckResult n, CheckResult c) {
        ObjectNode checks = MAPPER.createObjectNode();
        checks.set("numeric", checkSummary(n));
        checks.set("citation", checkSummary(c));
        return checks;
    }

    private static ObjectNode checkSummary(CheckResult r) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("checker_id", r.checkerId());
        node.put("checker_version", r.checkerVersion());
        node.put("status", String.valueOf(r.status()));
        node.set("violations", MAPPER.valueToTree(r).path("violations").deepCopy());
        return node;
    }

    /** Agent Hub 种子：9 个内置运行时 agent + 3 个市场目录可安装 agent。 */
    private static void seedAgents() {
        String boot = Instant.now().toString();
        putAgent(AGENT_RUNTIME, "editor-in-chief", "主编编排 Agent", "ORCHESTRATOR", "1.0.0",
                "受控对话流主编：受理任务、澄清确认需求、分派专业 agent、汇总交付",
                new String[]{"任务受理", "需求澄清", "多agent分派", "交付汇总"}, boot);
        putAgent(AGENT_RUNTIME, "material-agent", "资料管理 Agent", "ANALYST", "1.0.0",
                "信源快照装载与页读取（read_page / list_metrics_index），编制资料导入的唯一入口",
                new String[]{"快照装载", "页读取", "指标索引"}, boot);
        putAgent(AGENT_RUNTIME, "finance-analyst", "财务分析 Agent", "ANALYST", "1.0.0",
                "经营业绩与盈利结构分析：营收、净利润、净息差、成本收入比",
                new String[]{"经营业绩分析", "盈利结构分析"}, boot);
        putAgent(AGENT_RUNTIME, "risk-analyst", "风险与拨备 Agent", "ANALYST", "1.0.0",
                "贷款质量与风险抵补分析：不良余额/不良率、拨备覆盖率，负责变动口径澄清",
                new String[]{"贷款质量分析", "口径澄清"}, boot);
        putAgent(AGENT_RUNTIME, "capital-analyst", "资本管理 Agent", "ANALYST", "1.0.0",
                "资本充足分析：高级法/权重法 CET1 等资本口径",
                new String[]{"资本充足分析"}, boot);
        putAgent(AGENT_RUNTIME, "disclosure-officer", "披露合规 Agent", "ANALYST", "1.0.0",
                "披露规范审查：口径铁律、释义与脚注体例（证监会 2 号准则对齐）",
                new String[]{"披露口径审查", "释义脚注"}, boot);
        putAgent(AGENT_RUNTIME, "numeric-checker", "数值核验器", "CHECKER", NumericChecker.CHECKER_VERSION,
                "N1–N7 数值纪律：指标完整性、单位白名单、数字页内字面存在、派生复算、同比复算、变动口径",
                new String[]{"数值复算", "口径拦截"}, boot);
        putAgent(AGENT_RUNTIME, "citation-checker", "引用核验器", "CHECKER", CitationChecker.CHECKER_VERSION,
                "引用溯源：摘录指纹比对、页内字面存在、信源注册表校验",
                new String[]{"引用溯源", "防篡改"}, boot);
        putAgent(AGENT_RUNTIME, "approver", "交付批准官", "APPROVER", "1.0.0",
                "具名批准交付：批准记录与工件 digest 绑定，内容一改批准即失效",
                new String[]{"具名批准", "digest 绑定"}, boot);

        putAgent(AGENT_CATALOG, "peer-benchmark", "同业对标 Agent", "ANALYST", "0.9.0",
                "四大行关键指标横向对标，为报告补充同业排名论断",
                new String[]{"同业对标"}, null);
        putAgent(AGENT_CATALOG, "macro-review", "宏观审慎 Agent", "ANALYST", "0.9.0",
                "宏观环境与监管动态审阅，为风险章节补充环境论断",
                new String[]{"宏观审阅"}, null);
        putAgent(AGENT_CATALOG, "layout-reviewer", "排版审校 Agent", "ANALYST", "0.9.0",
                "年报版式审校：A4 版式、表格规范、脚注体例",
                new String[]{"版式审校"}, null);
    }

    private static void putAgent(Map<String, ObjectNode> pool, String id, String name, String kind,
                                 String version, String desc, String[] caps, String registeredAt) {
        ObjectNode a = MAPPER.createObjectNode();
        a.put("agent_id", id);
        a.put("name", name);
        a.put("kind", kind);
        a.put("version", version);
        a.put("description", desc);
        a.put("source", registeredAt == null ? "MARKET" : "BUILT_IN");
        a.put("status", registeredAt == null ? "AVAILABLE" : "RUNNING");
        ArrayNode arr = a.putArray("capabilities");
        for (String c : caps) {
            arr.add(c);
        }
        if (registeredAt == null) {
            a.putNull("registered_at");
        } else {
            a.put("registered_at", registeredAt);
        }
        a.putNull("last_loaded_task");
        a.putNull("last_loaded_at");
        pool.put(id, a);
    }

    /** 市场目录 → 运行时：安装注册。 */
    private static void handleAgentInstall(HttpExchange ex) throws IOException {
        String id;
        try {
            id = MAPPER.readTree(ex.getRequestBody()).path("agent_id").asText("");
        } catch (Exception e) {
            respond(ex, 400, errorNode("bad_request", "请求体解析失败"));
            return;
        }
        ObjectNode a = AGENT_CATALOG.remove(id);
        if (a == null) {
            if (AGENT_RUNTIME.containsKey(id)) {
                respond(ex, 409, errorNode("already_installed", id + " 已在运行时"));
            } else {
                respond(ex, 404, errorNode("unknown_agent", "市场目录无此 agent: " + id));
            }
            return;
        }
        a.put("status", "RUNNING");
        a.put("registered_at", Instant.now().toString());
        AGENT_RUNTIME.put(id, a);
        respond(ex, 200, a.deepCopy());
    }

    /** 运行时 agent 按任务动态按需加载：记录最近加载任务与时间。 */
    private static void handleAgentLoad(HttpExchange ex) throws IOException {
        JsonNode body;
        try {
            body = MAPPER.readTree(ex.getRequestBody());
        } catch (Exception e) {
            respond(ex, 400, errorNode("bad_request", "请求体解析失败"));
            return;
        }
        String id = body.path("agent_id").asText("");
        String taskId = body.path("task_id").asText("");
        ObjectNode a = AGENT_RUNTIME.get(id);
        if (a == null) {
            if (AGENT_CATALOG.containsKey(id)) {
                respond(ex, 409, errorNode("not_installed", id + " 尚未安装注册，请先在 Agent Hub 安装"));
            } else {
                respond(ex, 404, errorNode("unknown_agent", "无此 agent: " + id));
            }
            return;
        }
        a.put("last_loaded_task", taskId);
        a.put("last_loaded_at", Instant.now().toString());
        respond(ex, 200, a.deepCopy());
    }

    // ———————————————————— 帮手 ————————————————————

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            } else {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode errorNode(String code, String message) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("error", code);
        n.put("message", message);
        return n;
    }

    private static void respond(HttpExchange ex, int code, JsonNode body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        respondRaw(ex, code, MAPPER.writeValueAsBytes(body));
    }

    private static void respondRaw(HttpExchange ex, int code, byte[] bytes) throws IOException {
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
