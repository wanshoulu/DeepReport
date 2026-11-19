# DeepReport

> **DeepReport 是一套受控长报告编制系统**：先把材料落盘成带指纹的快照，再由配置驱动的报告画像组织编制，最后用**不依赖模型**的数值与引用双检做 fail-closed 门禁，让报告里每个数字都能追到来源页的原文摘录。

仓库、产品与系统三者现已统一命名为 **DeepReport**（[`wanshoulu/DeepReport`](https://github.com/wanshoulu/DeepReport)，原 `Report-Agent` 已由 GitHub 重定向）；历史文档中的 `Report Agent` / `W06` 为演变留痕，不再作为对外名称。

## 项目简介

**问题域。** 银行年报、投研、客户分析、技术方案这类长报告，交付标准不是"写得流畅"，而是**可溯源、可核验、可审计**：一个比率写错了，要能定位到是哪一页、哪一句支撑的；材料里没有的数字，不许由模型补出来。主流深度研究智能体擅长检索与长文生成，但把"引用是否真的支撑论断"交给模型自评，在强监管场景下不够。

**方案机制。** DeepReport 把编制拆成三段，核验独立于生成模型：

1. **落盘**：原始材料经 `/v1/documents/import` 注册进快照库，生成页级 `digest` 与 `sha256`；
2. **编制**：`report-profiles` 决定报告类型（任务模板、领域技能、指标字典、检查规则），检索与初稿复用 [openJiuwen-DeepSearch](https://github.com/openJiuwen-ai/deepsearch/blob/main/deepsearch/README.md)；六工位流水线产出结构化 `artifact`；
3. **门禁**：数值规则 N1–N7 与引用规则 C1–C2 做确定性检查，失败即阻断交付（定向修订一次，再失败转人工）。检查结论与批准记录都绑定工件指纹，改正文即全部失效。

**交付物。** 结构化 `artifact`（`metrics` / `sections` / `citations` / `tables`）、带核验附录的报告、`frontend/workbench.html` 工作台，以及可复现的评测脚本。

**状态。** `main` 是领域无关通用内核主线，`annual-report` 是冻结的银行年报基线。已跑通年报、投研投顾、客户分析三类配置；已具备深搜桥接、指标抽取、实时工位状态与双检闭环。**尚未完成** Java 编排层的 `MetricKey` 值对象化与 LLM 意图识别，逐条见 [`ROADMAP.md`](ROADMAP.md)。本系统保证的是"过程可回放、结论可核验、篡改可发现"，**不对材料源本身的真实性背书**——一致不等于正确。

## 名词表

| 名词 | 含义与出处 |
|---|---|
| **DeepReport** | 本系统产品名。Deep（深度研究）+ Report（受控报告）；命名上与检索内核 DeepSearch 呼应：DeepSearch 管检索，DeepReport 管编制与放行。 |
| **DeepSearch** | [openJiuwen-DeepSearch](https://github.com/openJiuwen-ai/deepsearch/blob/main/deepsearch/README.md)：官方定义为"知识增强、高性能、高精度的深度检索与研究引擎"，基于 [openJiuwen agent-core](https://www.openjiuwen.com/) 协调多 Agent 完成查询规划、信息收集、理解反思与报告生成；官方列出的首个应用场景是金融分析研报，支持"基于样例的报告生成"与"片段级结果溯源"。本项目复用其检索与初稿能力。 |
| **报告画像 / report profile** | 一类报告的领域资产集合：任务模板、领域技能、指标字典、检查规则，由 `report_type` 选中，配置见 `source/config/report-profiles/`。 |
| **快照 / snapshot** | 落盘的原始材料及其页索引。`live` 是用户导入资料形成的动态快照集；`cmb` / `icbc` / `ccb` 为冻结演示快照。 |
| **工件 / artifact** | 一次编制的结构化结果，含 `metrics` / `sections` / `citations` / `tables` / `charts`。 |
| **证据摘录 / evidence_excerpt** | 引用指向的原文片段，必须在来源页文本中**字面命中**，其 `sha256` 进引用三元组。区别于 DeepSearch 官方的"片段级溯源"（segment-level provenance）。 |
| **工件指纹** | `sha256(工件)`。检查结论与批准记录绑定它，正文改动即指纹失配。 |
| **双检** | NumericChecker（N1–N7 数值规则）+ CitationChecker（C1–C2 引用规则），确定性执行、不调用模型。 |
| **L2 语境绑定核验** | 引用支持度判定：数值前**最近主体词**的归属匹配，用于区分"营收 0.85%"与"不良率 0.85%"；主体词表由 profile 注入。 |
| **fail-closed 门禁** | 检查失败不是"建议面"，而是阻断交付：打回定向修订一次，再失败转人工（源自 W06 工作包的 GatekeepRail 改造）。 |
| **W06** | 上游 `agent-hub-alpha` 中"报告 Agent 与核验"工作包编号，仅见于 `reviews/` 历史文档。 |

## 机制清单

- **快照指纹绑定**：材料落盘即生成 `content_sha256` 与页级 `digest`，引用必须指向已注册信源页；
- **主体隔离**：`subject_hint` 显式传递当前编制主体，避免混合主体快照库中 A 银行的数字被 B 银行的报告引用；
- **派生值识别**：模型自行复算的百分比（官方 7.5% → 自算 7.52%）会被标为无法溯源，而非当作可引用事实；
- **推断标记器**：评价句式窗口 ∩ 材料零频词，按次自学习生成词表，标记 HIGH / LOW 两级无据定性论断，零领域硬编码；
- **配置驱动多类型**：新增报告类型 = 加一份 profile + 骨架 + `index.json` 登记，Java 编排层零改动；
- **深搜桥接**：非年报类型先调 DeepSearch `run_brief`，结果经 `snapshot_commit` 落盘后重新 scope，引擎不可达时降级到 `live` 资料；
- **实时工位上报**：`?async=1` 启动后台线程池，`/v1/pipeline/status` 增量上报六工位状态，前端按服务端权威 stage 名点亮。

## 实测数据

以下数字均为本项目自测，**测量口径与环境一并列出**，不是第三方基准：

| 结果 | 口径与环境 |
|---|---|
| 年报 21/21 节、33,097 字、100 表、双检 PASSED | 2026-11-19，`main` + `bank=live` + `annual_report` + `long_form`；106 页工行 2025 年报快照；指标 8/8（6 项真值 + 2 项诚实 `NOT_FOUND`） |
| 632 条引用 → SUPPORTED 227 / PARTIAL 404 / UNSUPPORTED **1** | 2026-11-18，Phase 1 适配层对 Phase 0 报告逐条判定；唯一 BLOCKING 是模型从外部知识带入、材料中不存在的"风险加权资产 282,699.48 亿" |
| 164,429 字符、632 个 `checked_citation`、409 行表格、约 25 分钟 | 2026-11-18，Phase 0 材料模式 E2E；109 页工行快照分 28 块 + 28 节模板，模型 `qwen3.7-flash` |
| 航天飞鸿投研 v5：30,281 字 / 47 标题 / 100 引用（72 放行 / 28 复核 / 0 编造），约 8 分钟 | 2026-11-18，5 块用户材料 + 8 节模板；门禁 PASSED，见 `artifacts/航天飞鸿投研报告.md` |
| 非预置主题实测：8 节仅 3 节有正文（5,393 字）、8 项指标全 `NOT_FOUND`、仅 1 条引用，双检仍 PASSED | 2026-11-19，`bank=live` + `research` + `long_form` + `subject_hint=金融银行风控技术方案`，耗时 293.0s、6/6 工位；这是一次**失败案例**，暴露门禁不校验完整性，完整取证见下方「已知限制」 |
| 15 例标注样本精确匹配 15/15，precision/recall/F1 = 1.0 | `benchmark/eval_checkers.py`，**15 例为本项目自构标注用例**，非公开基准；1 例语义等价诚实标注需模型层，结果见 `benchmark/results.json` |

同类系统的公开基准可用于对照，但**本项目尚未在公开基准上评测**：GPT Researcher 在 DeepResearchGym 的引用精度 85.36% / 报告 83.70% / 覆盖 64.67%，STORM 在 NAACL 2024 报告引用召回 84.8% / 精度 85.2%。逐项对比与源码级差距见 [`docs/opensource-comparison-and-plan.md`](docs/opensource-comparison-and-plan.md) 与 [`reviews/w06-competitive-analysis-vs-report-agents-1117.md`](reviews/w06-competitive-analysis-vs-report-agents-1117.md)。

## 按目标导航

| 想做什么 | 去哪 |
|---|---|
| 跑一次完整编制 | 下方「快速开始」 |
| 看工作台长什么样 | `frontend/workbench.html`（三页：报告编制 / 资产库 / 报告库） |
| 理解核验规则与已知边界 | [`reviews/generalization-and-l2-fix-1117.md`](reviews/generalization-and-l2-fix-1117.md)、`skills/verify_metrics_generic.py` |
| 新增一类报告 | `source/config/report-profiles/`，约定见 [`BRANCHES.md`](BRANCHES.md) |
| 看设计取舍与整改计划 | [`docs/opensource-comparison-and-plan.md`](docs/opensource-comparison-and-plan.md)、[`ROADMAP.md`](ROADMAP.md) |
| 复现 Phase 0 端到端验证 | [`docs/phase0-validation-report.md`](docs/phase0-validation-report.md)、[`docs/phase1-verification-adapter-design.md`](docs/phase1-verification-adapter-design.md) |
| 找 HTTP 接口 | `source/w06-annual-report-agent/` 内 `CheckerHttpServer` 路由表；前端调用示例见 `frontend/workbench.html` |

## 快速开始

系统以容器方式部署，核心是 `w06-checker`（Java HTTP 服务 + 工具网关 + 双检）与 DeepSearch 引擎。部署后用四行命令从"导材料"走到"出报告"：

```bash
curl http://<host>:18083/health
curl -X POST http://<host>:18083/v1/documents/import -H 'Content-Type: application/json' \
  -d '{"title":"监管原文","authority":"PRIMARY_OFFICIAL","text":"……原文……"}'
curl -X POST 'http://<host>:18083/v1/pipeline/run?async=1' -H 'Content-Type: application/json' \
  -d '{"bank":"live","mode":"long_form","report_type":"research","subject_hint":"研究标的"}'
curl 'http://<host>:18083/v1/pipeline/status?run_id=<上一步返回的 run_id>'
```

单独调用双检：`POST /v1/checks/numeric`、`POST /v1/checks/citation`（请求体为 artifact JSON）。完整 compose 见 `source/w06-annual-report-agent/deploy/compose.yaml`。

## 设计决策与代价

| 决策 | 换来什么 | 代价 |
|---|---|---|
| 核验做成生成之后的**独立门禁**，不放在生成模型内部 | 门禁结论不受生成模型自评影响，可独立复现 | 生成完才拦，错误报告已消耗算力；且无法自证材料源真伪 |
| 材料先落盘成带指纹的快照再编制 | 引用可逐字回查、篡改可发现 | 多一次导入与索引；混合主体库需要 `subject_hint` 显式指定主体，否则会串数据 |
| fail-closed：缺指标 / 空模板一律 FAILED | 不把"没查到"包装成"已核验" | 材料不全时无法交付，必须人工补料 |
| 复用 DeepSearch 而非自研检索 | 拿到模板抽取、混合检索、片段级溯源 | 依赖外部引擎版本与网络；搜索 key 未配置时联网检索为空，深搜桥接降级到已导入资料 |
| 领域知识只进配置与材料，代码只写语言级规则 | 换报告类型零代码改动 | Java 编排层仍有年报专用 `MetricKey` enum（61 处引用），值对象化尚未完成 |

## 已知限制

- **门禁只判编造、不判完整性（当前最严重）**：2026-11-19 用非预置主题「金融银行风控技术方案研究报告」实测，8 节里 5 节空白、8 项指标全 `NOT_FOUND`、仅 1 条引用，numeric 与 citation 双检仍返回 `PASSED`。`NOT_FOUND` 被 N1 豁免、空节不触发任何规则，因此一份实质为空的报告会被放行。需要补章节覆盖率 / 证据密度的 completeness 规则。
- **非预置报告类型静默回退**：`research` 骨架是投研八节，但指标字典仍是年报八项；`subject_hint` 只作用于指标抽取与深搜意图，不覆盖 `company` / `task_id` / 报告标题，实测把快照库名"W06 联网抓取快照库"当成了研究标的（正文称"W06银行"）。未登记类型应显式报错或要求先建 profile，而不是悄悄套用旧骨架。
- 已登记的报告类型只有 `annual_report` / `research` / `client_analysis`；技术方案、尽调、行业研究需先新增 profile。
- 任务类型识别目前是**词表匹配**，不是 LLM 意图识别；非年报类型的写作工位对空模板的 LLM 填充仍在补齐。
- 真实搜索 key 未配置（占位 key），联网检索不可用；当前端到端验证全部走"材料模式"。
- 语义等价（如 ROE ↔ 净资产收益率）需 NLI/语义蕴含模型，确定性层一律保守降级人工。
- 双检覆盖正文指标与引用，**不覆盖表格单元格**。
- 取数与分析偏浅：无付费/登录态信源接入，`table_extract` 为单页轻量实现。
- 交付形态仅 HTML/JSON，无 PDF/Word/PPT 导出。
- 工程生产化缺口：未接 PostgreSQL 权威账本、执行身份 `o/a/b`、角色挂 RDC/Gateway，详见 `reviews/w06-gap-vs-github-1117.md`。

完整不足清单与逐条验收标准见 [`ROADMAP.md`](ROADMAP.md)。

## 安全须知

> ⚠️ 现有验证实例以公网裸 IP 直连、未启用鉴权（`SKIP_AUTH=true`），**仅用于开发验证**。不要导入真实客户数据、未公开财务数据或受监管材料。部署到生产前必须配置安全组白名单、鉴权与 TLS；导入材料的 `authority` 分级只是引用核验的信用标注，不构成访问控制。

## 分支与目录

| 分支 | 定位 |
|---|---|
| `main` | 通用内核主线，持续迭代 |
| `annual-report` | 银行年报冻结基线（含工行 2025 年报成品），用于等价性回归 |

分工与协作约定见 [`BRANCHES.md`](BRANCHES.md)。主要目录：`source/`（Java 工程与配置）、`skills/`（核验与推断标记脚本）、`frontend/`（工作台）、`benchmark/`（评测）、`docs/`（设计与验证）、`reviews/`（对标与历史评审）、`artifacts/`（报告成品）。

> 本仓由 `agent-hub-alpha` 的 W06 工作包演化而来，早期评审请求与架构审视保留在 `reviews/` 与 `REVIEW-REQUEST.md` 供追溯。
