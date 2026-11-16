# Agent Division（多 Agent 六工位分工）

受控生成流水线按职责切分为六个工位。工位划分、输入、输出全部由**通用数据**驱动（任务/工件/骨架配置），服务端与前端不含任何机构特判。

## 工位与职责

| # | 工位 | Agent | 输入 | 输出 |
|---|---|---|---|---|
| 1 | 查数 | material-agent | 冻结快照 manifest（信源、页清单、指标索引） | 信源清单 + 页数 + 指标索引计数 + 释义计数；缺材料如实报告 |
| 2 | 分析 | finance-analyst / risk-analyst / capital-analyst（按骨架配置 sections[].agent 分派） | 指标索引 + 快照页原文 | 逐指标洞察：值 + 单位 + 同比变动（口径按 delta_unit：PERCENT→%、PERCENTAGE_POINT→个百分点、BASIS_POINT→个基点）+ pie 结构占比 |
| 3 | 图表 | chart-agent | 分析阶段洞察 + 图表数据文件 | 逐图表说明：类型、挂载节（section_id）、标题、数据系列 |
| 4 | 写作 | writing（主编笔，骨架配置驱动） | 分析/图表产物 + report-structure.json | 按 section_order 逐节：标题（banks.<bank>.titles）、责任 agent、字数、claim 数；静态节按 static_bodies 模板 |
| 5 | 核验 | numeric-checker + citation-checker | 成稿工件 | N1-N7 数值核验 + C1-C2 引用核验结果（确定性，无 LLM） |
| 6 | 主编 | editor-in-chief | 五阶段汇总 | 交付结论 + 批准指引（批准绑定工件内容指纹 sha256，具名留痕） |

## 分派纪律

- **分派来自配置**：分析工位按 `report-structure.json` 的 `sections[sid].agent` 把每节派给对应分析师（财务/风险/资本），不在代码里写死「第几节归谁」。
- **工位文案来自运行时**：前端逐工位播报的内容取自 pipeline 响应的 `stages[]`（headline/details/data），前端只渲染不编造。
- **单指标洞察一句话**：分析输出面向下游写作可直接引用，不产生自由发挥的叙述。
- **核验先于主编**：主编只汇总已核验事实；核验失败走打回/人工路径，主编不修饰失败结论。

## 与骨架/核验的关系

六工位是**流程切分**，骨架（report-skeleton.md）是**产物结构**，核验协议（SKILL.md「核验协议」节）是**质量门禁**。三者互相独立：换机构改配置、换流程加工位、加指标改字典，互不牵连。
