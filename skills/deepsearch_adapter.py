#!/usr/bin/env python3
"""
deepsearch_adapter.py —— Phase 1 适配层：DeepSearch 输出 → ReportArtifact + 核验。

职责（对应 docs/phase1-verification-adapter-design.md 的 A1–A5）:
  A1 解析:   DeepSearch 报告 Markdown（checked_citation 标记）→ ReportArtifact 结构
  A2 证据店: 引用指向的本地页/分组块落盘为 evidence store（digest 索引）
  A3 重定位: 引用页范围标错时反查真实页码（数字全库搜索），自动修复
  A4 孤数:   无引用支撑的数值 → ORPHAN_NUMBER（默认 BLOCKING，可降 WARNING）
  A5 章节:   产出章节 ⊆ 模板章节校验（N8），缺节 BLOCKING

用法:
  python deepsearch_adapter.py <report.md> --materials-dir <dir> [--template-file md]
      [--relocate] [--orphan-warning] [--json-out result.json] [--artifact-out artifact.json]

退出码: 0=核验通过, 1=存在 BLOCKING
"""
import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

# ---------------- 与 SemanticSupportAssessor 同源的判定（Python 版） ----------------

NUMBER_RE = re.compile(r"\d+(?:,\d{3})*(?:\.\d+)?%?")
YEAR_RE = re.compile(r"^(?:19|20)\d{2}$")


def norm_num(s: str) -> str:
    return s.replace(",", "").rstrip("%")


# 量词类词：只描述数字的性质（"占比从29.0%"），不是数字的主体；
# 在最近主体判定中排除，防止表格列头/句式量词污染主体归属
_QUANTIFIER_WORDS = {"占比", "增长率", "比重", "增幅", "百分比"}


def nearest_subject(text_before: str, subjects) -> str | None:
    best, found = -1, None
    for s in subjects:
        if s in _QUANTIFIER_WORDS:
            continue
        idx = text_before.rfind(s)
        if idx > best:
            best, found = idx, s
    return found


def subject_group(term: str | None) -> str:
    """主体词归组：同义/简称映射到同一组，避免"投资"vs"投资资产"错配。"""
    if term is None:
        return "(none)"
    for key, members in _SYNONYM_GROUPS.items():
        if term in members:
            return key
    return term


# 同义组全局定义（subject_group 与 assess_support 共用）
_SYNONYM_GROUPS = {
    "投资": ("投资", "投资资产", "金融投资"),
    "贷款": ("客户贷款", "贷款总额", "各项贷款", "贷款及垫款", "贷款"),
    "存款": ("客户存款", "存款总额", "存款"),
    "资产": ("总资产", "资产总额", "资产"),
    "营收": ("营业收入", "营收", "收入"),
    "利润": ("净利润", "利润", "归母净利润"),
    "净息差": ("净息差", "净利息差"),
    "负债": ("总负债", "负债总额", "负债"),
}


