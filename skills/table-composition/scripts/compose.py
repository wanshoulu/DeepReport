#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""table-composition Skill 的确定性执行脚本。

stdin  : {"capability": {...}, "materials": [{"file": ..., "text": ...}]}
stdout : {"capability_id": ..., "headers": ..., "rows": ..., ...}

纪律：不调用模型、不访问网络、数字照抄原文、抽不到就留空并记 extraction_miss。
"""
import hashlib
import json
import re
import sys

# 数值矩阵抽取规则：指标词 -> (匹配正则, 条款定位词)
METRIC_RULES = [
    ("核心一级资本充足率", r"核心一级资本充足率不得低于\s*(\d+(?:\.\d+)?)%", "第二十六条"),
    ("一级资本充足率", r"(?<!核心)一级资本充足率不得低于\s*(\d+(?:\.\d+)?)%", "第二十六条"),
    ("资本充足率", r"(?<!核心一级)(?<!一级)资本充足率不得低于\s*(\d+(?:\.\d+)?)%", "第二十六条"),
    ("储备资本要求", r"储备资本要求为风险加权资产的\s*(\d+(?:\.\d+)?)%", "第二十七条"),
    ("杠杆率", r"杠杆率不得低于\s*(\d+(?:\.\d+)?)%", "第三十条"),
]

# 条款对照矩阵：技术落点关键词表（Skill 的领域方法，不是报告内容）
TECH_TERMS = [
    "风险管理平台", "规则策略", "模型算法", "集中统一管理", "全流程管理",
    "大数据", "人工智能", "风险监测预警", "数据治理", "数据质量", "数据标准",
    "交叉验证", "压力情景", "模拟校验", "可解释性", "可审计性", "算法歧视",
    "消费者权益保护", "模型管理核心环节", "数据分级分类", "脱敏", "隐私保护",
    "灾备", "实时", "规则引擎", "联邦学习", "图计算",
]

CLAUSE_RE = re.compile(r"(（[一二三四五六七八九十百]+）|第[一二三四五六七八九十百]+条)")


def fingerprint(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:12]


def compose_metric_table(cap, materials):
    """从原文抽「指标 / 字面数值 / 条款 / 原文片段 / 来源」矩阵。"""
    rows, miss, sources = [], [], []
    for label, pattern, clause in METRIC_RULES:
        found = False
        for m in materials:
            for hit in re.finditer(pattern, m["text"]):
                # 取命中处所在句子，作为证据摘录（字面命中，不改写）
                start = max(0, m["text"].rfind("。", 0, hit.start()) + 1)
                end = m["text"].find("。", hit.end())
                end = len(m["text"]) if end < 0 else end + 1
                excerpt = m["text"][start:end].strip()
                rows.append([label, hit.group(1) + "%", clause, excerpt, m["file"]])
                sources.append({"file": m["file"], "sha256_12": fingerprint(m["text"])})
                found = True
                break
            if found:
                break
        if not found:
            miss.append({"metric": label, "reason": "原文未命中字面数值，未补全"})
    return {
        "capability_id": cap["id"],
        "headers": ["监管指标", "最低要求（原文）", "条款", "原文摘录", "来源文件"],
        "rows": rows,
        "sources": list({s["file"]: s for s in sources}.values()),
        "extraction_rule": "指标词 + 不得低于/要求为 + 字面百分比；数字照抄原文，不做换算",
        "extraction_miss": miss,
    }


def compose_clause_matrix(cap, materials):
    """按条款号切分原文，生成「条款 / 要求摘要 / 技术落点 / 来源」。"""
    rows, sources = [], []
    for m in materials:
        text = m["text"]
        marks = list(CLAUSE_RE.finditer(text))
        for i, hit in enumerate(marks):
            seg_end = marks[i + 1].start() if i + 1 < len(marks) else len(text)
            segment = text[hit.start():seg_end].strip()
            first = re.split(r"[。\n]", segment, maxsplit=1)[0].strip()
            if len(first) < 8:
                continue
            terms = [t for t in TECH_TERMS if t in segment]
            rows.append([
                hit.group(1),
                first[:60],
                "、".join(terms[:5]) if terms else "（未命中技术关键词）",
                m["file"],
            ])
            sources.append({"file": m["file"], "sha256_12": fingerprint(text)})
    # 去重（同条款在多份材料重复出现）
    seen, uniq = set(), []
    for r in rows:
        key = (r[0], r[1][:20], r[3])
        if key not in seen:
            seen.add(key)
            uniq.append(r)
    return {
        "capability_id": cap["id"],
        "headers": ["条款", "要求摘要（原文首句）", "技术落点关键词", "来源文件"],
        "rows": uniq[:24],
        "sources": list({s["file"]: s for s in sources}.values()),
        "extraction_rule": "中文条款号切段 + 首句摘要 + 领域关键词命中；命中词来自 Skill 词表，不生成新论断",
        "extraction_miss": [] if uniq else [{"reason": "材料中未识别到中文条款号"}],
    }


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception as exc:
        json.dump({"error": "invalid stdin JSON: %s" % exc}, sys.stdout, ensure_ascii=False)
        return 1
    cap = payload.get("capability", {})
    materials = payload.get("materials", [])
    spec = str(cap.get("spec", ""))
    cap_id = str(cap.get("id", ""))
    # 按 capability 语义选抽取规则；规则属于 Skill 方法，不写死报告内容
    if "条款" in spec or "矩阵" in spec or "clause" in cap_id:
        out = compose_clause_matrix(cap, materials)
    else:
        out = compose_metric_table(cap, materials)
    json.dump(out, sys.stdout, ensure_ascii=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
