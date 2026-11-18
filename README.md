# Report Agent（通用长报告智能体）

> **产品名 DeepReport · 数据可溯、数字可核验的长报告智能体——一个通用内核，配一组可插拔的领域资产。**

## 项目简介

Report Agent 面向**强监管、强溯源的长报告编制**：从落盘的原始材料出发，经取数、指标抽取、确定性核验、结构化成稿、具名审批，产出**可溯源、可核验、可审计**的完整报告。

它的目标不是「只会写年报」，而是**一套内核写所有长报告**——银行年报、投研投顾、客户分析、尽调、行业研究……报告类型不再是写死在代码里的分支，而是通过 `reportType` 切换的一组配置。

**架构主张：一个通用内核 + 四类可插拔领域资产。** 会随报告类型变化的东西全部外置为配置，内核不认识任何具体行业：

| 可插拔资产 | 抽象什么 | 落点 |
|---|---|---|
| 任务模板 | 产出物形状 | `report-profiles/*/task_book` + `skeletons/*.json` |
| 领域技能 | 专业流程/术语 | `report-profiles/*/skill_set` |
| 指标字典 | 数字的语义 | `report-profiles/metrics/*.json` |
| 检查规则 | 什么算合格 | N1–N7 / C1–C2 + `verify_metrics_generic.py` |

内置三种画像：`annual_report`（银行年报，向后兼容基线）、`research`（投研投顾）、`client_analysis`（客户分析）。**新增报告类型 = 加一份 profile 配置，核心代码零改动。** 设计与验证见 [`reviews/generalization-and-l2-fix-1117.md`](reviews/generalization-and-l2-fix-1117.md)。

## 它解决的三个 harder 问题

- **决策可回放**：每个数字、每条论断都能追到来源文件的具体页与原文摘录，且摘录与指纹字面绑定；
- **执行可核验**：数值（N1–N7）与引用（C1–C2）两道**不依赖模型**的确定性检查，失败即阻断交付；
- **结果可审计**：批准人对工件指纹签名，工件一旦被改，旧检查与旧批准同时失效，必须重查重批。

一句话边界：**一致不等于正确，重复得到错误答案也不符合目标**——本实现保证的是「过程可回放、结论可核验、篡改可发现」，不对材料源本身的真实性背书。

## 分支策略

| 分支 | 定位 | 状态 |
|---|---|---|
| **`main`** | 通用写报告智能体（本分支，主线） | 持续迭代，目标是领域无关内核 |
| **`annual-report`** | 银行年报的完整可运行实现 | 冻结基线，含工行 2025 年报成品，供对照/演示 |

`main` 沉淀领域无关的核验内核与多类型配置；`annual-report` 保留「年报这一类报告完整跑通」的参考实现与真实成品。泛化改造后可用它做回归对照。详见 [`BRANCHES.md`](BRANCHES.md)。

**当前进度**：通用内核已在 `main` 完成第一阶段——L2 语境绑定核验、报告类型配置层、配置驱动的通用数值核验器、可复现评测基准均已就位；年报这一类型已端到端跑通（`annual-report` 分支）。**尚未完成**领域无关的 Java 编排内核（任务书/骨架按类型切换、`MetricKey` 值对象化）——这是 [`ROADMAP.md`](ROADMAP.md) 的 P0 项。

## 名词（首次出现就地解释）

| 名词 | 含义 |
|---|---|
| **工件 / artifact** | 一次编制的结构化结果，含 `metrics` / `sections` / `citations` / `tables` / `charts` |
| **报告画像 / report profile** | 一类报告的领域资产集合：任务模板、技能、指标字典、检查规则；由 `reportType` 选中 |
| **双检** | NumericChecker（N1–N7 数值规则）+ CitationChecker（C1–C2 引用规则），确定性、不靠模型 |
| **writer** | openJiuwen deep agent，经 `skill-app-agent` 容器调用，负责长文成稿 |
| **fail-closed 门禁** | 检查失败不是「建议面」，而是阻断交付：打回定向修订一次，再失败转人工 |
| **工件指纹** | `sha256(工件)`；检查结论与批准记录都绑定它，改正文即指纹失配 |
| **证据摘录 / evidence_excerpt** | 引用指向的原文片段，必须在来源页文本中字面命中，其 sha256 进引用三元组 |
| **工具** | 系统对外暴露的能力：`web_search` / `web_fetch` / `pdf_extract` / `snapshot_commit` / `table_extract` / `table_analyze` |

## 核验能力（可复现评测）

- **L2 引用支持度**：语境绑定判定（数值前最近主体词匹配），修复「营收 0.85%」误命中「不良率 0.85%」的语境错配；主体词表从报告类型配置注入，无银行名词硬编码。
- **评测基准**：`python benchmark/eval_checkers.py` —— 15 例标注样本，精确匹配 15/15，precision/recall/F1 = 1.0，误报率/漏报率 = 0（明确标注用例上）；1 例语义等价诚实标注需模型层。结果见 `benchmark/results.json`。
- **通用数值核验**：`skills/verify_metrics_generic.py` 从指标字典读规则，同一套代码核验投研与客户分析指标；负例（错单位/伪造数字）照样 BLOCKING。