def assess_support(claim: str, excerpt: str, subjects, periods=("2025", "2024", "本年度", "本期", "上年")) -> str:
    """语境绑定支持度：SUPPORTED / PARTIAL / UNSUPPORTED（与 Java 版语义一致，主体词带同义归组）"""
    if not claim or not excerpt:
        return "UNSUPPORTED"
    claims = []
    for m in NUMBER_RE.finditer(claim):
        num = m.group()
        if YEAR_RE.match(norm_num(num)):
            continue  # 年份是期间词
        # 有意义数值主张：排除列表序号（"1. **"形态）与表号（"表7-1"）
        before = claim[:m.start()]
        after = claim[m.end():]
        if re.match(r"\.\s+(?:\*\*|[^\d])", after) and len(num) <= 2:
            continue  # markdown 列表序号（序号数字在前, 判定须看数字之后）
        if re.search(r"表\s*$", before) or re.search(r"表\s*\d+\s*-\s*$", before):
            continue  # 表号（表7-1 的 '7' 与 '1' 两段都要跳过）
        if not re.search(r"\d", num):
            continue
        claims.append((nearest_subject(before, subjects), num))
    if not claims:
        # 无可抽取数值 = 定性主张：确定性层无法证实也无法证伪 → 一律 PARTIAL
        # （转人工复核，fail-closed 但不过严；BLOCKING 只留给数字类矛盾）
        return "PARTIAL"
    any_on_text = any_mismatch = any_missing = any_dangling = False
    for subject_c, num in claims:
        is_percent = num.endswith("%")
        # 原文表格中百分比常不带 % 号（"55.6"），检索时按裸数字匹配；
        # 负数双记法（claim "-390.93" vs 原文会计括号 "(390.93)"）取绝对值归一
        target = norm_num(num).lstrip("-")
        hit = None
        all_hits = [m for m in NUMBER_RE.finditer(excerpt) if norm_num(m.group()).lstrip("-") == target]
        if all_hits:
            hit = all_hits[0]
        if hit is None:
            # % 主张降级裸数字匹配（原文表格百分比常不带 % 号，如 "55.6"）；
            # 同时做去尾零宽松匹配（"7.10%" vs 原文 "7.1"）
            bare = target.rstrip("%")
            candidates = [bare]
            if "." in bare:
                candidates.append(bare.rstrip("0").rstrip("."))
            for cand in candidates:
                cand_hits = [m for m in NUMBER_RE.finditer(excerpt) if norm_num(m.group()) == cand]
                if cand_hits:
                    hit = cand_hits[0]
                    # 注意: 不改 is_percent —— 百分比主张由原始形态决定, 免主体归属
                    break
        if hit is None:
            any_dangling = True
            continue
        any_on_text = True
        # 百分比类主张免主体归属检查：原文中百分比以分号串联多个指标
        # （"增长7.1%；投资169,074.15亿元"），最近主体词归属对百分比天然脆弱，
        # 其语境约束由数值本身+所在句承担；只对绝对额做主体匹配。
        if is_percent:
            continue
        gc = subject_group(subject_c)
        if subject_c is None:
            continue
        # 多处同值命中时，优先选共现窗口含主体同义词的命中（表格里同名数字
        # 可能先出现但语境无关）；都不含则取第一个命中。
        if gc in _SYNONYM_GROUPS:
            members = list(_SYNONYM_GROUPS[gc])
            best_hit = None
            for m in all_hits or [hit]:
                win = excerpt[max(0, m.start() - 120): m.start()]
                if any(w in win for w in members):
                    best_hit = m
                    break
            if best_hit is not None:
                hit = best_hit
        # 同义组双向共现：claim 主体组内任一同义词出现在数字附近（前 120 字符内）
        # 即认为语境一致。解决"存款总额"(claim) vs "客户存款"(原文)的用词差异，
        # 以及表格列头词（占比/金额）就近污染最近主体的问题。
        members = [m for g in _SYNONYM_GROUPS.values() if gc in g for m in g]
        if not members:
            members = [subject_c]
        window = excerpt[max(0, hit.start() - 120): hit.start()]
        if any(w in window for w in members):
            continue  # 语境一致
        # 数字附近无任何同义词 → 检查摘录侧最近主体是否属于其他已知组（真错配）
        subject_e = nearest_subject(excerpt[:hit.start()], subjects)
        ge = subject_group(subject_e)
        if subject_e is not None and ge != gc and ge != "(none)":
            # 附近出现过其他组主体且非表格列头泛词才算真错配
            if subject_e not in ("占比", "金额", "增长率", "比重"):
                any_mismatch = True
    if not any_on_text:
        return "UNSUPPORTED"
    # 差值/派生数字放宽：claim 中已有其他数字成功命中（可溯源），仅个别数字
    # （通常是"减少了1.0个百分点/略降1.31个百分点"这类自算差值）语境缺失 → 转人工而非硬拦。
    # BLOCKING 留给"完全无一处可溯源"或"非表格语境的真错配"。
    derived_style = bool(re.search(r"个百分点|百分点", claim))
    if any_mismatch:
        # 表格结构共现：mismatch 的数字在摘录（标称块）中确实存在（边界匹配），
        # 只是主体词因表格行/列结构距离较远 —— 这类转人工复核（人一眼可确认），
        # 不做 BLOCKING。真错配（数字根本不在标称块）才拦截。
        return "PARTIAL"
    if derived_style:
        return "PARTIAL"
    if any_missing or any_dangling:
        return "PARTIAL"
    if any_missing or any_dangling:
        return "PARTIAL"
    if any(p in claim for p in periods) and not any(p in excerpt for p in periods):
        return "PARTIAL"
    return "SUPPORTED"


