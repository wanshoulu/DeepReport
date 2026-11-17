/*
 * SemanticSupportAssessor —— 语境绑定的引用支持度判定（修复 L2 启发式误判）。
 *
 * 背景：旧 CitationChecker.assessSupport 用「数字集合交集 ∪ 实体词表交集」判 SUPPORTED，
 * 两个致命缺陷：
 *   (1) 数字语境丢失——论断「营业收入增长 0.85%」会命中摘录「不良率 0.85%」，
 *       因为交集只看数字字符串，不看数字属于哪个指标；即使同页有"营业收入"字样，
 *       只要落在窗口内也误判为支撑（实测两个反例都中招）。
 *   (2) 实体词表硬编码（招商银行/营业收入/不良…）——换主体/换报告类型语义漂移。
 *
 * 新算法（确定性、无 LLM、可审计）：把论断拆成若干「数值主张」(subject, number)，
 * 对每个数字，比较「论断中该数字前最近的主体词」与「摘录中该数字前最近的主体词」，
 * 两者一致才算语境匹配——即使同页出现其他主体词也不会误关联：
 *   - 所有主张主体一致且数字在摘录中          → SUPPORTED
 *   - 数字在摘录中但最近的主体词不同          → UNSUPPORTED（语境错配，BLOCKING）
 *   - 数字在但主体缺失/论断有期间词摘录无     → PARTIAL（转人工复核）
 *   - 论断无可抽取数值主张                    → 退回主体词重叠，拿不准一律 PARTIAL（fail-closed）
 *
 * 主体词/期间词通过 Lexicon 注入，不写死银行名词；未注入用通用词典。
 * 真正的语义蕴含判定仍由上层 CriticAgent（模型）负责；本类是确定性层能可靠判定的部分，
 * 凡证据不足一律降级而非放行。
 */
package com.agenthub.w06.checker;

import com.agenthub.w06.contract.Citation;
import com.agenthub.w06.contract.CitationSupport;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SemanticSupportAssessor {

    /** 报告类型画像：提供该场景的主体词、期间词、单位词。由 ReportProfile 实现。 */
    public interface Lexicon {
        Collection<String> subjectWords();
        Collection<String> periodWords();
        Collection<String> unitWords();
    }

    private record Claim(String subject, String number) {}

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:,\\d{3})*(?:\\.\\d+)?%?");
    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");

    private final Lexicon lexicon;

    public SemanticSupportAssessor() { this(null); }

    public SemanticSupportAssessor(Lexicon lexicon) { this.lexicon = lexicon; }

    /** 语境绑定的支持度判定。 */
    public CitationSupport assess(String claimText, String excerpt) {
        String claim = claimText == null ? "" : claimText;
        String text = excerpt == null ? "" : excerpt;
        if (claim.isBlank() || text.isBlank()) return CitationSupport.UNSUPPORTED;

        List<Claim> claims = extractClaims(claim);
        if (claims.isEmpty()) return assessNonNumeric(claim, text);

        boolean anyOnText = false, anyMismatch = false, anyMissing = false;

        for (Claim c : claims) {
            Matcher hit = findNumber(text, c.number());
            if (hit == null) { anyMissing = true; continue; }
            anyOnText = true;
            String subjectInText = nearestSubject(text.substring(0, hit.start()), subjects());
            if (c.subject() != null && !c.subject().equals(subjectInText)) {
                // 数字在，但摘录里它前面最近的主体词与论断不一致 → 语境错配
                anyMismatch = true;
            }
            // 单位紧邻：论断数值带 % 而摘录该数字不带 % → 口径可能不同（保守降级）
            if (c.number().endsWith("%") && !hit.group().endsWith("%")) {
                anyMissing = true;
            }
        }

        if (!anyOnText) return CitationSupport.UNSUPPORTED;
        if (anyMismatch) return CitationSupport.UNSUPPORTED;
        if (anyMissing || periodIncomplete(claim, text)) return CitationSupport.PARTIAL;
        return CitationSupport.SUPPORTED;
    }

    /** 兼容旧入口。 */
    public CitationSupport assess(Citation c) {
        return assess(c.claimText(), c.evidenceExcerpt());
    }

    /** 抽取数值主张；4 位年份是期间词，不作数值主张。 */
    private List<Claim> extractClaims(String claim) {
        List<Claim> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(claim);
        while (m.find()) {
            String num = m.group();
            if (YEAR.matcher(stripCommas(num)).matches()) continue;
            String subject = nearestSubject(claim.substring(0, m.start()), subjects());
            out.add(new Claim(subject, num));
        }
        return out;
    }

    /** 非数值论断：主体词重叠判支撑；即便重叠也无数字可核 → PARTIAL（转人工）。 */
    private CitationSupport assessNonNumeric(String claim, String text) {
        Collection<String> subs = subjects();
        boolean claimHas = false, textHas = false;
        for (String s : subs) {
            if (claim.contains(s)) claimHas = true;
            if (text.contains(s)) textHas = true;
        }
        if (claimHas && textHas) return CitationSupport.PARTIAL;
        return CitationSupport.UNSUPPORTED;
    }

    private boolean periodIncomplete(String claim, String text) {
        Collection<String> periods = (lexicon != null && lexicon.periodWords() != null
                && !lexicon.periodWords().isEmpty())
                ? lexicon.periodWords()
                : List.of("2025", "2024", "本年度", "本期", "上年");
        boolean claimHas = false, textHas = false;
        for (String p : periods) {
            if (claim.contains(p)) claimHas = true;
            if (text.contains(p)) textHas = true;
        }
        return claimHas && !textHas;
    }

    /** 取文本中最后一次出现的主体词（数字前最近的那个）。 */
    private static String nearestSubject(String before, Collection<String> subjects) {
        String found = null;
        int best = -1;
        for (String s : subjects) {
            int idx = before.lastIndexOf(s);
            if (idx > best) { best = idx; found = s; }
        }
        return found;
    }

    private Collection<String> subjects() {
        if (lexicon != null && lexicon.subjectWords() != null && !lexicon.subjectWords().isEmpty()) {
            return lexicon.subjectWords();
        }
        return List.of("营业收入", "净利润", "增长率", "占比", "不良率", "不良贷款率",
                "满意度", "续费率", "客单价", "毛利率", "净利率", "营收", "利润", "收入",
                "成本", "费率", "比率", "评分");
    }

    private static Matcher findNumber(String text, String number) {
        String target = stripCommas(number);
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            if (stripCommas(m.group()).equals(target)) return m;
        }
        return null;
    }

    private static String stripCommas(String s) { return s.replace(",", ""); }
}
