#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""chart-composition Skill 的确定性渲染脚本。

stdin  : {"capability": {...}, "materials": [...], "tables": [...]}
stdout : {"capability_id": ..., "chart_type": ..., "svg": "...", "nodes": [...], ...}

纪律：只渲染不改数、不发明节点；每个节点必须带原文 evidence。
"""
import html
import json
import re
import sys

# 四个阶段槽是版式方法论；槽里的节点文字必须从材料原文命中抽取
STAGES = [
    ("数据层", ["数据治理", "数据质量", "数据标准", "企业级大数据平台", "外部数据", "数据分级分类", "统一风险视图"]),
    ("模型层", ["模型算法", "集中统一管理", "规则策略", "评分卡", "联邦学习", "模型开发", "压力情景", "交叉验证"]),
    ("决策层", ["规则引擎", "风控决策引擎", "风险监测预警", "实时", "嵌入式", "业务流程", "一键上线"]),
    ("审计层", ["可解释性", "可审计性", "算法歧视", "消费者权益保护", "退出", "评价", "自主掌控"]),
]

SVG_W, SVG_H = 920, 230
BOX_W, BOX_H, GAP = 200, 130, 20
FILLS = ["#E8F1FB", "#E6F4EA", "#FFF4E5", "#F3E8FB"]
STROKES = ["#2F6FB5", "#2E7D4F", "#B26A16", "#7A4CA3"]


def find_evidence(materials, terms):
    """返回第一个命中关键词的原文句子，作为节点证据。"""
    for m in materials:
        text = m["text"]
        for term in terms:
            idx = text.find(term)
            if idx < 0:
                continue
            start = max(0, text.rfind("。", 0, idx) + 1)
            end = text.find("。", idx)
            end = len(text) if end < 0 else end + 1
            return term, text[start:end].strip(), m["file"]
    return None, "", ""


def render_flow(cap, materials):
    nodes, miss = [], []
    for stage, terms in STAGES:
        term, evidence, src = find_evidence(materials, terms)
        if not evidence:
            miss.append({"stage": stage, "reason": "材料原文未命中该层关键词"})
            nodes.append({"stage": stage, "label": "", "evidence": "", "source_file": ""})
        else:
            nodes.append({
                "stage": stage,
                "label": term,
                "evidence": evidence[:120],
                "source_file": src,
            })

    parts = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" '
        'role="img" aria-label="风控系统分层架构图">' % (SVG_W, SVG_H, SVG_W, SVG_H),
        '<rect x="0" y="0" width="%d" height="%d" fill="#FFFFFF"/>' % (SVG_W, SVG_H),
        '<text x="16" y="24" font-size="14" fill="#1F2933" font-family="sans-serif">'
        '风控系统分层架构（节点文字取自材料原文，见 evidence）</text>',
    ]
    for i, n in enumerate(nodes):
        x = 16 + i * (BOX_W + GAP)
        y = 50
        parts.append('<rect x="%d" y="%d" width="%d" height="%d" rx="8" fill="%s" stroke="%s" stroke-width="2"/>'
                     % (x, y, BOX_W, BOX_H, FILLS[i], STROKES[i]))
        parts.append('<text x="%d" y="%d" font-size="15" font-weight="bold" fill="#12212F" '
                     'font-family="sans-serif">%s</text>' % (x + 14, y + 30, html.escape(n["stage"])))
        label = n["label"] if n["label"] else "（材料未覆盖）"
        parts.append('<text x="%d" y="%d" font-size="13" fill="#33404D" font-family="sans-serif">%s</text>'
                     % (x + 14, y + 60, html.escape(label[:16])))
        if n["evidence"]:
            ev = html.escape(n["evidence"][:10])
            parts.append('<text x="%d" y="%d" font-size="11" fill="#5A6875" font-family="sans-serif">%s…</text>'
                         % (x + 14, y + 86, ev))
            parts.append('<text x="%d" y="%d" font-size="10" fill="#7A8794" font-family="sans-serif">来自 %s</text>'
                         % (x + 14, y + 108, html.escape(n["source_file"])))
        if i < len(nodes) - 1:
            ax, cy = x + BOX_W, y + BOX_H // 2
            parts.append('<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="#8A97A5" stroke-width="2"/>'
                         % (ax + 2, cy, ax + GAP - 10, cy))
            parts.append('<polygon points="%d,%d %d,%d %d,%d" fill="#8A97A5"/>'
                         % (ax + GAP - 4, cy, ax + GAP - 12, cy - 5, ax + GAP - 12, cy + 5))
    parts.append('</svg>')
    return {"capability_id": cap["id"], "chart_type": "flow",
            "svg": "".join(parts), "nodes": nodes, "extraction_miss": miss}


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception as exc:
        json.dump({"error": "invalid stdin JSON: %s" % exc}, sys.stdout, ensure_ascii=False)
        return 1
    cap = payload.get("capability", {})
    materials = payload.get("materials", [])
    ctype = cap.get("chart_type", "flow")
    if ctype != "flow":
        json.dump({"error": "unsupported chart_type: %s" % ctype,
                   "capability_id": cap.get("id"), "nodes": []}, sys.stdout, ensure_ascii=False)
        return 2
    json.dump(render_flow(cap, materials), sys.stdout, ensure_ascii=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
