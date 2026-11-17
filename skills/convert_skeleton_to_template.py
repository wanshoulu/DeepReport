#!/usr/bin/env python3
"""
骨架 JSON → openJiuwen DeepSearch 官方 Markdown 模板转换器。

官方模板格式（DeepSearch Developer Guide）:
    # <顶层章节>
    > Functional summary: <该节功能说明>
    > Core section: true|false

    ## <小节>
    > Functional summary: <小节功能说明>

用法:
    python convert_skeleton_to_template.py <skeleton.json> <output.md> [--type annual_report|research|client_analysis]

- 年报骨架 (report-structure.json): sections[*].label 作章节标题；
  role=static 的节为合规披露类（非核心），analysis 节为核心。
- report-profiles 骨架 (skeletons/*.json): 见各文件自身结构。
"""
import json
import sys
from pathlib import Path


def convert_annual(src: Path) -> str:
    d = json.loads(src.read_text(encoding="utf-8"))
    order = d["section_order"]
    sections = d["sections"]
    lines = [
        "# 释义与重要提示" if False else "",
    ]
    out = []
    for i, sid in enumerate(order, 1):
        s = sections[sid]
        label = s["label"]
        role = s.get("role", "analysis")
        is_core = role == "analysis"
        summary_map = {
            "disclosure-officer": "合规披露类章节：依据来源材料如实转录与整理，不作推断性分析",
            "finance-analyst": "财务分析类章节：基于来源材料中的财务数据展开分析，所有数字须可溯源",
            "risk-analyst": "风险分析类章节：基于来源材料中的风险指标展开分析，所有数字须可溯源",
            "capital-analyst": "资本分析类章节：基于来源材料中的资本数据展开分析，所有数字须可溯源",
        }
        summary = summary_map.get(s.get("agent", ""), "基于来源材料展开章节内容")
        out.append(f"# {i}. {label}")
        out.append(f"> Functional summary: {summary}")
        out.append(f"> Core section: {'true' if is_core else 'false'}")
        out.append("")
    return "\n".join(out).strip() + "\n"


def convert_profile(src: Path) -> str:
    d = json.loads(src.read_text(encoding="utf-8"))
    sections = d.get("sections")
    out = []
    if isinstance(sections, dict) and "section_order" in d:
        # 年报式结构: {"section_order": [...], "sections": {"S-1": {"title","type","min_chars"}}}
        type_map = {
            "summary": "结论先行：提炼本章核心观点，供决策者快速把握",
            "background": "背景与口径：交代分析对象的基本面与统计口径，供后续分析引用",
            "analysis": "分析章节：基于来源材料展开论证，所有数字须可溯源",
            "outlook": "展望章节：基于来源材料的既有事实做有依据的外推，标注不确定性",
            "static": "合规披露类章节：依据来源材料如实转录与整理，不作推断性分析",
        }
        for i, sid in enumerate(d["section_order"], 1):
            s = sections[sid]
            title = s.get("title", s.get("label", f"Section {i}"))
            summary = type_map.get(s.get("type", "analysis"), "基于来源材料展开章节内容")
            core = s.get("type", "analysis") not in ("static",)
            out.append(f"# {i}. {title}")
            out.append(f"> Functional summary: {summary}")
            out.append(f"> Core section: {'true' if core else 'false'}")
            out.append("")
    elif isinstance(sections, list):
        # 列表式骨架: [{"id","title","summary","core"}...]
        for i, sec in enumerate(sections, 1):
            title = sec.get("title", sec.get("label", f"Section {i}"))
            summary = sec.get("summary", sec.get("functional_summary", "基于来源材料展开章节内容"))
            core = sec.get("core", True)
            out.append(f"# {i}. {title}")
            out.append(f"> Functional summary: {summary}")
            out.append(f"> Core section: {'true' if core else 'false'}")
            out.append("")
    return "\n".join(out).strip() + "\n"


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 2:
        print(__doc__)
        sys.exit(1)
    src, dst = Path(args[0]), Path(args[1])
    is_annual = "report-structure" in src.name or "annual" in src.name
    if is_annual:
        content = convert_annual(src)
    else:
        content = convert_profile(src)
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(content, encoding="utf-8")
    n_sections = content.count("# ")
    print(f"OK: {src.name} -> {dst} ({n_sections} sections, {len(content)} chars)")


if __name__ == "__main__":
    main()
