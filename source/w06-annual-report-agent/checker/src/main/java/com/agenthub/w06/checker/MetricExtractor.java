/*
 * W06 指标抽取工位 —— 从 live 快照页文本按「名称模式 → 值 → 单位 → page/digest 证据」抽取
 * 年报八指标。诚信约束：
 *  - 只取与期望单位一致的原文口径（亿元/％），不做换算（换算值过不了 N3 字面匹配，也不该骗过它）；
 *  - 材料中定位不到的指标显式标 NOT_FOUND（N1 完整性满足、N3 豁免、N4 跳过——fail-closed 语义保留）；
 *  - 每个值携带 page + evidence digest，来源可打开。
 */
package com.agenthub.w06.checker;

import com.agenthub.w06.contract.MetricKey;
import com.agenthub.w06.contract.MetricValue;
import com.fasterxml.jackson.databind.JsonNode;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MetricExtractor {

    private MetricExtractor() { }

    /** 指标定位规则：名称模式（取首个命中行）→ 行内/行后窗口取值。 */
    private record Rule(String key, Pattern name, String unit, boolean wantPct) { }

    private static final List<Rule> RULES = List.of(
            // 管理层讨论正文（亿元口径）：如 P21 "净利润 3,707.66 亿元、营业收入 8,382.70 亿元"
            new Rule("OPERATING_REVENUE",
                    Pattern.compile("营业收入\\s*([\\d,]+\\.\\d{2})\\s*亿元"), "亿元", false),
            new Rule("NET_PROFIT_ATTRIBUTABLE",
                    Pattern.compile("净利润\\s*([\\d,]+\\.\\d{2})\\s*亿元"), "亿元", false),
            new Rule("NPL_RATIO",
                    Pattern.compile("不良贷款率\\s*([\\d.]+)\\s*%"), "%", true),
            new Rule("PROVISION_COVERAGE",
                    Pattern.compile("拨备覆盖率\\s*([\\d.]+)\\s*%"), "%", true),
            new Rule("CET1_RATIO",
                    Pattern.compile("核心一级资本充足率\\s*([\\d.]+)\\s*%"), "%", true),
            new Rule("COST_INCOME_RATIO",
                    Pattern.compile("成本收入比\\s*([\\d.]+)\\s*%"), "%", true),
            new Rule("NET_INTEREST_MARGIN",
                    Pattern.compile("净利息收益率\\s*([\\d.]+)\\s*%"), "%", true),
            // 摘要表"名称（换行）数值"布局：名称行后 120 字符窗口取第一组两位小数
            new Rule("OPERATING_REVENUE", null, "亿元", false),
            new Rule("NET_PROFIT_ATTRIBUTABLE", null, "亿元", false),
            new Rule("NPL_RATIO", null, "%", true),
            new Rule("PROVISION_COVERAGE", null, "%", true),
            new Rule("CET1_RATIO", null, "%", true),
            new Rule("COST_INCOME_RATIO", null, "%", true),
            new Rule("NET_INTEREST_MARGIN", null, "%", true));

    /** 摘要表布局的名称锚点（P16 样式：名称可能带注号换行，数值列紧随其后）。 */
    private static final Map<String, String> TABLE_ANCHORS = Map.of(
            "OPERATING_REVENUE", "营业收入",
            "NET_PROFIT_ATTRIBUTABLE", "归属于母公司股东的净利润",
            "NET_INTEREST_MARGIN", "净利息收益率",
            "COST_INCOME_RATIO", "成本收入比",
            "NPL_RATIO", "不良贷款率",
            "PROVISION_COVERAGE", "拨备覆盖率",
            "CET1_RATIO", "核心一级资本充足率");

    private static final Pattern NUM2 = Pattern.compile("([\\d,]+\\.\\d{2})");

    /**
     * 从快照页集合抽取八指标。pages: [{digest, page, text}]（live 快照解析视图）。
     * 抽不到的指标 value=NOT_FOUND、page=0（N1 完整性满足，N3 豁免，checker 语义诚实）。
     */
    public static List<MetricValue> extract(JsonNode pages, String period) {
        return extract(pages, period, null);
    }

    /**
     * 字典驱动抽取（通用内核）：指标集/名称模式/约定单位来自 MetricDictionary（注册制领域资产）。
     * 匹配策略：对字典每个指标，按 matchNames()（中文名+别名）在页文本定位 → 窗口取首个两位小数 →
     * 单位取字典 expected_unit。抽不到 → NOT_FOUND（诚实兜底，N1 满足/N3 豁免/N4 跳过）。
     * 归母口径守护泛化：字典 definition 含"归属于母公司/归母"字样的指标，正文须含同字样才采信。
     */
    public static List<MetricValue> extract(JsonNode pages, String period, MetricDictionary dictionary) {
        if (dictionary != null) {
            return extractWithDictionary(pages, period, dictionary);
        }
        return extractLegacyBanking(pages, period);
    }

    /** 字典驱动路径（通用）。 */
    private static List<MetricValue> extractWithDictionary(JsonNode pages, String period, MetricDictionary dict) {
        List<JsonNode> pageList = new ArrayList<>();
        if (pages != null) pages.forEach(pageList::add);
        Map<String, MetricValue> got = new LinkedHashMap<>();
        for (MetricDictionary.MetricDef def : dict.all()) {
            MetricValue mv = null;
            for (JsonNode p : pageList) {
                String text = p.path("text").asText("");
                if (text.isBlank()) continue;
                String digest = p.path("digest").asText("");
                int page = p.path("page").asInt(0);
                for (String name : def.matchNames()) {
                    int i = text.indexOf(name);
                    if (i < 0) continue;
                    // 子串守护：名称前紧连"比/占/率/入/对"（如"比营业收入""占营业收入"）是比率行而非指标行
                    if (i > 0) {
                        char prev = text.charAt(i - 1);
                        if (prev == '比' || prev == '占' || prev == '率' || prev == '入' || prev == '对') {
                            int j = text.indexOf(name, i + name.length());
                            if (j >= 0) i = j;
                        }
                    }
                    String tail = text.substring(i + name.length(), Math.min(text.length(), i + name.length() + 30));
                    if (tail.contains("除以")) {
                        int j = text.indexOf(name, i + name.length());
                        if (j >= 0) i = j;
                    }
                    String window = text.substring(i + name.length(), Math.min(text.length(), i + name.length() + 120));
                    Matcher n = NUM2.matcher(window);
                    if (n.find()) {
                        String unit = def.expectedUnit() == null || def.expectedUnit().isBlank() ? "%" : def.expectedUnit();
                        mv = mv(def.key(), n.group(1), unit, digest, page, period);
                        break;
                    }
                }
                if (mv != null) break;
            }
            if (mv != null && mv.page() > 0 && def.definition() != null
                    && (def.definition().contains("归属于母公司") || def.definition().contains("归母"))) {
                String text = textOfPage(pageList, mv.page());
                if (text == null || !(text.contains("归属于母公司") || text.contains("归属于本行"))) {
                    mv = null;
                }
            }
            if (mv == null) {
                String unit = def.expectedUnit() == null || def.expectedUnit().isBlank() ? "%" : def.expectedUnit();
                mv = new MetricValue(def.key(), "NOT_FOUND", unit, period, "", 0, "");
            }
            got.put(def.key(), mv);
        }
        return new ArrayList<>(got.values());
    }

    /** 旧银行口径路径（硬编码模式，向后兼容 annual_report + banking_8 行为不变）。 */
    private static List<MetricValue> extractLegacyBanking(JsonNode pages, String period) {
        // page 文本按页码缓存
        List<JsonNode> pageList = new ArrayList<>();
        if (pages != null) {
            pages.forEach(pageList::add);
        }
        Map<String, MetricValue> got = new LinkedHashMap<>();

        for (Rule rule : RULES) {
            if (got.containsKey(rule.key())) continue;
            for (JsonNode p : pageList) {
                String text = p.path("text").asText("");
                if (text.isBlank()) continue;
                String digest = p.path("digest").asText("");
                int page = p.path("page").asInt(0);
                if (rule.name() != null) {
                    Matcher m = rule.name().matcher(text);
                    if (m.find()) {
                        got.put(rule.key(), mv(rule.key(), m.group(1), rule.unit(), digest, page, period));
                        break;
                    }
                } else {
                    // 摘要表锚点：名称首次出现处（跳过注释引用），窗口 120 字符取第一组两位小数
                    String anchor = TABLE_ANCHORS.get(rule.key());
                    int i = text.indexOf(anchor);
                    if (i >= 0) {
                        // 若锚点紧邻注释（"（7）"），跳过首现取二现
                        String tail = text.substring(i + anchor.length(), Math.min(text.length(), i + anchor.length() + 30));
                        if (tail.contains("除以") || tail.contains("贷款减值")) {
                            int j = text.indexOf(anchor, i + anchor.length());
                            if (j >= 0) i = j;
                        }
                        String window = text.substring(i + anchor.length(),
                                Math.min(text.length(), i + anchor.length() + 120));
                        Matcher n = NUM2.matcher(window);
                        if (n.find()) {
                            got.put(rule.key(), mv(rule.key(), n.group(1), rule.unit(), digest, page, period));
                            break;
                        }
                    }
                }
            }
        }

        // 归母净利润专项：正文"净利润 X 亿元"可能是含少数股东的口径——若材料同页
        // 出现"归属于母公司"字样才采信，否则 NOT_FOUND（不冒充归母口径）。
        MetricValue profit = got.get("NET_PROFIT_ATTRIBUTABLE");
        if (profit != null && profit.page() > 0) {
            String text = textOfPage(pageList, profit.page());
            if (text == null || !text.contains("归属于母公司")) {
                if (text == null || !text.contains("归属于本行")) {
                    got.remove("NET_PROFIT_ATTRIBUTABLE");
                }
            }
        }

        List<MetricValue> out = new ArrayList<>();
        for (MetricKey k : MetricKey.values()) {
            MetricValue v = got.get(k.name());
            if (v == null) {
                out.add(new MetricValue(k.name(), "NOT_FOUND", expectedUnit(k.name()), period, "", 0, ""));
            } else {
                out.add(v);
            }
        }
        return out;
    }

    private static MetricValue mv(String key, String value, String unit, String digest, int page, String period) {
        String excerpt = value; // 证据片段即数值本身（digest+page 已可打开溯源）
        String excerptDigest = sha256(excerpt);
        return new MetricValue(key, value, unit, period, digest, page, excerptDigest);
    }

    private static String expectedUnit(String k) {
        return switch (k) {
            case "OPERATING_REVENUE", "NET_PROFIT_ATTRIBUTABLE", "NPL_BALANCE" -> "亿元";
            default -> "%";
        };
    }

    private static String textOfPage(List<JsonNode> pages, int page) {
        for (JsonNode p : pages) {
            if (p.path("page").asInt(0) == page) return p.path("text").asText("");
        }
        return null;
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "sha256:unavailable";
        }
    }
}
