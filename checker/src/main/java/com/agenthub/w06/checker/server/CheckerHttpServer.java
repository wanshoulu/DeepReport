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
 *  GET  /                                 workbench.html（工作台）
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
        respond(ex, 200, approval);
    }

    private static ObjectNode emptyApproval(String taskId) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("task_id", taskId);
        n.put("status", "NONE");
        n.put("message", "该任务尚无交付批准记录");
        return n;
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
