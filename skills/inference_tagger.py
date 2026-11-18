#!/usr/bin/env python3
"""
inference_tagger.py —— 定性推断标记器（报告类型无关，零硬编码泛化）。

原理：对（报告, 材料集）做字符 n-gram 差集分析，找出“报告高频、材料零/低频”
的词汇——这些是模型在写作中引入的推断/评价/包装词。不预设任何领域词表：
词表从每次运行的 diff 中自学习，适配任意报告类型（年报/投研/论文/尽调）。

核心算法：
  1. 对报告与材料分别抽取 2-4 字 n-gram（去停用位置：标点/数字边界）
  2. 报告频次 >= min_report_count 且材料频次 == 0 → 候选推断词
  3. 候选词按“信息量”过滤：包含在更长候选词中的短词剔除（保留最长）
     排除：纯数字/单位/通用连接词（从材料高频词自动统计通用词表，非硬编码）
  4. 密度分级：段内推断词密度 > threshold 的句子标 [INFERENCE]
  5. 输出：标记后的报告 + 推断词清单（按频次排序，供作者确认进白名单/黑名单）

用法:
  python inference_tagger.py <report.md> --materials-dir <dir> [--top N]
      [--mark] [--json-out inferred.json] [--tagged-out report_tagged.md]

设计约束：不 import 适配器、不改核验器——独立后处理步骤，可叠加任何报告。
"""
import argparse
import json
import re
from collections import Counter
from pathlib import Path

CJK = r'[\u4e00-\u9fff]'


def extract_ngrams(text: str, n_range=(2, 3, 4)) -> Counter:
    """抽取中文 n-gram 频次表（滑动窗口，跨标点切分）"""
    counter = Counter()
    # 按标点/空白切片段，避免跨句 n-gram
    segments = re.split(r'[。；！？\n,，、：:（）()\[\]{}""\'\'\s|<>#*\-—\.]', text)
    for seg in segments:
        seg = seg.strip()
        # 去掉数字/英文混杂段（数字主张由核验器负责，这里只管中文定性词）
        if not re.search(CJK, seg):
            continue
        for n in n_range:
            for i in range(len(seg) - n + 1):
                gram = seg[i:i + n]
                if re.fullmatch(CJK + '+', gram):
                    counter[gram] += 1
    return counter


def auto_stopwords(material_counter: Counter, report_counter: Counter, top: int = 200) -> set:
    """自动通用词表：材料与报告中都高频的词 = 领域通用词（非模型自创）。
    材料高频词天然是领域基础词，不进推断清单。"""
    common = set()
    for gram, cnt in report_counter.most_common(top * 3):
        if material_counter.get(gram, 0) > 0:
            common.add(gram)
    return common


def filter_candidates(candidates: Counter) -> Counter:
    """保留最长候选：若短词的所有出现都来自长词，剔除短词。
    简化实现：若某候选词是另一更高频候选的真子串且频次接近，剔除。"""
    items = sorted(candidates.items(), key=lambda x: -len(x[0]))
    kept = {}
    for gram, cnt in items:
        redundant = False
        for longer, lcnt in kept.items():
            if gram in longer and abs(cnt - lcnt) <= max(cnt, lcnt) * 0.5:
                redundant = True
                break
        if not redundant:
            kept[gram] = cnt
    return Counter(kept)


def extract_inference_claims(report: str) -> list:
    """抽取评价性句式窗口：句式模式是语言级规则（非领域级），可跨报告类型复用。
    模式：触发动词/名词短语 + 其后 n-gram。如"具备/享有/构成/建立/形成/彰显 + X"，
    "X 优势/壁垒/领先/能力/地位/潜力"等评价表述。"""
    claims = []
    triggers = [
        r'(?:具备|享有|构成|建立|形成|彰显|确立|展示|奠定|构筑|夯实)了?([^。；\n]{2,40})',
        r'([^。；\n]{1,20})(?:优势|壁垒|领先地位|竞争地位|核心竞争[^。；\n]{0,6}|护城河|想象空间)',
        r'(?:高度认可|巨大潜力|核心竞争力|显著(?:优势|提升|领先))',
    ]
    for ln in report.split('\n'):
        if ln.startswith('#') or ln.startswith('|'):
            continue
        for pat in triggers:
            for m in re.finditer(pat, ln):
                claims.append((ln.strip(), m.group(0)))
    return claims


