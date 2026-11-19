---
name: chart-composition
description: 把材料中抽取的结构化节点或数值列渲染为内联 SVG 图（flow / bar），每个节点附原文证据片段。当报告模板声明 kind=chart 且 w06-charts 尚未注册为 MCP 工具时使用；只渲染不改数、不发明节点。
entry: scripts/render.py
---

# chart-composition

## 何时使用

- 模板 `capabilities` 中出现 `kind: chart`。
- 路由表 `chart.registered_tool` 为 `null` 时由本 Skill 承载；一旦 `chart_spec_build` 注册进 `tools.json`，编排层应改为先探测该 MCP 工具，本 Skill 退居为渲染方法。

## 方法（确定性，无模型）

1. **flow**：渲染骨架固定为四个阶段槽（数据层 / 模型层 / 决策层 / 审计层）——这是版式方法论，不是报告内容。节点文字从材料原文中按关键词匹配抽取，每个节点带 `evidence`（命中句子）与 `source_file`；匹配不到的槽留空并记 `extraction_miss`。
2. **bar**：数值取 `table-composition` 产出的表格数值列，逐柱标注原始字面值；不换算、不插值。
3. SVG 全元素显式填色（避免宿主 CSS 类丢失导致黑块），输出为字符串交由编排层回填 `artifact.charts`。

## 输入输出契约

输入（stdin JSON）：

```json
{"capability": {"id": "...", "kind": "chart", "chart_type": "flow", "spec": "..."}, "materials": [{"file": "01.txt", "text": "..."}], "tables": []}
```

输出（stdout JSON）：

```json
{"capability_id": "...", "chart_type": "flow", "svg": "<svg ...>", "nodes": [{"stage": "", "label": "", "evidence": "", "source_file": ""}], "extraction_miss": []}
```

## 纪律

- 只渲染不改数、不发明图表（与项目 `chart-agent` 能力卡一致）。
- 图必须能回溯到材料：节点无 `evidence` 即视为未交付。
- 不使用网络、不调用 LLM。
