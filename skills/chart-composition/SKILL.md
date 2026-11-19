---
name: chart-composition
description: 成图能力的方法论：判断何时该出图、节点如何取自材料原文、图型如何选择。执行已上移为标准 MCP 工具 chart_render（tools/list 可发现、tools/call 可调用），本 Skill 不执行、不产出工件。
mcp_tool: chart_render
layer: methodology
---

# chart-composition（方法论）

> **分层变更（v0.2）**：`ARCHITECTURE-v3.md:156` 曾标注 `w06-charts 待注册`，图表一度由本 Skill 的本地脚本承载。
> 现已把成图注册为标准 MCP 工具 `chart_render`，生产网关以通用 `mcp_bridge` 分支转发；本 Skill **不再声明 `entry`**。

## 何时使用

1. 模板 `capabilities` 中出现 `kind: chart`。
2. Agent 先 `tools/list` 发现 `chart_render`，再按路由表 `chart.mcp_tool` 绑定。
3. 工具未在发现结果中出现 → `MCP_TOOL_NOT_FOUND` 记 BLOCKING，不回退到本地脚本。

## 方法（由 MCP 工具执行，此处只定义判据）

1. **flow**：四个阶段槽（数据层 / 模型层 / 决策层 / 审计层）是版式方法论，不是报告内容。槽里的节点文字必须命中材料原文关键词，每节点带 `evidence`（命中句子）与 `source_file`；匹配不到的槽标注「材料未覆盖」并记 `extraction_miss`。
2. **bar**（规划中）：数值取 `table_compose` 产出的表格数值列，逐柱标注原始字面值；不换算、不插值。当前工具仅实现 `flow`，其它图型返回 `unsupported chart_type`。
3. SVG 全元素显式填色（避免宿主 CSS 类丢失导致黑块），内联输出、无外链、无脚本。

## 工具契约（唯一事实源：`mcp/tools.json`）

调用：

```json
{"name": "chart_render", "arguments": {"capability": {"id": "...", "chart_type": "flow"}, "materials": [{"file": "01.txt", "text": "..."}]}}
```

返回：`capability_id` / `chart_type` / `svg` / `nodes` / `extraction_miss`。

## 纪律

- 只渲染不改数、不发明图表（与项目 `chart-agent` 能力卡一致）。
- 图必须能回溯到材料：节点无 `evidence` 即视为未交付，门禁判 `CAPABILITY_MISSING`。
- 不使用网络、不调用 LLM。
- **禁止**在 Skill 里再实现一份渲染逻辑；版式变更只改 MCP Server。

## 可执行参考

`scripts/render.py` 是通过 MCP client 调用 `chart_render` 的示例，不是执行入口。
