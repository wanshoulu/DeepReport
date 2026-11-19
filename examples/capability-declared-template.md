---
report_type: risk_tech_solution
display_name: 金融银行风控技术方案研究报告
capabilities:
  - id: cap.capital_ratio_table
    kind: table
    spec: 资本充足率监管红线对比表（核心一级资本充足率/一级资本充足率/资本充足率/储备资本/逆周期资本）
    required: true
    source_of_truth: materials
  - id: cap.reg_clause_matrix
    kind: table
    spec: 监管条款与技术要求对照矩阵（条款号/要求摘要/技术落点/来源文件）
    required: true
    source_of_truth: materials
  - id: cap.control_architecture_chart
    kind: chart
    chart_type: flow
    spec: 风控系统分层架构图（数据层/模型层/决策层/审计层），节点须来自材料原文
    required: true
    source_of_truth: materials
---

# 金融银行风控技术方案研究报告

> 本模板只做两件事：声明章节意图与声明所需能力（上方 `capabilities`）。
> 能力怎么产出由 Agent 查 `source/config/capability-routes.json` 后路由到 Skill / MCP 决定，模板不写生成逻辑。

## 一、监管要求与合规基线
以导入的监管原文为准，逐条对应技术要求；资本充足率红线、模型风险管理、数据治理均须引用原文条款。

## 二、总体架构
分层描述数据层、模型层、决策层、审计层，架构图节点必须能回溯到材料原文。

## 三、关键技术与选型
规则引擎、模型平台、实时计算、图计算、隐私计算等，说明适用条件与代价。

## 四、数据治理与模型风险
覆盖模型开发、验证、部署、评价、退出全流程；可解释性、可审计性、算法歧视防范。

## 五、实施路径与验收口径
分阶段里程碑、量化验收指标及其测量口径；无出处的数字不得作为选型依据。

## 六、证据空白与限制
列出材料未覆盖、无法核实的事项。