def section_title_whitelist(report: str) -> set:
    """章节标题与目录词自动白名单：标题词在正文出现属正常复用，非推断。"""
    words = set()
    for m in re.finditer(r'^#{1,3}\s+(.+)$', report, re.M):
        title = m.group(1).strip()
        # 标题内 2-4 字滑窗全部入白名单
        clean = re.sub(r'[\d\.、\s]', '', title)
        for n in (2, 3, 4):
            for i in range(len(clean) - n + 1):
                words.add(clean[i:i + n])
    # 目录行同样处理
    for m in re.finditer(r'^\[?\d+[\.、]?\s*([^\]\n]{3,30})\]?\(?#?', report, re.M):
        clean = re.sub(r'[\d\.、\s\(\)]', '', m.group(1))
        for n in (2, 3, 4):
            for i in range(len(clean) - n + 1):
                words.add(clean[i:i + n])
    return words


def tag_sentences(report: str, infer_words: set, density_threshold: float = 0.15) -> tuple:
    """给含推断词的句子加标记；返回(标记后文本, (HIGH句数, LOW句数))。
    分级规则：句子含 checked_citation 引用或具体数字 → LOW（事实+评价包装，提示级）；
    纯评价（无引用无数字）→ HIGH（推断断言，需作者确认）。"""
    high = low = 0
    lines = report.split('\n')
    out_lines = []
    for ln in lines:
        if ln.startswith('#') or ln.startswith('|') or not re.search(CJK, ln):
            out_lines.append(ln)
            continue
        hits = sorted((w for w in infer_words if w in ln), key=len, reverse=True)
        if hits:
            has_cite = 'checked_citation' in ln
            has_number = bool(re.search(r'\d[\d,\.]{2,}', ln))
            total_len = max(1, len(re.sub(r'[^\u4e00-\u9fff]', '', ln)))
            covered = sum(len(w) for w in hits)
            density = covered / total_len
            if density >= density_threshold or len(hits) >= 2:
                if has_cite or has_number:
                    ln = ln.rstrip() + f"  `[INFERENCE-LOW: {', '.join(hits[:3])}]`"
                    low += 1
                else:
                    ln = ln.rstrip() + f"  `⚠ [INFERENCE-HIGH: {', '.join(hits[:3])}]`"
                    high += 1
        out_lines.append(ln)
    return '\n'.join(out_lines), (high, low)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("report")
    ap.add_argument("--materials-dir", required=True)
    ap.add_argument("--min-report-count", type=int, default=2,
                    help="报告中最少出现次数（默认2，过滤一次性噪声）")
    ap.add_argument("--top", type=int, default=50, help="输出推断词上限")
    ap.add_argument("--min-len", type=int, default=2)
    ap.add_argument("--max-len", type=int, default=4)
    ap.add_argument("--mark", action="store_true", help="在报告中就地打 [INFERENCE] 标记")
    ap.add_argument("--json-out", default="inferred_words.json")
    ap.add_argument("--tagged-out", default="report_tagged.md")
    args = ap.parse_args()

    report = Path(args.report).read_text(encoding="utf-8")
    mdir = Path(args.materials_dir)
    materials = "\n".join(p.read_text(encoding="utf-8", errors="replace")
                          for p in sorted(mdir.glob("*.txt")))

    # 推断词 = 评价句式窗口内的 n-gram ∩ 材料零频 n-gram（自动白名单：章节标题词）
    rep_grams = extract_ngrams(report, range(args.min_len, args.max_len + 1))
    mat_grams = extract_ngrams(materials, range(args.min_len, args.max_len + 1))

    claim_windows = extract_inference_claims(report)
    title_white = section_title_whitelist(report)

    candidates = Counter()
    for ln, snippet in claim_windows:
        ln_grams = extract_ngrams(snippet, range(args.min_len, args.max_len + 1))
        for g, cnt in ln_grams.items():
            if mat_grams.get(g, 0) == 0 and g not in title_white and rep_grams[g] >= args.min_report_count:
                candidates[g] += cnt
    candidates = filter_candidates(candidates)

    # 评分：频次 × 长度（越长越可能是实质性概念）
    scored = sorted(candidates.items(),
                    key=lambda x: -(x[1] * (1 + (len(x[0]) - 2) * 0.5)))[:args.top]

    result = {
        "report": args.report,
        "materials_dir": args.materials_dir,
        "inference_words": [{"word": w, "count": c} for w, c in scored],
        "total_candidates": len(candidates),
        "note": "报告高频但材料零频的词 = 模型写作引入的推断/评价词；需作者确认属合理推断还是无据断言",
    }
    Path(args.json_out).write_text(json.dumps(result, ensure_ascii=False, indent=2),
                                   encoding="utf-8")

    infer_words = {w for w, _ in scored}
    print(json.dumps({"inference_words_top": [w for w, _ in scored[:15]],
                      "total": len(scored)}, ensure_ascii=False))

    if args.mark:
        tagged, (high, low) = tag_sentences(report, infer_words)
        Path(args.tagged_out).write_text(tagged, encoding="utf-8")
        print(f"marked: HIGH={high} (需作者确认), LOW={low} (事实+评价包装) -> {args.tagged_out}")


if __name__ == "__main__":
    main()
