/*
 * ReportProfile —— 报告类型画像（泛化的核心抽象）。
 *
 * 设计依据（多源调研结论）：业界已放弃「每种报告造一个专用 Agent」，转向
 * 「一个通用内核 + 一组可插拔领域资产」。四类会随领域变化的东西——任务模板、
 * 领域技能、指标字典、检查规则——全部外置到配置，运行时按 reportType 加载。
 *
 * 本类把 source/config/report-profiles/*.json 装载为运行时对象，作为：
 *   - SemanticSupportAssessor.Lexicon（主体词/期间词/单位词，消除银行名词硬编码）
 *   - 未来 NumericChecker 的 required_metrics / derived_rules / delta_unit_rules 来源
 *
 * 向后兼容：未指定 reportType 时默认 annual_report，行为与旧版一致。
 */
package com.agenthub.w06.checker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agenthub.w06.checker.SemanticSupportAssessor.Lexicon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class ReportProfile implements Lexicon {

    private final String reportType;
    private final String displayName;
    private final String description;
    private final String subjectKind;
    private final String subjectLabel;
    private final String defaultSubject;
    private final String metricProfile;
    private final List<String> requiredMetrics;
    private final String skeletonRef;
    private final List<String> skillSet;
    private final String evidenceLocator;
    private final String citationProtocol;
    private final String writingStyle;
    private final int minCharsPerSection;
    private final String goal;

    // Lexicon 三词表
    private final List<String> subjectWords;
    private final List<String> periodWords;
    private final List<String> unitWords;

    private final JsonNode raw;

    private ReportProfile(JsonNode root) {
        this.raw = root;
        this.reportType = root.path("report_type").asText("annual_report");
        this.displayName = root.path("display_name").asText(reportType);
        this.description = root.path("description").asText("");
        this.subjectKind = root.path("subject_kind").asText("company");
        this.subjectLabel = root.path("subject_label").asText("主体");
        this.defaultSubject = root.path("default_subject").asText("示例主体");
        this.metricProfile = root.path("metric_profile").asText("");
        this.requiredMetrics = stringList(root.path("required_metrics"));
        this.skeletonRef = root.path("skeleton_ref").asText("");
        this.skillSet = stringList(root.path("skill_set"));
        this.evidenceLocator = root.path("evidence_locator").asText("page");

        JsonNode tb = root.path("task_book");
        this.goal = tb.path("goal").asText("");
        this.writingStyle = tb.path("style").asText("");
        this.citationProtocol = tb.path("citation_protocol").asText("");
        this.minCharsPerSection = tb.path("min_chars_per_section").asInt(1000);

        JsonNode lx = root.path("support_lexicon");
        this.subjectWords = stringList(lx.path("subject_words"));
        this.periodWords = stringList(lx.path("period_words"));
        this.unitWords = stringList(lx.path("unit_words"));
    }

    private static List<String> stringList(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isArray()) for (JsonNode e : n) out.add(e.asText());
        return List.copyOf(out);
    }

    /** 从文件路径加载。 */
    public static ReportProfile load(Path path) throws IOException {
        return new ReportProfile(new ObjectMapper().readTree(Files.readAllBytes(path)));
    }

    /** 从 classpath 资源加载（打包部署用）。 */
    public static ReportProfile loadResource(String resource) throws IOException {
        try (InputStream in = ReportProfile.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("profile resource not found: " + resource);
            return new ReportProfile(new ObjectMapper().readTree(in));
        }
    }

    /** 按报告类型从 index.json 注册表加载；找不到则回退 annual_report。 */
    public static ReportProfile byType(String reportType, Path configRoot) throws IOException {
        ObjectMapper om = new ObjectMapper();
        JsonNode index = om.readTree(Files.readAllBytes(configRoot.resolve("report-profiles/index.json")));
        JsonNode profiles = index.path("profiles");
        String path = profiles.has(reportType) ? profiles.path(reportType).asText()
                : profiles.path(index.path("default_report_type").asText()).asText();
        return load(configRoot.resolve(path));
    }

    // ---- getters ----
    public String reportType() { return reportType; }
    public String displayName() { return displayName; }
    public String description() { return description; }
    public String subjectKind() { return subjectKind; }
    public String subjectLabel() { return subjectLabel; }
    public String defaultSubject() { return defaultSubject; }
    public String metricProfile() { return metricProfile; }
    public List<String> requiredMetrics() { return requiredMetrics; }
    public String skeletonRef() { return skeletonRef; }
    public List<String> skillSet() { return skillSet; }
    public String evidenceLocator() { return evidenceLocator; }
    public String citationProtocol() { return citationProtocol; }
    public String writingStyle() { return writingStyle; }
    public int minCharsPerSection() { return minCharsPerSection; }
    public String goal() { return goal; }
    public JsonNode raw() { return raw; }

    // ---- Lexicon 实现 ----
    @Override public Collection<String> subjectWords() { return subjectWords; }
    @Override public Collection<String> periodWords() { return periodWords; }
    @Override public Collection<String> unitWords() { return unitWords; }

    @Override public String toString() {
        return "ReportProfile{" + reportType + ", metrics=" + requiredMetrics.size()
                + ", subjects=" + subjectWords.size() + ", locator=" + evidenceLocator + '}';
    }
}
