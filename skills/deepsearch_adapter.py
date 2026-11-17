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


def nearest_subject(text_before: str, subjects) -> str | None:
    best, found = -1, None
    for s in subjects:
        idx = text_before.rfind(s)
        if idx > best:
            best, found = idx, s
    return found


def subject_group(term: str | None) -> str:
    """主体词归组：同义/简称映射到同一组，避免"投资"vs"投资资产"错配。"""
    if term is None:
        return "(none)"
    groups = {
        "投资": ("投资", "投资资产", "金融投资"),
        "贷款": ("客户贷款", "贷款总额", "各项贷款", "贷款及垫款", "贷款"),
        "存款": ("客户存款", "存款总额", "存款"),
        "资产": ("总资产", "资产总额", "资产"),
        "营收": ("营业收入", "营收", "收入"),
        "利润": ("净利润", "利润", "归母净利润"),
        "净息差": ("净息差", "净利息差"),
    }
    for key, members in groups.items():
        if term in members:
            return key
    return term


def assess_support(claim: str, excerpt: str, subjects, periods=("2025", "2024", "本年度", "本期", "上年")) -> str:
    """语境绑定支持度：SUPPORTED / PARTIAL / UNSUPPORTED（与 Java 版语义一致，主体词带同义归组）"""
    if not claim or not excerpt:
        return "UNSUPPORTED"
    claims = []
    for m in NUMBER_RE.finditer(claim):
        num = m.group()
        if YEAR_RE.match(norm_num(num)):
            continue  # 年份是期间词
        claims.append((nearest_subject(claim[:m.start()], subjects), num))
    if not claims:
        ch = any(s in claim for s in subjects)
        th = any(s in excerpt for s in subjects)
        return "PARTIAL" if (ch and th) else "UNSUPPORTED"
    any_on_text = any_mismatch = any_missing = any_dangling = False
    for subject_c, num in claims:
        target = norm_num(num)
        hit = None
        for m in NUMBER_RE.finditer(excerpt):
            if norm_num(m.group()) == target:
                hit = m
                break
        if hit is None:
            any_dangling = True
            continue
        any_on_text = True
        subject_e = nearest_subject(excerpt[:hit.start()], subjects)
        # 主体归属一致判定（同义归组后比较）
        gc, ge = subject_group(subject_c), subject_group(subject_e)
        if subject_c is not None and subject_e is not None and gc != ge:
            any_mismatch = True
        elif subject_c is not None and subject_e is None:
            any_mismatch = True
    if not any_on_text:
        return "UNSUPPORTED"
    if any_mismatch:
        return "UNSUPPORTED"
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

def relocate(citation, page_texts_norm, page_by_name) -> list[int]:
    """论断中的数字反查全库，返回命中的页/块编号列表（空=全库无此数）。
    匹配带数字边界；过滤 <3 位有效数字的短串（无定位价值，易误命中）。"""
    claim_nums = [norm_num(x) for x in numbers_of(citation["claim_text"])
                  if not YEAR_RE.match(norm_num(x))]
    claim_nums = [n for n in claim_nums
                  if len(n.split(".")[0].lstrip("0") or "0") >= 3 or "." in n and len(n) >= 4]
    if not claim_nums:
        return []
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
    ap.add_argument("--subjects", default="营业收入,净利润,不良率,不良贷款率,总资产,净利润率,增长率,占比,满意度,续费率,客单价,毛利率,净息差,成本收入比,拨备覆盖率,资本充足率,客户贷款,存款总额,投资资产,贷款总额")
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

    page_texts_norm, page_by_name = {}, {}
    for p in sorted(materials_dir.glob("*.txt")):
        t = p.read_text(encoding="utf-8", errors="replace")
        page_texts_norm[p.name] = norm(t)
        br = block_range(str(p))
        page_by_name[p.name] = br[0] if br else -1

    violations = []
    cited_nums = set()
    for c in parsed["citations"]:
        # A2: 摘录 = 引用路径指向的块文本（digest 绑定）；远端路径不可达时先重定位
        src = c["source_path"]
        name = Path(src).name
        block = store.get(src) or store.get(str(materials_dir / name))
        # A3: 引用路径不可达 → 反查真实块并重绑定
        if block is None and args.relocate:
            hits = relocate(c, page_texts_norm, page_by_name)
            if hits:
                # 用第一个命中块作为证据（多块时保留候选列表）
                cand_name = next((n for n, pg in page_by_name.items() if pg == hits[0]), None)
                cand = store.get(str(materials_dir / cand_name)) if cand_name else None
                if cand:
                    block = cand
                    c["relocated_to"] = hits[:5]
                    violations.append(("CITATION_RELOCATED", c["claim_id"],
                                       f"{Path(src).name} 不可达 → 重定位至块 {hits[0]}", "INFO"))
        if block:
            c["evidence_excerpt"] = block["text"][:2000]
            c["excerpt_digest"] = block["digest"]
            # C2 语义支持度（有证据才判定）
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
