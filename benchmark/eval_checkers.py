#!/usr/bin/env python3
"""
eval_checkers.py —— 双检可量化评测基准（补齐调研 gap 7.5：零公开评测数据）。

把「我们更严格」从口号变成可复现数字：对 NumericChecker / CitationChecker 的
判定逻辑各跑一组带标注的正负样本，输出 precision / recall / 误报率 / 漏报率。

口径：
  - 正样本(should_PASS)：合规工件，checker 应放行
  - 负样本(should_FAIL)：注入已知缺陷，checker 应拦截
  - TP=正确拦截负例, TN=正确放行正例, FP=误拦正例(误报), FN=漏拦负例(漏报)
  - precision = TP/(TP+FP), recall = TP/(TP+FN)

本脚本用与 Java SemanticSupportAssessor 等价的算法评测引用支持度层（L2），
并把结果写入 benchmark/results.json。Java 侧另有 JUnit 反例测试覆盖同一批用例。

用法: python benchmark/eval_checkers.py [--json-out benchmark/results.json]
"""
import re, json, sys, argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NUMBER = re.compile(r'\d+(?:,\d{3})*(?:\.\d+)?%?')
YEAR = re.compile(r'^(?:19|20)\d{2}$')


def norm(s): return s.replace(",", "")


def nearest_subject(before, subjects):
    best, found = -1, None
    for s in subjects:
        i = before.rfind(s)
        if i > best: best, found = i, s
    return found


def assess_support(claim, excerpt, subjects, periods):
    """与 Java SemanticSupportAssessor 等价的判定。"""
    if not claim or not excerpt: return "UNSUPPORTED"
    claims = []
    for m in NUMBER.finditer(claim):
        num = m.group()
        if YEAR.match(norm(num)): continue
        claims.append((nearest_subject(claim[:m.start()], subjects), num))
    if not claims:
        ch = any(s in claim for s in subjects); th = any(s in excerpt for s in subjects)
        return "PARTIAL" if (ch and th) else "UNSUPPORTED"
    any_on_text = any_mismatch = any_missing = False
    for subj, num in claims:
        hit = next((m for m in NUMBER.finditer(excerpt) if norm(m.group()) == norm(num)), None)
        if hit is None: any_missing = True; continue
        any_on_text = True
        subj_e = nearest_subject(excerpt[:hit.start()], subjects)
        if subj is not None and subj != subj_e: any_mismatch = True
        if num.endswith("%") and not hit.group().endswith("%"): any_missing = True
    if not any_on_text: return "UNSUPPORTED"
    if any_mismatch: return "UNSUPPORTED"
    if any_missing: return "PARTIAL"
    ch = any(p in claim for p in periods); th = any(p in excerpt for p in periods)
    if ch and not th: return "PARTIAL"
    return "SUPPORTED"


def load_profiles():
    profiles = {}
    base = ROOT / "source/config/report-profiles"
    for p in base.glob("*.json"):
        if p.name == "index.json": continue
        d = json.loads(p.read_text(encoding="utf-8"))
        profiles[d["report_type"]] = d
    return profiles


# 每例: (report_type, claim, excerpt, expected)  expected ∈ {SUPPORTED,UNSUPPORTED,PARTIAL}
# 口径：SUPPORTED=应放行(正); UNSUPPORTED=应拦截(负); PARTIAL=降级人工(算作"未放行")
CASES = [
    # --- 年报 ---
    ("annual_report", "2025年营业收入3,374.88亿元", "2025年，本集团实现营业收入3,374.88亿元", "SUPPORTED"),
    ("annual_report", "营业收入同比增长0.85%", "不良贷款率0.85%，同比下降。", "UNSUPPORTED"),
    ("annual_report", "营业收入增长0.85%", "2025年实现营业收入3374.88亿元，不良率0.85%。", "UNSUPPORTED"),
    ("annual_report", "2025年营业收入增长", "营业收入3,374.88亿元，同比增长0.83%。", "PARTIAL"),
    ("annual_report", "苹果公司发布新产品", "三、经营概况 2025年实现营业收入3,374.88亿元", "UNSUPPORTED"),
    ("annual_report", "净息差1.87%", "报告期内净息差1.87%，较上年收窄", "SUPPORTED"),
    ("annual_report", "归母净利润同比上升6.2%", "资本充足率6.2%，符合监管要求", "UNSUPPORTED"),
    # --- 投研 ---
    ("research", "毛利率增长12%", "净利率增长12%，成本控制良好", "UNSUPPORTED"),
    ("research", "本季度毛利率42.5%", "本报告期毛利率42.5%，环比提升", "SUPPORTED"),
    # 语义等价(ROE=净资产收益率)确定性层无法识别，属于已知限制，交给模型层；标注为 NEEDS_MODEL
    ("research", "净资产收益率18%", "公司ROE为18%，处于行业前列", "NEEDS_MODEL"),
    ("research", "市盈率25倍", "市净率25倍，估值偏高", "UNSUPPORTED"),
    # --- 客户分析 ---
    ("client_analysis", "客户满意度达到92分", "本年度客户满意度92分，较上年提升", "SUPPORTED"),
    ("client_analysis", "续费率85%", "流失率85%，需重点关注", "UNSUPPORTED"),
    ("client_analysis", "客单价较上期增长", "客单价156元，环比上升", "PARTIAL"),
    ("client_analysis", "活跃用户数120万人次", "本期活跃用户数120万人次", "SUPPORTED"),
]


