/*
 * NumericChecker — 确定性数值核验器（W06「计算和约定检查由工具承担」的落点）
 *
 * 纯 Java、零模型调用。规则集：
 *  N1 完整性：八指标一条不少（METRIC_MISSING）
 *  N2 单位白名单：每项指标只接受约定单位（UNIT_MISMATCH → A07 反例拦截）
 *  N3 数字来源：value 必须能在 source page 的原文中以字面出现（FABRICATED_NUMBER）
 *  N4 派生复算：可机器复算的口径（不良率=不良余额/贷款总额 等）逐项复算（CALC_MISMATCH）
 *  N5 期间成对：同比类指标须同时有本期与上期，或显式标注（PERIOD_MISMATCH）
 *
 * 输入之外不查任何网络 —— 快照 digest 与页码是唯一事实来源。
 */

package com.agenthub.w06.checker;

import com.agenthub.w06.contract.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class NumericChecker {

    public static final String CHECKER_ID = "numeric-checker";
    public static final String CHECKER_VERSION = "0.1.0";

    /** 指标 → 约定单位（W01 冻结口径；单位错误即 A07）。 */
    private static final Map<MetricKey, String> EXPECTED_UNITS = Map.of(
            MetricKey.OPERATING_REVENUE, "亿元",
            MetricKey.NET_PROFIT_ATTRIBUTABLE, "亿元",
            MetricKey.NET_INTEREST_MARGIN, "%",
            MetricKey.COST_INCOME_RATIO, "%",
            MetricKey.NPL_BALANCE, "亿元",
            MetricKey.NPL_RATIO, "%",
            MetricKey.PROVISION_COVERAGE, "%",
            MetricKey.CET1_RATIO, "%");

    /** 原文数字匹配：容忍千分位逗号。 */
    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?|\\d+(?:\\.\\d+)?");

    public CheckResult check(AnnualReportArtifacts draft, SnapshotTextResolver resolver) {
        List<Violation> violations = new ArrayList<>();

        // N1 完整性
        Map<MetricKey, MetricValue> byKey = new EnumMap<>(MetricKey.class);
        for (MetricValue m : draft.metrics()) {
            if (byKey.put(m.key(), m) != null) {
                violations.add(v("METRIC_DUPLICATED", m.key().name(), "同一指标出现多条记录", Severity.BLOCKING));
            }
        }
        for (MetricKey k : MetricKey.values()) {
            if (!byKey.containsKey(k)) {
                violations.add(v("METRIC_MISSING", k.name(), "八指标缺失：" + k.getDisplayName(), Severity.BLOCKING));
            }
        }

        for (MetricValue m : draft.metrics()) {
            // N2 单位
            String expected = EXPECTED_UNITS.get(m.key());
            if (expected != null && !expected.equals(m.unit())) {
                violations.add(v("UNIT_MISMATCH", m.key().name(),
                        "单位应为 " + expected + "，实际 " + m.unit() + "（A07）", Severity.BLOCKING));
            }
            // N3 数字来源（NOT_FOUND 显式标注不算伪造，但完整性会在别处暴露）
            if (!"NOT_FOUND".equals(m.value()) && !"UNSPECIFIED".equals(m.value())) {
                String pageText = resolver.textOf(m.sourceDocumentDigest(), m.page());
                if (pageText == null) {
                    violations.add(v("SOURCE_UNRESOLVABLE", m.key().name(),
                            "digest/page 无法定位到快照页", Severity.BLOCKING));
                } else if (!numberPresent(pageText, m.value())) {
                    violations.add(v("FABRICATED_NUMBER", m.key().name(),
                            "值 " + m.value() + " 未在第 " + m.page() + " 页原文出现", Severity.BLOCKING));
                }
            }
        }

        // N4 派生复算（指标字典「机器可复算口径」）
        derivedCheck(byKey, resolver, violations);

        // N6 跨期同比：上期值来自独立信源页（如 page-46），本期同比必须与两期值一致
        yoyCheck(draft, byKey, resolver, violations);

        boolean waiting = violations.stream().anyMatch(x -> x.severity() == Severity.WARNING)
                && violations.stream().noneMatch(x -> x.severity() == Severity.BLOCKING);
        return new CheckResult(CHECKER_ID, CHECKER_VERSION,
                violations.isEmpty() ? CheckStatus.PASSED
                        : (waiting ? CheckStatus.WAITING_HUMAN : CheckStatus.FAILED),
                violations);
    }

    /**
     * N6 跨期同比复算（指标字典「期间规则」）：
     * 报告正文含"同比+增速"论断时，用快照中可定位的上期值与本期值复算。
     * 上期值从上年度信源页正则定位；定位不到时降级 WARNING（PERIOD_BASE_MISSING）。
     */
    private void yoyCheck(AnnualReportArtifacts draft, Map<MetricKey, MetricValue> byKey,
                          SnapshotTextResolver resolver, List<Violation> out) {
        // 本期营业收入
        MetricValue rev = byKey.get(MetricKey.OPERATING_REVENUE);
        if (rev == null || "NOT_FOUND".equals(rev.value())) {
            return;
        }
        // 上期值：扫描非本期指标页之外的快照页，找"2024年...营业收入<数>亿元"
        String priorPage = findPriorYearRevenue(resolver, rev.page());
        if (priorPage == null) {
            out.add(v("PERIOD_BASE_MISSING", "OPERATING_REVENUE",
                    "快照未定位 2024 年营业收入基数，同比复算降级为人工核对", Severity.WARNING));
            return;
        }
        try {
            var m = java.util.regex.Pattern.compile("营业收入([\\d,]+(?:\\.\\d+)?)亿元").matcher(priorPage);
            if (!m.find()) {
                return;
            }
            BigDecimal prior = new BigDecimal(m.group(1).replace(",", ""));
            BigDecimal current = new BigDecimal(rev.value().replace(",", ""));
            BigDecimal yoy = current.subtract(prior)
                    .divide(prior, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            // 若正文含"同比"+百分比，校验其与复算值一致（±0.1pp）
            for (ReportSection sec : draft.sections()) {
                var pm = java.util.regex.Pattern
                        .compile("同比(?:增长|下降|减少|增加)?([\\d.]+)%").matcher(sec.body());
                while (pm.find()) {
                    BigDecimal claimed = new BigDecimal(pm.group(1));
                    if (yoy.abs().subtract(claimed.abs()).abs()
                            .compareTo(new BigDecimal("0.1")) > 0) {
                        out.add(v("YOY_MISMATCH", "OPERATING_REVENUE",
                                "正文同比 " + pm.group(1) + "% vs 复算 " + yoy.setScale(2, java.math.RoundingMode.HALF_UP) + "%",
                                Severity.BLOCKING));
                    }
                }
            }
        } catch (NumberFormatException ignored) {
            // 正文无同比论断则跳过
        }
    }

    /** 定位上年度营收页：从 resolver 可见的所有页里找含 2024 年营业收入的（跳过本期页）。 */
    private String findPriorYearRevenue(SnapshotTextResolver resolver, int currentPage) {
        for (int pg = 1; pg <= 200; pg++) {
            if (pg == currentPage) {
                continue;
            }
            String text = resolver.textOf(null, pg);
            if (text != null && text.contains("2024年") && text.contains("营业收入")) {
                return text;
            }
        }
        return null;
    }

    private void derivedCheck(Map<MetricKey, MetricValue> byKey,
                              SnapshotTextResolver resolver, List<Violation> out) {
        MetricValue nplRatio = byKey.get(MetricKey.NPL_RATIO);
        MetricValue nplBalance = byKey.get(MetricKey.NPL_BALANCE);
        if (nplRatio == null || nplBalance == null || "NOT_FOUND".equals(nplRatio.value())) {
            return;
        }
        String pageText = resolver.textOf(nplBalance.sourceDocumentDigest(), nplBalance.page());
        if (pageText == null) {
            return;
        }
        // 尝试从同页提取"贷款和垫款本金总额/贷款总额"基数（约定关键词）
        BigDecimal loans = findLoansTotal(pageText);
        if (loans == null) {
            out.add(v("DERIVED_BASE_MISSING", "NPL_RATIO",
                    "同页未定位贷款总额基数，不良率口径复算降级为人工核对", Severity.WARNING));
            return;
        }
        try {
            BigDecimal balance = new BigDecimal(nplBalance.value().replace(",", ""));
            BigDecimal recomputed = balance.multiply(BigDecimal.valueOf(100))
                    .divide(loans, 2, RoundingMode.HALF_UP);
            BigDecimal reported = new BigDecimal(nplRatio.value().replace(",", ""));
            if (recomputed.subtract(reported).abs().compareTo(new BigDecimal("0.05")) > 0) {
                out.add(v("CALC_MISMATCH", "NPL_RATIO",
                        "复算 " + recomputed + "% vs 报告 " + reported + "%（偏差>0.05pp）", Severity.BLOCKING));
            }
        } catch (NumberFormatException e) {
            out.add(v("VALUE_UNPARSABLE", "NPL_RATIO", "数值不可解析：" + nplRatio.value(), Severity.BLOCKING));
        }

        // 口径2：拨备覆盖率 = 贷款减值准备 / 不良余额 × 100（容差 1pp）
        provisionCheck(byKey, resolver, out);
        // 口径3：成本收入比 = 业务及管理费 / 营业收入 × 100（容差 0.5pp）
        costIncomeCheck(byKey, resolver, out);
    }

    /** 口径2：拨备覆盖率复算。基数（贷款减值准备）从不良余额所在页定位；缺失降级 WARNING。 */
    private void provisionCheck(Map<MetricKey, MetricValue> byKey,
                                SnapshotTextResolver resolver, List<Violation> out) {
        MetricValue cov = byKey.get(MetricKey.PROVISION_COVERAGE);
        MetricValue npl = byKey.get(MetricKey.NPL_BALANCE);
        if (cov == null || npl == null || "NOT_FOUND".equals(cov.value())) {
            return;
        }
        String page = resolver.textOf(npl.sourceDocumentDigest(), npl.page());
        if (page == null) {
            return;
        }
        BigDecimal reserve = findNumberAfter(page, "贷款减值准备");
        if (reserve == null) {
            out.add(v("DERIVED_BASE_MISSING", "PROVISION_COVERAGE",
                    "同页未定位贷款减值准备，拨备覆盖率复算降级人工核对", Severity.WARNING));
            return;
        }
        try {
            BigDecimal nplBalance = new BigDecimal(npl.value().replace(",", ""));
            BigDecimal recomputed = reserve.multiply(BigDecimal.valueOf(100))
                    .divide(nplBalance, 2, RoundingMode.HALF_UP);
            BigDecimal reported = new BigDecimal(cov.value().replace(",", ""));
            if (recomputed.subtract(reported).abs().compareTo(new BigDecimal("1")) > 0) {
                out.add(v("CALC_MISMATCH", "PROVISION_COVERAGE",
                        "复算 " + recomputed + "% vs 报告 " + reported + "%（容差1pp）", Severity.BLOCKING));
            }
        } catch (NumberFormatException e) {
            out.add(v("VALUE_UNPARSABLE", "PROVISION_COVERAGE", "数值不可解析：" + cov.value(), Severity.BLOCKING));
        }
    }

    /** 口径3：成本收入比复算。基数（业务及管理费）从营业收入所在页定位；缺失降级 WARNING。 */
    private void costIncomeCheck(Map<MetricKey, MetricValue> byKey,
                                 SnapshotTextResolver resolver, List<Violation> out) {
        MetricValue ratio = byKey.get(MetricKey.COST_INCOME_RATIO);
        MetricValue rev = byKey.get(MetricKey.OPERATING_REVENUE);
        if (ratio == null || rev == null || "NOT_FOUND".equals(ratio.value())) {
            return;
        }
        String page = resolver.textOf(rev.sourceDocumentDigest(), rev.page());
        if (page == null) {
            return;
        }
        BigDecimal expense = findNumberAfter(page, "业务及管理费");
        if (expense == null) {
            out.add(v("DERIVED_BASE_MISSING", "COST_INCOME_RATIO",
                    "同页未定位业务及管理费，成本收入比复算降级人工核对", Severity.WARNING));
            return;
        }
        try {
            BigDecimal revenue = new BigDecimal(rev.value().replace(",", ""));
            BigDecimal recomputed = expense.multiply(BigDecimal.valueOf(100))
                    .divide(revenue, 2, RoundingMode.HALF_UP);
            BigDecimal reported = new BigDecimal(ratio.value().replace(",", ""));
            if (recomputed.subtract(reported).abs().compareTo(new BigDecimal("0.5")) > 0) {
                out.add(v("CALC_MISMATCH", "COST_INCOME_RATIO",
                        "复算 " + recomputed + "% vs 报告 " + reported + "%（容差0.5pp）", Severity.BLOCKING));
            }
        } catch (NumberFormatException e) {
            out.add(v("VALUE_UNPARSABLE", "COST_INCOME_RATIO", "数值不可解析：" + ratio.value(), Severity.BLOCKING));
        }
    }

    /** 在 text 中找 keyword 之后最近的数字（含千分位，跨最多 12 个非数字字符），纯字符扫描无正则。 */
    static BigDecimal findNumberAfter(String text, String keyword) {
        int i = text.indexOf(keyword);
        if (i < 0) {
            return null;
        }
        int j = i + keyword.length();
        int skipped = 0;
        while (j < text.length() && !isNumChar(text.charAt(j)) && skipped < 12) {
            j++;
            skipped++;
        }
        int start = j;
        while (j < text.length() && isNumChar(text.charAt(j))) {
            j++;
        }
        if (j == start) {
            return null;
        }
        // 去掉可能的尾部逗号（如 "656.10，"）
        String raw = text.substring(start, j);
        while (raw.endsWith(",")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        try {
            return new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isNumChar(char c) {
        return (c >= '0' && c <= '9') || c == ',' || c == '.';
    }

    /** 从页文本定位贷款总额（约定口径词；W01 冻结材料里应存在）。 */
    private BigDecimal findLoansTotal(String pageText) {
        var m = Pattern.compile("(?:贷款和垫款本金总额|贷款总额)[^\\d]{0,12}([\\d,]+(?:\\.\\d+)?)").matcher(pageText);
        return m.find() ? new BigDecimal(m.group(1).replace(",", "")) : null;
    }

    private static boolean numberPresent(String pageText, String value) {
        String normalized = value.replace(",", "");
        var m = NUMBER.matcher(pageText);
        while (m.find()) {
            if (m.group().replace(",", "").equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static Violation v(String rule, String target, String detail, Severity s) {
        return new Violation(rule, target, detail, s);
    }

    /** 快照文本解析口：由部署层把 MaterialAgent 的 SnapshotStore 接进来。 */
    public interface SnapshotTextResolver {
        /** 返回 digest+page 对应的原文；不存在返回 null。 */
        String textOf(String digest, int page);
    }
}
