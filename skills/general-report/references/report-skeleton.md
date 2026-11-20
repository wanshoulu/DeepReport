# 通用报告骨架方法（report-skeleton · 通用域）

> 与 `skills/annual-report/references/report-skeleton.md` 同一方法论，适用对象不同：
> 年报域用 report-structure.json 的顶层 `sections`/`section_order`/`static_bodies`/`banks`；
> 通用域（调研/客户分析/通用深度报告等）用同文件的 `report_types.general`。

## 1. 唯一事实源

`deploy/report-structure.json` 是骨架的唯一数据配置：

- `report_types.general.sections` — 各节 `role`（static/analysis）、`agent`（分析洞察责任 agent）、`label`；
- `report_types.general.section_order` — 节序（`G-1` → `G-7`）；
- `report_types.general.static_bodies` — 静态节正文模板（含 `{company}/{snapshot_version}/{primary_pages}/{comparison_pages}` 占位符，编排器在合成 base 工件时填充）；
- `report_types.general.analysis_body` — 分析节 brief_body 模板（brief 模式直接使用，长文模式由 writer-agent 重写）。

**新增一种报告类型 = 在 `report_types` 下加一组骨架，不改任何代码**；编排器按请求的 `report_type` 参数选骨架，未知类型返回 400 与可用类型清单。

## 2. 角色与节

通用域骨架（G-1 → G-7）：

| 节 | role | agent | 说明 |
|---|---|---|---|
| G-1 执行摘要 | analysis | editor-in-chief | 编排器按分析节成稿汇总；长文模式由 writer-agent 重写 |
| G-2 背景与目标 | static | — | static_bodies 模板 |
| G-3 资料与方法 | static | — | static_bodies 模板（信源页清单占位符） |
| G-4 核心发现 | analysis | finance-analyst | 资料驱动的分析节 |
| G-5 风险与挑战 | analysis | risk-analyst | 资料驱动的分析节 |
| G-6 结论与建议 | analysis | editor-in-chief | 主编收口 |
| G-7 数据来源与使用限制 | static | — | static_bodies 模板 |

## 3. 与指标的关系

通用域不强制 8 项标准指标：base 工件 `metrics` 允许为空（数值核验器 0.2.2+ 对空/null metrics 跳过完整性检查）。若调用方的资料含可结构化指标，由编排器按年报域同一纪律（单位白名单/页内字面命中/变动口径）注入与核验。

## 4. 修改纪律

- 只改 `report-structure.json`，不改 Java 代码；
- 静态节模板中的占位符必须来自 `placeholders` 白名单，编排器填充失败时保留原文占位符并如实展示；
- 节序变更同步 `section_order`；新节必须同时给 `role`/`agent`/`label`，analysis 节必须给 `brief_body` 模板。
