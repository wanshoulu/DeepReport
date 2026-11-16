/*
 * CheckerHttpServer — W06 确定性核验服务的最小 HTTP 入口（纯 JDK，无框架依赖）
 *
 * 部署形态：独立容器，control-net 内部网络；供 orchestrator/Gateway 调用。
 * 端点：
 *   GET  /health                      → {"status":"UP"}
 *   POST /v1/checks/numeric           → body: AnnualReportArtifacts JSON → CheckResult
 *   POST /v1/checks/citation          → 同上
 *
 * 快照解析：SNAPSHOT_DIR 环境变量指向挂载的年报快照（manifest.json + pages/）。
 */

package com.agenthub.w06.checker.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.agenthub.w06.contract.AnnualReportArtifacts;
import com.agenthub.w06.contract.SourceEntry;
import com.agenthub.w06.contract.SourceAuthority;
import com.agenthub.w06.contract.CheckResult;
import com.agenthub.w06.checker.CitationChecker;
import com.agenthub.w06.checker.NumericChecker;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Executors;

public final class CheckerHttpServer {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("CHECKER_PORT", "18095"));
        String snapshotDir = System.getenv().getOrDefault("SNAPSHOT_DIR", "/snapshot");
        ObjectMapper mapper = new ObjectMapper();
        SnapshotHttpResolver resolver = new SnapshotHttpResolver(Path.of(snapshotDir));
        resolver.load();
        java.util.List<SourceEntry> sourceEntries = loadSourceEntries(Path.of(snapshotDir));

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        server.createContext("/health", ex -> respond(ex, 200, "{\"status\":\"UP\",\"snapshot_pages\":" + resolver.pageCount() + ",\"sources\":" + sourceEntries.size() + "}"));
        server.createContext("/v1/sources", ex -> {
            try {
                respond(ex, 200, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sourceEntries));
            } catch (Exception e) {
                respond(ex, 500, "{\"error\":\"serialize\"}");
            }
        });
        server.createContext("/v1/checks/numeric", ex -> handleCheck(ex, mapper, resolver, draft -> new NumericChecker().check(draft, resolver)));
        server.createContext("/v1/checks/citation", ex -> handleCheck(ex, mapper, resolver, draft -> new CitationChecker().check(draft, resolver, sourceEntries)));
        server.createContext("/", ex -> serveTestPage(ex));

        server.start();
        System.out.println("W06 checker listening on :" + port + ", snapshot pages=" + resolver.pageCount());
    }

    private static void serveTestPage(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        try {
            String path = ex.getRequestURI().getPath();
            String file = "/".equals(path) ? "/app/www/workbench.html" : "/app/www/testpage.html";
            byte[] html = Files.readAllBytes(Path.of(file));
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, html.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(html); }
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":\"testpage missing\"}");
        }
    }

    /** 从快照 manifest.json 的 sources[] 加载信源注册表。 */
    static java.util.List<SourceEntry> loadSourceEntries(Path snapshotDir) {
        java.util.List<SourceEntry> list = new java.util.ArrayList<>();
        try {
            var manifest = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(snapshotDir.resolve("manifest.json").toFile());
            var arr = manifest.get("sources");
            if (arr != null && arr.isArray()) {
                arr.forEach(node -> {
                    try {
                        list.add(new SourceEntry(
                                node.path("source_id").asText(),
                                node.path("title").asText(),
                                SourceAuthority.valueOf(node.path("authority").asText("SECONDARY_INSTITUTIONAL")),
                                node.path("origin_url").asText(""),
                                node.path("fetched_at").asText(""),
                                node.path("content_sha256").asText(""),
                                java.util.Arrays.stream(node.path("pages").asText("").split(","))
                                        .filter(t -> !t.isBlank())
                                        .map(Integer::parseInt).toList()));
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception e) {
            System.err.println("WARN: manifest sources 加载失败: " + e.getMessage());
        }
        return list;
    }

    @FunctionalInterface
    interface CheckFn {
        CheckResult run(AnnualReportArtifacts draft);
    }

    private static void handleCheck(HttpExchange ex, ObjectMapper mapper,
                                    SnapshotHttpResolver resolver, CheckFn fn) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        try {
            AnnualReportArtifacts draft = mapper.readValue(ex.getRequestBody(), AnnualReportArtifacts.class);
            CheckResult result = fn.run(draft);
            respond(ex, 200, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        } catch (Exception e) {
            respond(ex, 400, "{\"error\":\"bad request: " + e.getMessage() + "\"}");
        }
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 快照解析器：digest+page → 原文。digest 用整页文本的 sha256（与 MaterialAgent 约定一致）。 */
    static final class SnapshotHttpResolver implements NumericChecker.SnapshotTextResolver {
        private final Path snapshotDir;
        private final Map<String, String> byDigest = new HashMap<>();
        private final Map<Integer, String> byPage = new HashMap<>();

        SnapshotHttpResolver(Path snapshotDir) { this.snapshotDir = snapshotDir; }

        void load() throws Exception {
            Path pagesDir = snapshotDir.resolve("pages");
            if (!Files.isDirectory(pagesDir)) {
                System.err.println("WARN: snapshot pages dir missing: " + pagesDir + " — checker will resolve nothing");
                return;
            }
            try (var stream = Files.list(pagesDir)) {
                stream.filter(p -> p.getFileName().toString().matches("page-\\d+\\.txt"))
                        .forEach(p -> {
                            try {
                                String text = Files.readString(p, StandardCharsets.UTF_8);
                                int page = Integer.parseInt(p.getFileName().toString().replaceAll("\\D", ""));
                                byPage.put(page, text);
                                byDigest.put(sha256(text), text);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
        }

        public String textOf(String digest, int page) {
            String text = byDigest.get(digest);
            return text != null ? text : byPage.get(page);
        }

        int pageCount() { return byPage.size(); }

        static String sha256(String s) throws Exception {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