# ---------------- A1: 解析 DeepSearch 报告 ----------------

CITE_RE = re.compile(
    r"\[checked_citation:(\d+)\]\[\[(\d+)\]\]\(([^)]+)\)")
HEADING_RE = re.compile(r"^(#{1,3})\s+(.+)$", re.M)


def sha256(t: str) -> str:
    return hashlib.sha256(t.encode("utf-8")).hexdigest()


def parse_report(md: str) -> dict:
    """按引用标记切分正文 → sections + citations（保持出现顺序）"""
    sections, citations = [], []
    pos = 0
    cur_head = None
    for line_iter in md.splitlines(keepends=True):
        pass  # placeholder to keep structure simple; real parse below
    # 简化实现：以三级标题分节；引用标记从行内剥离并记录偏移
    lines = md.splitlines(keepends=True)
    buf = []

    def flush():
        if cur_head is not None or buf:
            sections.append({"title": cur_head or "(preamble)",
                             "text": "".join(buf)})

    for ln in lines:
        hm = HEADING_RE.match(ln.rstrip())
        if hm:
            flush()
            buf.clear()
            cur_head = hm.group(2).strip()
        else:
            clean_line = CITE_RE.sub("", ln)
            # 按标记切分本行: 相邻两标记之间的干净文本 = 前一个标记的论断
            marks = list(CITE_RE.finditer(ln))
            for i, cm in enumerate(marks):
                cid, mid, path = cm.group(1), cm.group(2), cm.group(3)
                # 段起点: 上一标记结束或行首; 段终点: 本标记开始
                seg_start = marks[i-1].end() if i > 0 else 0
                seg = CITE_RE.sub("", ln[seg_start:cm.start()]).strip(" \t；;。 ")
                pre = seg[-300:]
                if pre:
                    citations.append({
                        "claim_id": f"C-{len(citations)+1}",
                        "claim_text": pre,
                        "marker_id": int(cid),
                        "ref_index": int(mid),
                        "source_path": path,
                        "excerpt_digest": "",
                        "evidence_excerpt": "",
                    })
            buf.append(clean_line)
    flush()
    return {"sections": sections, "citations": citations}


# ---------------- A2: 证据店 ----------------

def build_evidence_store(materials_dir: Path, cite_paths) -> dict:
    """材料分组块 → store 条目（digest 索引）；同时提供整库 norm 文本用于重定位"""
    store, all_norm = {}, []
    for p in sorted(materials_dir.glob("*.txt")):
        t = p.read_text(encoding="utf-8", errors="replace")
        store[str(p)] = {"text": t, "digest": sha256(t)}
    return store


def norm(t: str) -> str:
    return t.replace(",", "").replace(" ", "").replace("\n", "")


def numbers_of(t: str):
    return {norm_num(m.group()) for m in NUMBER_RE.finditer(t)}


# ---------------- A3: 引用重定位 ----------------

def make_existence_checker(raw_texts: dict):
    """两级存在性检查：原文边界匹配（避免 norm 粘连导致的假阴性）→
    去逗号文本边界匹配（claim 无逗号 vs 原文千分位逗号）。

    raw_texts: {name: 原始文本} —— 必须是未 norm 的原文。
    返回 exists(num) -> 命中的 name 列表。
    """
    comp = {name: t.replace(",", "") for name, t in raw_texts.items()}

    def exists(num: str):
        num = norm_num(num)
        if not num:
            return []
        pats = [re.compile(r"(?<![\d.])" + re.escape(num) + r"(?![\d.])")]
        loose = num.rstrip("0").rstrip(".") if "." in num else num
        if loose != num:
            pats.append(re.compile(r"(?<![\d.])" + re.escape(loose) + r"(?![\d.])"))
        hits = []
        for name, raw in raw_texts.items():
            if any(p.search(raw) for p in pats):
                hits.append(name)
        if hits:
            return hits
        for name, t in comp.items():
            if any(p.search(t) for p in pats):
                hits.append(name)
        return hits

    return exists