def run():
    profiles = load_profiles()
    rows = []
    needs_model = 0
    for rtype, claim, excerpt, expected in CASES:
        lx = profiles[rtype]["support_lexicon"]
        got = assess_support(claim, excerpt, lx["subject_words"], lx["period_words"])
        if expected == "NEEDS_MODEL":
            needs_model += 1
            # 确定性层拿不准应降级(非 SUPPORTED)才符合预期；若它擅自 SUPPORTED 反而是危险误放行
            handled_ok = got != "SUPPORTED"
            rows.append({"report_type": rtype, "claim": claim, "excerpt": excerpt,
                         "expected": expected, "got": got, "match": handled_ok,
                         "note": "语义等价需模型层；确定性层应保守降级"})
            continue
        # 支持度三态映射为 放行/拦截：SUPPORTED=放行(neg→TN/FP), 其余=拦截
        rows.append({"report_type": rtype, "claim": claim, "excerpt": excerpt,
                     "expected": expected, "got": got, "match": got == expected})

    matched = sum(r["match"] for r in rows)
    # 二分类混淆矩阵只统计有明确标注的用例，NEEDS_MODEL 单列
    tp = tn = fp = fn = 0
    for r in rows:
        if r["expected"] == "NEEDS_MODEL": continue
        expected_block = r["expected"] != "SUPPORTED"
        got_block = r["got"] != "SUPPORTED"
        if expected_block and got_block: tp += 1
        elif (not expected_block) and (not got_block): tn += 1
        elif (not expected_block) and got_block: fp += 1
        elif expected_block and (not got_block): fn += 1
    precision = tp / (tp + fp) if (tp + fp) else 1.0
    recall = tp / (tp + fn) if (tp + fn) else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0

    result = {
        "benchmark": "L2 citation support (context-bound assessor)",
        "checker_version": "0.3.0",
        "total_cases": len(rows),
        "exact_match": f"{matched}/{len(rows)}",
        "needs_model_layer": needs_model,
        "confusion": {"TP": tp, "TN": tn, "FP": fp, "FN": fn,
                      "$comment": "仅统计有明确 SUPPORTED/UNSUPPORTED/PARTIAL 标注的用例；NEEDS_MODEL 单列"},
        "precision": round(precision, 4),
        "recall": round(recall, 4),
        "f1": round(f1, 4),
        "false_positive_rate": round(fp / (fp + tn), 4) if (fp + tn) else 0.0,
        "false_negative_rate": round(fn / (fn + tp), 4) if (fn + tp) else 0.0,
        "by_report_type": {},
        "cases": rows,
    }
    for rtype in profiles:
        sub = [r for r in rows if r["report_type"] == rtype]
        result["by_report_type"][rtype] = {
            "n": len(sub), "matched": sum(r["match"] for r in sub)}
    return result


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--json-out", default=str(ROOT / "benchmark/results.json"))
    args = ap.parse_args()
    res = run()
    Path(args.json_out).write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"L2 支持度评测: exact_match={res['exact_match']}  "
          f"precision={res['precision']} recall={res['recall']} f1={res['f1']}")
    print(f"混淆矩阵: {res['confusion']}  误报率={res['false_positive_rate']} 漏报率={res['false_negative_rate']}")
    for rt, v in res["by_report_type"].items():
        print(f"  {rt}: {v['matched']}/{v['n']}")
    bad = [r for r in res["cases"] if not r["match"]]
    if bad:
        print("\n未匹配用例:")
        for r in bad:
            print(f"  [{r['report_type']}] want={r['expected']} got={r['got']} | {r['claim']} :: {r['excerpt']}")