## 与同类产品的定位差异

市面主流报告智能体（OpenAI Deep Research / Perplexity / Manus / Hebbia / STORM / GPT Researcher）各有侧重，但没有一家同时做到「长文 + 句级可溯 + 失败阻断」。本项目的取舍：

- **强项**：引用字面核验 + 语境绑定 + 指纹绑定、fail-closed 门禁、长文交付闭环、配置驱动的多报告类型；
- **已知差距**：数据源广度（无付费/登录态源接入）、数据汇总分析深度（表格抽取为轻量实现）、交付形态（仅 HTML/JSON，无 PDF/Word/PPT）、语义等价判定需模型层。

逐维度、带来源的对比见 [`reviews/w06-competitive-analysis-vs-report-agents-1117.md`](reviews/w06-competitive-analysis-vs-report-agents-1117.md)（含第 7 节代码级深度差距）。

## 目录结构

| 目录 | 内容 |
|---|---|
| `source/w06-annual-report-agent/` | Maven 工程：`agent-orchestrator` / `agents` / `checker` 三模块，41 个 Java 文件（年报参考实现，正向领域无关内核重构） |
| `source/config/` | `tools.json`、`report-structure.json`、`report-profiles/`（三种报告类型画像 + 指标字典 + 骨架） |
| `skills/` | `annual-report`、`orchestrator-skill`、`verify_metrics_generic.py`（通用数值核验） |
| `frontend/workbench.html` | DeepReport 工作台：报告编制 / 资产库 / 报告库三页 |
| `benchmark/` | `eval_checkers.py`（评测基准）、`results.json`、`fixtures/`（投研/客户分析样本） |
| `reviews/` | 架构全景、对标调研、代码级差距、泛化与 L2 修复等 |
| `artifacts/` | 工行 2025 年报成品 HTML + `gen_report_v2.py` 渲染脚本（完整版在 `annual-report` 分支） |
| `ROADMAP.md` | 通用化的分阶段迭代清单（现状 / 目标 / 验收标准 / 证据） |
| `BRANCHES.md` | 分支分工与协作约定 |

## 运行方式（最小，基于年报参考实现）

系统以容器方式部署，核心是 `w06-checker`（Java HTTP 服务 + 工具网关 + 核验器）与 `skill-app-agent`（writer）两个容器。

```bash
# 构建（见 source/w06-annual-report-agent/deploy/checker.Dockerfile + compose.yaml）
docker compose -f source/w06-annual-report-agent/deploy/compose.yaml up -d

# 健康
curl http://<host>:18083/health

# 跑一次编制（brief 快验 / long_form 完整长文）
curl -X POST http://<host>:18083/v1/pipeline/run \
  -H 'Content-Type: application/json' \
  -d '{"bank":"icbc","mode":"brief"}'

# 直接调数据工具
curl -X POST http://<host>:18083/v1/tools/call \
  -H 'Content-Type: application/json' \
  -d '{"tool_name":"table_extract","arguments":{"bank":"live"}}'
```

核验与批准：

```bash
curl -X POST http://<host>:18083/v1/checks/numeric  -H 'Content-Type: application/json' -d '<artifact JSON>'
curl -X POST http://<host>:18083/v1/checks/citation -H 'Content-Type: application/json' -d '<artifact JSON>'
curl -X POST 'http://<host>:18083/v1/approvals?approver=Audit' -H 'Content-Type: application/json' -d '<artifact JSON>'
```

## 已知限制

完整的不足清单与迭代计划见 [`ROADMAP.md`](ROADMAP.md)。当前最关键的几条：

- **Java 编排层仍年报专用**：`buildTaskBook()` 未消费 `report-profiles`，`MetricKey` 仍是编译期 enum（61 处引用），非年报数值核验暂走 Python 通用脚本；
- **语义等价未解决**：ROE ↔ 净资产收益率等需 NLI/语义蕴含模型，确定性层一律保守降级人工；
- **表格不进双检**：数值/引用检查覆盖正文指标与引用，不覆盖表格单元格；
- **取数与分析仍浅**：无付费/登录态源白名单，`table_extract` 为单页轻量实现；
- **交付形态单一**：仅 HTML/JSON，无 PDF/Word/PPT 导出；
- **工程生产化缺口**：未接 PostgreSQL 权威账本、执行身份 `o/a/b`、角色挂 RDC/Gateway，详见 `reviews/w06-gap-vs-github-1117.md`。

> 本仓由 W06 工作包演化而来，早期评审请求与架构审视保留在 `reviews/` 与 `REVIEW-REQUEST.md` 供追溯。