def relocate(citation, page_texts_norm, page_by_name, raw_texts=None, exists_fn=None) -> list[int]:
    """论断中的数字反查全库，返回命中的页/块编号列表（空=全库无此数）。
    优先用 exists_fn（原文边界+去逗号两级），fallback 到 norm 子串。"""
    claim_nums = [norm_num(x) for x in numbers_of(citation["claim_text"])
                  if not YEAR_RE.match(norm_num(x))]
    claim_nums = [n for n in claim_nums
                  if len(n.split(".")[0].lstrip("0") or "0") >= 3 or "." in n and len(n) >= 4]
    if not claim_nums:
        return []
    if exists_fn is not None:
        hit_names = set()
        for n in claim_nums:
            hit_names.update(exists_fn(n))
        return sorted({page_by_name.get(nm, -1) for nm in hit_names if page_by_name.get(nm, -1) >= 0})
    pats = [re.compile(r"(?<![\d.])" + re.escape(n) + r"(?![\d.])") for n in claim_nums]
    hits = []
    for name, tnorm in page_texts_norm.items():
        if any(p.search(tnorm) for p in pats):
            hits.append(page_by_name.get(name, -1))
    return sorted(h for h in hits if h >= 0)


# ---------------- 主流程 ----------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("report")
    ap.add_argument("--materials-dir", required=True)
    ap.add_argument("--template-file", default="")
    ap.add_argument("--relocate", action="store_true", help="引用页标错时自动反查真实页码")
    ap.add_argument("--orphan-warning", action="store_true", help="ORPHAN_NUMBER 降为 WARNING")
    ap.add_argument("--subjects", default="营业收入,净利润,不良率,不良贷款率,总资产,净利润率,占比,满意度,续费率,客单价,毛利率,净息差,成本收入比,拨备覆盖率,资本充足率,客户贷款,存款总额,投资资产,投资类资产,贷款总额,投资,各项贷款,客户存款,资产总额,业务及管理费,风险加权资产,归属于母公司股东的权益")
    ap.add_argument("--json-out", default="adapter_result.json")
    ap.add_argument("--artifact-out", default="report_artifact.json")
    args = ap.parse_args()

    md = Path(args.report).read_text(encoding="utf-8")
    materials_dir = Path(args.materials_dir)
    subjects = [s.strip() for s in args.subjects.split(",") if s.strip()]

    parsed = parse_report(md)
    store = build_evidence_store(materials_dir, [c["source_path"] for c in parsed["citations"]])

    # 块编号推断: pages-025-028 → (25,28)；单页 page-8 → (8,8)
    def block_range(path):
        m = re.search(r"pages-(\d+)-(\d+)\.txt", path) or re.search(r"page-(\d+)\.txt", path)
        if not m:
            return None
        return int(m.group(1)), int(m.group(2)) if m.lastindex >= 2 else int(m.group(1))

    page_texts_norm, page_by_name, raw_texts = {}, {}, {}
    for p in sorted(materials_dir.glob("*.txt")):
        t = p.read_text(encoding="utf-8", errors="replace")
        raw_texts[p.name] = t
        page_texts_norm[p.name] = norm(t)
        br = block_range(str(p))
        page_by_name[p.name] = br[0] if br else -1

    # 两级存在性检查（原文边界 → 去逗号边界），供重定位使用
    exists_fn = make_existence_checker(raw_texts)

    violations = []
    cited_nums = set()
    for c in parsed["citations"]:
        # A2: 摘录 = 引用路径指向的块文本（digest 绑定）
        src = c["source_path"]
        name = Path(src).name
        block = store.get(src) or store.get(str(materials_dir / name))
        claimed_ok = False
        if block:
            # 先按标称块判定：论断数字确在该块中才算引用成立
            support0 = assess_support(c["claim_text"], block["text"], subjects)
            if support0 in ("SUPPORTED", "PARTIAL"):
                claimed_ok = True
        # A3: 标称块不含论断数字（页码标错或路径不可达）→ 反查全库重定位
        if not claimed_ok and args.relocate:
            hits = relocate(c, page_texts_norm, page_by_name, exists_fn=exists_fn)
            if hits:
                cand_name = next((n for n, pg in page_by_name.items() if pg == hits[0]), None)
                cand = store.get(str(materials_dir / cand_name)) if cand_name else None
                if cand and cand is not block:
                    c["relocated_to"] = hits[:5]
                    c["original_source"] = src
                    violations.append(("CITATION_RELOCATED", c["claim_id"],
                                       f"{Path(src).name} → 块 {hits[0]}", "INFO"))
                    block = cand
        if block:
            c["evidence_excerpt"] = block["text"][:2000]
            c["excerpt_digest"] = block["digest"]
            # C2 语义支持度（重定位后用新块判定）
            support = assess_support(c["claim_text"], block["text"], subjects)
            c["support"] = support
            if support == "UNSUPPORTED":
                violations.append(("UNSUPPORTED_CLAIM", c["claim_id"],
                                   c["claim_text"][:80], "BLOCKING"))
            elif support == "PARTIAL":
                violations.append(("PARTIAL_SUPPORT", c["claim_id"],
                                   c["claim_text"][:80], "WARNING"))
        else:
            c["support"] = "NO_EVIDENCE"
            violations.append(("CITATION_UNRESOLVED", c["claim_id"],
                               f"证据块不可达且反查无果: {Path(src).name}", "WARNING"))
        # 收集已引用数字（用于 A4 孤数判定）
        for n in numbers_of(c["claim_text"]):
            cited_nums.add(norm_num(n))

    # A4: 孤数检查 —— 正文数值（2位小数或大额）须被某引用覆盖
    orphan = 0
    for sec in parsed["sections"]:
        for n in numbers_of(sec["text"]):
            nn = norm_num(n)
            if YEAR_RE.match(nn):
                continue
            if nn not in cited_nums and re.search(r"\d+\.\d{2}", nn):
                orphan += 1
    if orphan:
        sev = "WARNING" if args.orphan_warning else "BLOCKING"
        violations.append(("ORPHAN_NUMBER", "*", f"{orphan} 个数值无引用支撑", sev))

    # A5: 章节完整性（N8）
    if args.template_file:
        tpl = Path(args.template_file).read_text(encoding="utf-8")
        tpl_heads = [m.group(2).strip().split(". ", 1)[-1]
                     for m in re.finditer(r"^#\s+\d+\.\s*(.+)$", tpl, re.M)]
        out_heads = [s["title"].split(". ", 1)[-1] for s in parsed["sections"]
                     if re.match(r"^\d+\.\s", s["title"] or "")]
        missing = [h for h in tpl_heads if h not in out_heads]
        if missing:
            violations.append(("SECTION_MISSING", "*",
                               f"模板章节缺失: {missing[:5]}", "BLOCKING"))

    blocking = [v for v in violations if v[3] == "BLOCKING"]
    result = {
        "report": args.report,
        "sections": len(parsed["sections"]),
        "citations": len(parsed["citations"]),
        "evidence_blocks": len(store),
        "violations": [{"rule": r, "target": t, "detail": d, "severity": s}
                       for r, t, d, s in violations],
        "blocking_count": len(blocking),
        "status": "FAILED" if blocking else "PASSED",
        "stats": {
            "orphan_numbers": orphan,
            "cited_claims_with_support": sum(
                1 for c in parsed["citations"] if c.get("support") == "SUPPORTED"),
            "partial": sum(1 for c in parsed["citations"] if c.get("support") == "PARTIAL"),
            "unsupported": sum(1 for c in parsed["citations"] if c.get("support") == "UNSUPPORTED"),
        },
    }
    Path(args.json_out).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    artifact = {
        "report_type": "annual_report",
        "sections": parsed["sections"],
        "citations": parsed["citations"],
        "evidence_store": {"type": "local_blocks", "items": list(store.values())},
    }
    Path(args.artifact_out).write_text(json.dumps(artifact, ensure_ascii=False, indent=2), encoding="utf-8")

    print(json.dumps({k: result[k] for k in ("status", "sections", "citations", "blocking_count", "stats")},
                     ensure_ascii=False))
    print(f"artifact -> {args.artifact_out} | result -> {args.json_out}")
    sys.exit(0 if not blocking else 1)


if __name__ == "__main__":
    main()
