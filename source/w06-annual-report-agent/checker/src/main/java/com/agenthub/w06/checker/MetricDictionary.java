/*
 * MetricDictionary —— 指标字典（配置驱动的通用指标抽象）。
 *
 * 替代 MetricKey 枚举的硬编码：指标集/约定单位/中文名/别名/派生规则全部来自
 * report-profiles/metrics/{metric_profile}.json，按报告类型经 ReportProfile.metricProfile() 加载。
 * banking_8 / client_analytics / equity_research 三份字典同构，新增行业 = 加一份 JSON。
 *
 * 兼容性：artifact 工件里的 metrics 仍以字符串 key 存储（如 "OPERATING_REVENUE"），
 * 字典只是核验与抽取的运行时依据，工件契约不变。
 */
package com.agenthub.w06.checker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class MetricDictionary {

    /** 单条指标定义：key、中文名、约定单位、别名（名称模式匹配用）、派生规则（可空）。 */
    public record MetricDef(
            String key,
            String nameZh,
            String expectedUnit,
            List<String> aliases,
            JsonNode derived,
            String definition) {

        /** 所有可用于文本匹配的名称（中文名 + 别名）。 */
        public List<String> matchNames() {
            List<String> names = new ArrayList<>();
            names.add(nameZh);
            names.addAll(aliases);
            return names;
        }
    }

    private final String metricProfile;
    private final String reportType;
    private final Map<String, MetricDef> byKey = new LinkedHashMap<>();
    private final Map<String, MetricDef> byName = new HashMap<>(); // 中文名/别名 → 定义

    private MetricDictionary(JsonNode root) {
        this.metricProfile = root.path("metric_profile") == null ? ""
                : (root.has("metric_profile") ? root.path("version").asText("") : "");
        this.reportType = root.path("report_type").asText("");
        JsonNode metrics = root.path("metrics");
        metrics.fields().forEachRemaining(e -> {
            String key = e.getKey();
            JsonNode v = e.getValue();
            String nameZh = v.path("name_zh").asText(key);
            String unit = v.path("expected_unit").asText("");
            List<String> aliases = new ArrayList<>();
            JsonNode al = v.path("aliases");
            if (al.isArray()) for (JsonNode a : al) aliases.add(a.asText());
            MetricDef def = new MetricDef(key, nameZh, unit, List.copyOf(aliases), v.path("derived"),
                    v.path("definition").asText(""));
            byKey.put(key, def);
            byName.put(nameZh, def);
            aliases.forEach(a -> byName.put(a, def));
        });
    }

    /** 从文件路径加载。 */
    public static MetricDictionary load(Path path) throws IOException {
        return new MetricDictionary(new ObjectMapper().readTree(Files.readAllBytes(path)));
    }

    /** 从 classpath 加载（打包部署用）。 */
    public static MetricDictionary loadResource(String resource) throws IOException {
        try (InputStream in = MetricDictionary.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("metric dictionary resource not found: " + resource);
            return new MetricDictionary(new ObjectMapper().readTree(in));
        }
    }

    /**
     * 按报告类型加载：configRoot/report-profiles/metrics/{metricProfile}.json。
     * metricProfile 为空或文件缺失时回退 banking_8（向后兼容：年报主链路行为不变）。
     */
    public static MetricDictionary forProfile(ReportProfile profile, Path configRoot) {
        String mp = profile.metricProfile();
        List<String> candidates = new ArrayList<>();
        if (mp != null && !mp.isBlank()) candidates.add(mp);
        candidates.add("banking_8");
        for (String name : candidates) {
            Path p = configRoot.resolve("report-profiles/metrics/" + name + ".json");
            if (Files.isRegularFile(p)) {
                try {
                    return load(p);
                } catch (IOException e) {
                    System.err.println("MetricDictionary load failed for " + name + ": " + e.getMessage());
                }
            }
        }
        throw new IllegalStateException("无可用的指标字典（尝试: " + candidates + "）");
    }

    // ---- 查询 ----
    public MetricDef byKey(String key) { return byKey.get(key); }
    public MetricDef byName(String name) { return byName.get(name); }
    public Collection<MetricDef> all() { return byKey.values(); }
    public int size() { return byKey.size(); }
    public Set<String> keys() { return byKey.keySet(); }
    public String reportType() { return reportType; }
}
