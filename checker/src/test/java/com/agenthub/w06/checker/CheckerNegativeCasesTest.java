/*
 * CheckerNegativeCasesTest — 负面用例回归（真实年报快照数据）。
 *
 * fixtures 与 deploy/snapshot/pages/*.txt 逐字一致（P18/P30/P37/P46），
 * 摘录 digest 与 deploy 工件的 sha256 一致 —— 测试数据即生产数据。
 */

package com.agenthub.w06.checker;

import com.agenthub.w06.contract.AnnualReportArtifacts;
import com.agenthub.w06.contract.Citation;
import com.agenthub.w06.contract.CheckResult;
import com.agenthub.w06.contract.CheckStatus;
import com.agenthub.w06.contract.DeltaUnit;
import com.agenthub.w06.contract.MetricKey;
import com.agenthub.w06.contract.MetricValue;
import com.agenthub.w06.contract.ReportSection;
import com.agenthub.w06.contract.Severity;
import com.agenthub.w06.contract.SourceAuthority;
import com.agenthub.w06.contract.SourceEntry;
import com.agenthub.w06.contract.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckerNegativeCasesTest {

    // ———— 快照页文本（真实年报节选，与 deploy/snapshot/pages 逐字一致）————

    static final String P18 = """
            三、总体经营情况分析（节选）
            报告期内，本集团实现营业收入3,375.32亿元，同比增长0.01%；归属于本行股东的净利润1,501.81亿元，同比增长1.21%。
            净利息收入2,155.93亿元，净息差1.87%，同比下降11个基点。
            非利息净收入1,219.39亿元。业务及管理费1,079.43亿元，成本收入比31.98%，同比上升0.09个百分点。
            报告期末，本集团资产总额130,705.23亿元，贷款和垫款本金总额72,580.58亿元，客户存款98,361.30亿元。""";

    static final String P30 = """
            四、贷款质量分析（节选）
            报告期末，本集团贷款和垫款本金总额72,580.58亿元，不良贷款余额682.06亿元，较上年末增加25.96亿元。
            不良贷款率0.94%，较上年末下降0.01个百分点。不良生成率1.03%，同比下降0.02个百分点。
            贷款减值准备2,672.24亿元，拨备覆盖率391.79%，较上年末下降20.19个百分点。
            贷款拨备率3.68%，信用成本0.60%。""";

    static final String P37 = """
            五、资本充足情况分析（节选）
            报告期末，本集团高级法下核心一级资本充足率14.16%，一级资本充足率16.51%，资本充足率18.24%，
            较上年末分别下降0.70、0.97、0.81个百分点。
            权重法下核心一级资本充足率11.92%，一级资本充足率13.90%，资本充足率15.00%。""";

    static final String P46 = """
            上年同期对比（2024年度，节选）
            2024年，本集团实现营业收入3,374.88亿元，归属于本行股东的净利润1,483.91亿元，净息差1.98%，成本收入比31.89%。
            2024年末，本集团不良贷款余额656.10亿元，不良贷款率0.95%，拨备覆盖率411.98%，高级法核心一级资本充足率14.86%。""";

    /** 页 52 未在任何信源注册（用于 SOURCE_UNREGISTERED 反例）。 */
    static final String P52 = "非注册页样本：本集团实现营业收入3,375.32亿元，同比增长0.01%。";

    static final NumericChecker.SnapshotTextResolver RESOLVER = (digest, page) -> switch (page) {
        case 18 -> P18;
        case 30 -> P30;
        case 37 -> P37;
        case 46 -> P46;
        case 52 -> P52;
        default -> null;
    };

    // ———— 信源注册表 / digest（与 deploy/snapshot/manifest.json 一致）————

    static final String PRIMARY = "696460edb9ddc740f83c8baf8a26d69ebf050f252f65442e871c0dbe818dd39f";

    static final List<SourceEntry> SOURCES = List.of(
            new SourceEntry("cmb-ar-2025-primary", "招商银行股份有限公司2025年度报告（本期节选）",
                    SourceAuthority.PRIMARY_OFFICIAL, "snapshot:local/cmb-ar-2025-primary",
                    "2026-11-16", PRIMARY, List.of(18, 30, 37)),
            new SourceEntry("cmb-ar-2024-comparison", "招商银行股份有限公司2024年度报告（对比基数节选）",
                    SourceAuthority.PRIMARY_OFFICIAL, "snapshot:local/cmb-ar-2024-comparison",
                    "2026-11-16",
                    "69c6096504be57484a32ea1afb02499891286066ea1179dcb30f54212bec3366",
                    List.of(46)));

    // ———— 8 条证据摘录及其真实 sha256 ————

    static final String X1 = "营业收入3,375.32亿元，同比增长0.01%";
    static final String X2 = "净利润1,501.81亿元，同比增长1.21%";
    static final String X3 = "净息差1.87%，同比下降11个基点";
    static final String X4 = "成本收入比31.98%，同比上升0.09个百分点";
    static final String X5 = "不良贷款余额682.06亿元，较上年末增加25.96亿元";
    static final String X6 = "不良贷款率0.94%，较上年末下降0.01个百分点";
    static final String X7 = "拨备覆盖率391.79%，较上年末下降20.19个百分点";
    static final String X8 = "高级法下核心一级资本充足率14.16%";

    static final String D1 = "6677359cdfa3f17bd61ef66fbd4117e4377e167e1ada359c83ae6412e22b66f9";
    static final String D2 = "5e7f97f3b7d2cac8e3ccd6c7c9c317c86b6f318380a3b69e19ca99a2a4e049d0";
    static final String D3 = "ae67d0eb8ba4abf877c8c8dfc46737c23cf5c793c1a34f69b1cf6cf322dad5d5";
    static final String D4 = "dfcc964586fcf516a8f5c3033f62ffd5c950c0bf0fce5f333c3019f8d4d5a135";
    static final String D5 = "35d7410106a56e7aba15de540e60b6441b8b1d31aaa94382b39878d28b2b4ef0";
    static final String D6 = "c65808de97af8c31f6fe09c378a6f78a1e67d06b367511e16f614bdef1e7bb70";
    static final String D7 = "a94b079ae0ea33c81367e2b731b344fa681d66e17624c7ab1c37e0b86e5345f8";
    static final String D8 = "07a9573653494b41c483eec3ee8ed25ad738c6adf616637e86e36938cd11d1b6";

    // ———— fixtures ————

    private static MetricValue mv(MetricKey key, String value, String unit, int page,
                                 String excerptDigest, String yoyDelta, DeltaUnit deltaUnit) {
        return new MetricValue(key, value, unit, "2025年度", PRIMARY, page,
                excerptDigest, yoyDelta, deltaUnit);
    }

    private static List<MetricValue> validMetrics() {
        return List.of(
                mv(MetricKey.OPERATING_REVENUE, "3,375.32", "亿元", 18, D1, "+0.01", DeltaUnit.PERCENT),
                mv(MetricKey.NET_PROFIT_ATTRIBUTABLE, "1,501.81", "亿元", 18, D2, "+1.21", DeltaUnit.PERCENT),
                mv(MetricKey.NET_INTEREST_MARGIN, "1.87", "%", 18, D3, "-11", DeltaUnit.BASIS_POINT),
                mv(MetricKey.COST_INCOME_RATIO, "31.98", "%", 18, D4, "+0.09", DeltaUnit.PERCENTAGE_POINT),
                mv(MetricKey.NPL_BALANCE, "682.06", "亿元", 30, D5, "+25.96", DeltaUnit.YUAN),
                mv(MetricKey.NPL_RATIO, "0.94", "%", 30, D6, "-0.01", DeltaUnit.PERCENTAGE_POINT),
                mv(MetricKey.PROVISION_COVERAGE, "391.79", "%", 30, D7, "-20.19", DeltaUnit.PERCENTAGE_POINT),
                mv(MetricKey.CET1_RATIO, "14.16", "%", 37, D8, "-0.70", DeltaUnit.PERCENTAGE_POINT));
    }

    private static List<ReportSection> validSections() {
        return List.of(
                new ReportSection("S-1", "一、经营业绩概要",
                        "报告期内，本集团实现营业收入3,375.32亿元，同比增长0.01%；归属于本行股东的净利润1,501.81亿元，同比增长1.21%。盈利结构保持稳定，净利息收入2,155.93亿元，净息差1.87%，同比下降11个基点；非利息净收入1,219.39亿元。业务及管理费1,079.43亿元，成本收入比31.98%，同比上升0.09个百分点，运营效率保持稳健。",
                        List.of("C-1", "C-2", "C-3", "C-4")),
                new ReportSection("S-2", "二、贷款质量",
                        "报告期末，本集团不良贷款余额682.06亿元，较上年末增加25.96亿元；不良贷款率0.94%，较上年末下降0.01个百分点。贷款减值准备2,672.24亿元，拨备覆盖率391.79%，较上年末下降20.19个百分点，风险抵补能力保持充足。",
                        List.of("C-5", "C-6", "C-7")),
                new ReportSection("S-3", "三、资本充足",
                        "报告期末，本集团高级法下核心一级资本充足率14.16%，较上年末下降0.70个百分点，资本充足水平保持同业前列。",
                        List.of("C-8")),
                new ReportSection("S-4", "四、口径与数据说明",
                        "本简报全部数值取自报告期公开年报快照，经确定性数值核验器逐项复算校验后交付；比率类指标变动以「个百分点」表述，净息差变动以「基点」表述，金额类变动以「亿元」表述。本简报不含前景展望章节，不构成任何预测或投资建议。",
                        List.of()));
    }

    private static List<Citation> validCitations() {
        return List.of(
                new Citation("C-1", "报告期内实现营业收入3,375.32亿元，同比微增", PRIMARY, 18, X1, D1),
                new Citation("C-2", "归属于本行股东的净利润1,501.81亿元，同比增长1.21%", PRIMARY, 18, X2, D2),
                new Citation("C-3", "净息差1.87%，同比下降11个基点", PRIMARY, 18, X3, D3),
                new Citation("C-4", "成本收入比31.98%，同比上升0.09个百分点", PRIMARY, 18, X4, D4),
                new Citation("C-5", "不良贷款余额682.06亿元，较上年末增加25.96亿元", PRIMARY, 30, X5, D5),
                new Citation("C-6", "不良贷款率0.94%，较上年末下降0.01个百分点", PRIMARY, 30, X6, D6),
                new Citation("C-7", "拨备覆盖率391.79%，较上年末下降20.19个百分点", PRIMARY, 30, X7, D7),
                new Citation("C-8", "高级法下核心一级资本充足率14.16%", PRIMARY, 37, X8, D8));
    }

    private static AnnualReportArtifacts draft(List<MetricValue> metrics,
                                               List<Citation> citations,
                                               List<ReportSection> sections) {
        return new AnnualReportArtifacts("TASK-2025-AR-001", "招商银行股份有限公司",
                "2025年度", metrics, citations, sections);
    }

    private static Violation first(List<Violation> vs, String rule) {
        return vs.stream().filter(v -> v.rule().equals(rule)).findFirst().orElse(null);
    }

    private static Violation mustHave(CheckResult r, String rule) {
        assertEquals(CheckStatus.FAILED, r.status(), "应判 FAILED: " + r.violations());
        Violation v = first(r.violations(), rule);
        assertNotNull(v, "应报 " + rule + "，实际违规: " + r.violations());
        return v;
    }

    // ———— 数值纪律：正例 ————

    @Test
    void validMetricsPass() {
        CheckResult r = new NumericChecker().check(draft(validMetrics(), validCitations(), validSections()), RESOLVER);
        assertEquals(CheckStatus.PASSED, r.status(), "真实年报数据全例应 PASSED: " + r.violations());
    }

    // ———— N1 / N2 / N3 / N4 ————

    @Test
    void missingMetricIsBlocking() {
        List<MetricValue> metrics = validMetrics().stream()
                .filter(m -> m.key() != MetricKey.PROVISION_COVERAGE).toList();
        CheckResult r = new NumericChecker().check(draft(metrics, validCitations(), validSections()), RESOLVER);
        mustHave(r, "METRIC_MISSING");
    }

    @Test
    void wrongUnitIsBlocking() {
        List<MetricValue> metrics = validMetrics().stream()
                .map(m -> m.key() == MetricKey.PROVISION_COVERAGE
                        ? mv(m.key(), "391.79", "个百分点", 30, D7, "-20.19", DeltaUnit.PERCENTAGE_POINT)
                        : m).toList();
        CheckResult r = new NumericChecker().check(draft(metrics, validCitations(), validSections()), RESOLVER);
        mustHave(r, "UNIT_MISMATCH");
    }

    @Test
    void fabricatedNumberIsBlocking() {
        List<MetricValue> metrics = validMetrics().stream()
                .map(m -> m.key() == MetricKey.PROVISION_COVERAGE
                        ? mv(m.key(), "399.99", "%", 30, D7, "-20.19", DeltaUnit.PERCENTAGE_POINT)
                        : m).toList();
        CheckResult r = new NumericChecker().check(draft(metrics, validCitations(), validSections()), RESOLVER);
        mustHave(r, "FABRICATED_NUMBER");
    }

    @Test
    void derivedNplRatioMismatchIsBlocking() {
        // 1.03 在页 30 真实出现（不良生成率），但按 682.06/72580.58 复算不良率应为 0.94
        List<MetricValue> metrics = validMetrics().stream()
                .map(m -> m.key() == MetricKey.NPL_RATIO
                        ? mv(m.key(), "1.03", "%", 30, D6, "-0.01", DeltaUnit.PERCENTAGE_POINT)
                        : m).toList();
        CheckResult r = new NumericChecker().check(draft(metrics, validCitations(), validSections()), RESOLVER);
        mustHave(r, "CALC_MISMATCH");
    }

    @Test
    void provisionCoverageMismatchIsBlocking() {
        // 3.68 在页 30 真实出现（贷款拨备率），但按 2672.24/682.06 复算拨备覆盖率应为 391.79
        List<MetricValue> metrics = validMetrics().stream()
                .map(m -> m.key() == MetricKey.PROVISION_COVERAGE
                        ? mv(m.key(), "3.68", "%", 30, D7, "-20.19", DeltaUnit.PERCENTAGE_POINT)
                        : m).toList();
        CheckResult r = new NumericChecker().check(draft(metrics, validCitations(), validSections()), RESOLVER);
        mustHave(r, "CALC_MISMATCH");
    }

    @Test
    void costIncomeMismatchIsBlocking() {
        // 0.60 在页 30 真实出现（信用成本），但按 1079.43/3375.32 复算成本收入比应为 31.98
        List<MetricValue> metrics = validMetrics().stream()
                .map(m -> m.key() == MetricKey.COST_INCOME_RATIO
                        ? mv(m.key(), "0.60", "%", 18, D4, "+0.09", DeltaUnit.PERCENTAGE_POINT)
                        : m).toList();
        CheckResult r = new NumericChecker().check(draft(metrics, validCitations(), validSections()), RESOLVER);
        mustHave(r, "CALC_MISMATCH");
    }

    // ———— N6 跨期同比（按句归属）————

    @Test
    void yoyMismatchIsBlocking() {
        List<ReportSection> sections = validSections().stream()
                .map(s -> s.sectionId().equals("S-1")
                        ? new ReportSection(s.sectionId(), s.title(),
                        s.body().replace("同比增长0.01%", "同比增长2.5%"), s.claimIds())
                        : s).toList();
        CheckResult r = new NumericChecker().check(draft(validMetrics(), validCitations(), sections), RESOLVER);
        Violation v = mustHave(r, "YOY_MISMATCH");
        assertEquals("OPERATING_REVENUE", v.target(), "同比论断应归属营业收入");
    }

    @Test
    void netProfitYoyAttributionIsCheckedAgainstProfitNotRevenue() {
        // 净利润同比被篡改为 2.5%：复算 (1501.81-1483.91)/1483.91 = 1.21% → 拦截且归属 NET_PROFIT
        List<ReportSection> sections = validSections().stream()
                .map(s -> s.sectionId().equals("S-1")
                        ? new ReportSection(s.sectionId(), s.title(),
                        s.body().replace("同比增长1.21%", "同比增长2.5%"), s.claimIds())
                        : s).toList();
        CheckResult r = new NumericChecker().check(draft(validMetrics(), validCitations(), sections), RESOLVER);
        Violation v = mustHave(r, "YOY_MISMATCH");
        assertEquals("NET_PROFIT_ATTRIBUTABLE", v.target(), "同比论断应归属净利润而非营业收入");
    }

    // ———— N7 变动口径 ————

    @Test
    void deltaUnitPercentForRatioIsBlocking() {
        // 受控错误样例：拨备覆盖率降 20.19 个百分点被写成「下降20.19%」
        List<ReportSection> sections = validSections().stream()
                .map(s -> s.sectionId().equals("S-2")
                        ? new ReportSection(s.sectionId(), s.title(),
                        s.body().replace("下降20.19个百分点", "下降20.19%"), s.claimIds())
                        : s).toList();
        CheckResult r = new NumericChecker().check(draft(validMetrics(), validCitations(), sections), RESOLVER);
        Violation v = mustHave(r, "DELTA_UNIT_MISMATCH");
        assertEquals(Severity.BLOCKING, v.severity());
    }

    @Test
    void deltaUnitPointForYuanIsBlocking() {
        // 不良余额增 25.96 亿元被写成「增加25.96个百分点」
        List<ReportSection> sections = validSections().stream()
                .map(s -> s.sectionId().equals("S-2")
                        ? new ReportSection(s.sectionId(), s.title(),
                        s.body().replace("增加25.96亿元", "增加25.96个百分点"), s.claimIds())
                        : s).toList();
        CheckResult r = new NumericChecker().check(draft(validMetrics(), validCitations(), sections), RESOLVER);
        mustHave(r, "DELTA_UNIT_MISMATCH");
    }

    @Test
    void correctDeltaPhrasesDoNotTriggerN7() {
        // 合法的 % 增速表述（营收 +0.01%）与同值的 pp 变动（不良率 -0.01）并存时不能误报
        CheckResult r = new NumericChecker().check(draft(validMetrics(), validCitations(), validSections()), RESOLVER);
        assertNull(first(r.violations(), "DELTA_UNIT_MISMATCH"));
    }

    // ———— 引用核验 ————

    @Test
    void supportedClaimsPass() {
        CheckResult r = new CitationChecker().check(draft(validMetrics(), validCitations(), validSections()),
                RESOLVER, SOURCES);
        assertEquals(CheckStatus.PASSED, r.status(), "真实摘录应 PASSED: " + r.violations());
    }

    @Test
    void tamperedExcerptIsBlocking() {
        List<Citation> citations = validCitations().stream()
                .map(c -> c.claimId().equals("C-7")
                        ? new Citation(c.claimId(), c.claimText(), c.sourceDocumentDigest(), c.page(),
                        "拨备覆盖率391.79%，较上年末下降21.19个百分点", c.evidenceExcerptDigest())
                        : c).toList();
        CheckResult r = new CitationChecker().check(draft(validMetrics(), citations, validSections()),
                RESOLVER, SOURCES);
        mustHave(r, "EXCERPT_DIGEST_MISMATCH");
    }

    @Test
    void fabricatedCitationIsBlocking() {
        List<Citation> citations = validCitations().stream()
                .map(c -> c.claimId().equals("C-8")
                        ? new Citation(c.claimId(), c.claimText(), c.sourceDocumentDigest(), 99,
                        c.evidenceExcerpt(), c.evidenceExcerptDigest())
                        : c).toList();
        CheckResult r = new CitationChecker().check(draft(validMetrics(), citations, validSections()),
                RESOLVER, SOURCES);
        mustHave(r, "FABRICATED_CITATION");
    }

    @Test
    void unregisteredSourcePageIsBlocking() {
        // 页 52 有真实页文本，但不在任何已注册信源内
        List<Citation> citations = validCitations().stream()
                .map(c -> c.claimId().equals("C-1")
                        ? new Citation(c.claimId(), c.claimText(), c.sourceDocumentDigest(), 52,
                        "本集团实现营业收入3,375.32亿元，同比增长0.01%",
                        "e938ca732a9286ae5168d05a939d779a214e8d31db841f8d9346199ea05e0bad")
                        : c).toList();
        CheckResult r = new CitationChecker().check(draft(validMetrics(), citations, validSections()),
                RESOLVER, SOURCES);
        mustHave(r, "SOURCE_UNREGISTERED");
    }

    @Test
    void partialSupportByYearMismatchIsWarning() {
        // claim 带年份而摘录无该年份 → PARTIAL（WARNING）
        List<Citation> citations = validCitations().stream()
                .map(c -> c.claimId().equals("C-1")
                        ? new Citation(c.claimId(), "2025年实现营业收入3,375.32亿元",
                        c.sourceDocumentDigest(), c.page(), c.evidenceExcerpt(), c.evidenceExcerptDigest())
                        : c).toList();
        CheckResult r = new CitationChecker().check(draft(validMetrics(), citations, validSections()),
                RESOLVER, SOURCES);
        Violation v = mustHave(r, "PARTIAL_SUPPORT");
        assertEquals(Severity.WARNING, v.severity());
    }

    @Test
    void unsupportedClaimIsBlocking() {
        // claim 无任何数字与关键实体 → UNSUPPORTED
        List<Citation> citations = validCitations().stream()
                .map(c -> c.claimId().equals("C-1")
                        ? new Citation(c.claimId(), "市场预期与经营节奏保持审慎",
                        c.sourceDocumentDigest(), c.page(), c.evidenceExcerpt(), c.evidenceExcerptDigest())
                        : c).toList();
        CheckResult r = new CitationChecker().check(draft(validMetrics(), citations, validSections()),
                RESOLVER, SOURCES);
        mustHave(r, "UNSUPPORTED_CLAIM");
    }

    @Test
    void uncitedClaimIsBlocking() {
        List<ReportSection> sections = validSections().stream()
                .map(s -> s.sectionId().equals("S-3")
                        ? new ReportSection(s.sectionId(), s.title(), s.body(), List.of("C-9"))
                        : s).toList();
        CheckResult r = new CitationChecker().check(draft(validMetrics(), validCitations(), sections),
                RESOLVER, SOURCES);
        mustHave(r, "UNCITED_CLAIM");
    }
}
