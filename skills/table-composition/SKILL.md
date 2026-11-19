---
name: table-composition
description: 成表能力的方法论：判断何时该出表、出哪种表、参数怎么组织、探测工具返回空时如何降级。执行已上移为标准 MCP 工具 table_compose（tools/list 可发现、tools/call 可调用），本 Skill 不执行、不产出工件。
mcp_tool: table_compose
layer: methodology
---

# table-composition（方法论）

> **分层变更（v0.2）**：确定性抽取逻辑已上移到 `mcp/capability_server.py` 的 `table_compose` 工具。
> 本 Skill 只回答「何时用、用哪个工具、参数怎么组织、失败怎么降级」，**不再声明 `entry`，不执行脚本**。
> 依据：MCP 工具是 model-controlled 的原子执行单元；Skill 是指令与方法（Anthropic Agent Skills 的 progressive disclosure 第一级）。

## 何时使用

1. 模板 `capabilities` 中出现 `kind: table`。
2. Agent 先 `tools/list` 发现 `table_compose`，再按路由表 `source/config/capability-routes.json` 的 `table.mcp_tool` 绑定。
3. 若路由表配了 `probe_tools`（当前为生产网关的 `table_extract`），先探测：它只识别被 PDF 拍平的财报表格，对监管条文返回 `table_count=0` 时，Agent **依据工具真实返回**改调 `table_compose`，不是静默跳过。

## 方法（由 MCP 工具执行，此处只定义判据）

1. **指标数值矩阵**：`spec` 不含「条款 / 矩阵」时走这条。指标词 + `不得低于` / `要求为` + 字面百分比；数字照抄原文，不做换算，附原文句子摘录与来源文件 sha256 指纹。
2. **条款对照矩阵**：`spec` 含「条款 / 矩阵」时走这条。中文条款号切段 → 首句摘要 → 领域关键词命中。
3. 抽不到留空并记 `extraction_miss`，**不补全、不推测**；由门禁判 `CAPABILITY_MISSING`。

## 工具契约（唯一事实源：`mcp/tools.json`）

调用：

```json
{"name": "table_compose", "arguments": {"capability": {"id": "...", "spec": "..."}, "materials": [{"file": "01.txt", "text": "..."}]}}
```

返回：`capability_id` / `headers` / `rows` / `sources` / `extraction_rule` / `extraction_miss`。
`materials` 为空时工具返回 `error`，拒绝产出空表壳。

## 纪律

- 数字照抄原文，派生值必须由脚本复算并附 `formula`（沿用项目既有纪律）。
- 输出必须带 `capability_id`，否则门禁无法做声明-交付对账。
- 不使用网络、不调用 LLM、不写文件（由编排层统一落盘）。
- **禁止**在 Skill 里再实现一份抽取逻辑；规则变更只改 MCP Server。

## 可执行参考

`scripts/compose.py` 是通过 MCP client 调用 `table_compose` 的示例，不是执行入口。
