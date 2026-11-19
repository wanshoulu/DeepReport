---
name: table-composition
description: 从原始材料文本中确定性抽取数值矩阵与条款条目，生成带 capability_id 与来源指纹的结构化表格。当报告模板声明 kind=table、且 table_extract 对条文类材料返回 0 时使用；不调用模型，不编造数字。
entry: scripts/compose.py
---

# table-composition

## 何时使用

- 模板 `capabilities` 中出现 `kind: table`。
- MCP 工具 `table_extract` 已探测但返回 `table_count: 0`（它只识别被 PDF 拍平的财报表格，见 `source/config/tools.json` 描述）。

## 方法（确定性，无模型）

1. **数值矩阵表**：逐份读材料文本，按 `指标词 + 数值 + 条款/来源` 三元组抽取。指标词来自 capability `spec` 中列出的名称；数值只取原文中出现的字面值，单位随原文保留；每个单元格记录 `source_file` 与命中原文片段。
2. **条款对照表**：识别中文条款号（如 `（十三）`、`第二十六条`），取该条首句作为要求摘要，再从同条正文中抽取技术落点关键词，生成一行。
3. 任何一条抽不到时返回空行并附 `extraction_miss`，**不补全、不推测**；由门禁判 `CAPABILITY_MISSING`。

## 输入输出契约

输入（stdin JSON）：

```json
{"capability": {"id": "...", "kind": "table", "spec": "..."}, "materials": [{"file": "01.txt", "text": "..."}]}
```

输出（stdout JSON）：

```json
{"capability_id": "...", "headers": [], "rows": [[]], "sources": [], "extraction_rule": "...", "extraction_miss": []}
```

## 纪律

- 数字照抄原文，派生值必须由脚本复算并附 `formula`（沿用项目既有纪律）。
- 输出必须带 `capability_id`，否则门禁无法做声明-交付对账。
- 不使用网络、不调用 LLM、不写文件（由编排层统一落盘）。
