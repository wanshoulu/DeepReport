# 开源报告智能体横向比较与整改方案

> 日期：2026-11-17　性质：广泛调研 → 综合比较 → 整改方案
>
> 调研方式：官方 GitHub/文档一手抓取 + 第三方基准数据交叉验证。样本：GPT Researcher、STORM、openJiuwen DeepSearch、Tongyi DeepResearch、DeerFlow、Local Deep Research，共 6 个主样本。

## 一、调研样本总览

| 项目 | 出品方 | Star | 许可证 | 定位 | 活跃度 |
|---|---|---|---|---|---|
| **GPT Researcher** | Tavily 团队 | 30.8k | Apache 2.0 | 自主深度研究 agent，web+本地，带引用长报告 | 高（2026-11 仍活跃提交，3355 commits） |
| **STORM / Co-STORM** | Stanford | 28k | MIT | 多视角提问 + 模拟对话 → Wikipedia 式带引用长文 | 中（研究原型，v1.1.0 2025-01） |
| **openJiuwen DeepSearch** | 华为系开源 | 新项目 | Apache 2.0 | 知识增强深度研究引擎，模板驱动报告，chunk 级引用 | 新（2026 年起，依托 openJiuwen 生态 600 万下载） |
| **Tongyi DeepResearch** | 阿里通义 | 18.5k | Apache 2.0 | 30.5B Agentic 模型本体（非框架），HLE 32.9 超 OpenAI | 高 |
| **DeerFlow** | 字节跳动 | — | — | SuperAgent 编排框架，Researcher/Coder/Reporter 分工 | 中 |
| **Local Deep Research** | 开源社区 | — | — | 完全本地部署，隐私优先，10+ 学术源 | 中 |

## 二、关键基准数据（第三方可交叉验证）

| 系统 | 引用质量 | 报告质量 | 覆盖率 | 来源 |
|---|---|---|---|---|
| **GPT Researcher** | **85.36%**（精度） | **83.70%** | 64.67% | CMU DeepResearchGym，开源第一 |
| **STORM** | 85.2% 精度 / 84.8% 召回 | — | — | 同行评审（NAACL 2024） |
| **Tongyi DeepResearch** | HLE 32.9 / GAIA 70.9 | — | — | 官方报告 |
| **我们（Report Agent）** | 无公开基准 | — | — | 仅内部 15 例 benchmark |

> 残酷事实：我们引以为傲的"引用核验"，在公开基准上是空白——无法与任何一家对比。

## 三、六维度能力对比（★=弱 → ★★★★★=强）

| 维度 | GPT Researcher | STORM | openJiuwen DeepSearch | Tongyi DR | DeerFlow | 我们 |
|---|---|---|---|---|---|---|
| **报告模板/结构定制** | ★★（report type 有限） | ★★★（大纲可控） | ★★★★★（`generate_template` 从样本抽取，Markdown/Word/PDF） | ★（模型内生） | ★★ | ★★★（report-profiles，但 Java 层未消费） |
| **深度搜索** | ★★★★（树状递归深挖，DeepResearchGym 覆盖率第一梯队） | ★★★（多视角提问式） | ★★★★（混合检索：关键词/向量/**图** + 10 种 web 引擎含博查） | ★★★★★（模型级搜索能力） | ★★★ | ★（固定快照 + 通用 web 工具） |
| **引用溯源** | ★★★★（inline 引用，基准第一） | ★★★★（同行评审级） | ★★★★（chunk 级 + 置信度 + 可预览） | ★★★ | ★★★ | ★★★★★（字面命中+sha256+指纹绑定，**但无公开基准**） |
| **确定性核验（数值复算/门禁）** | ★（无） | ★（无） | ★★（置信度评估，非确定性） | ★（无） | ★（无） | ★★★★★（N1-N7/C1-C2/fail-closed，**独有**） |
| **中文/国内生态** | ★★★（支持但非重点） | ★★（英文为主） | ★★★★★（博查/Qwen/GLM/DeepSeek 官方验证，国内网络适配） | ★★★★★ | ★★★★ | ★★★★（已跑通中文年报） |
| **部署/集成成熟度** | ★★★★★（Docker/MCP/FastAPI/LangGraph，最成熟） | ★★★（pip 包 + streamlit demo） | ★★★（Docker Compose 三模式，较新） | ★★★（模型权重部署） | ★★★★ | ★★（自研容器，无标准接口） |

## 四、综合判断

### 4.1 各项目对我们的价值

| 项目 | 结论 | 理由 |
|---|---|---|
| **openJiuwen DeepSearch** | **主引擎，采用** | 唯一同时满足：模板从样本抽取（直接解决泛化+模板问题）、图检索+博查（中文金融数据源）、chunk 级引用、HITL、国内模型官方验证 |
| **GPT Researcher** | **借鉴对象** | 树状递归深挖（DeepResearchGym 覆盖率第一）值得学习；其 MCP server 形态是我们核验能力产品化的参照 |
| **STORM** | **借鉴对象** | 多视角提问（Perspective-Guided Question Asking）可提升我们报告的提问质量；Co-STORM 的 mind map 组织方式可用于客户分析报告 |
| **Tongyi DeepResearch** | **模型层备选** | 它是模型不是框架，不能拿来搭系统；但 HLE 32.9 说明 Agentic 模型趋势，未来可作 writer 候选 |
| **DeerFlow** | **不采用** | 编排框架与我们需求重叠度低，openJiuwen agent-core 已覆盖 |
| **Local Deep Research** | **不采用** | 隐私优先场景，与我们的云端核验+审批流冲突 |

### 4.2 决策：为什么选 openJiuwen DeepSearch 做主引擎

不是因为它最好，而是因为**它与我们需求的匹配度最高**：

1. **模板能力是刚需**：我们要"各种报告都能写"，`generate_template` 从样本文档抽模板是六家中唯一原生支持的（GPT Researcher 的 report type 只是预定义几种；STORM 只控大纲不控全文结构）；
2. **中文金融场景**：博查搜索、Qwen/GLM 官方验证、国内网络适配（jina 设 `s.jinaai.cn`），其他项目都不具备；
3. **chunk 级引用 + 置信度**：与我们"引用字面核验"天然互补——它做生成时的引用绑定，我们做事后的确定性复核；
4. **openJiuwen 生态背书**：600 万下载、华为系维护，不是个人项目。

**风险（诚实）**：DeepSearch 是新项目，成熟度和社区不如 GPT Researcher（30.8k star、3355 commits）；如果 S0 验证发现质量不达标，备选方案是 GPT Researcher 做引擎（放弃模板从样本抽取，改用我们自己的 report-profiles 转 GPT Researcher 的自定义 report format）。

### 4.3 我们真正独有、必须守住的东西

对标六家后确认，**没有一家做确定性数值复算 + fail-closed 门禁 + 指纹绑定批准**：

- GPT Researcher：引用是生成的，无事后复核；
- STORM：明确自述"不能产出可发布文章"；
- openJiuwen DeepSearch：置信度是模型评估，不是确定性复算；
- 其他：无。

这是我们的差异化定位——**别人负责"写得出"，我们负责"写得对"**。

## 五、整改方案（综合比较后确定）

### 总原则

> **引擎换openJiuwen DeepSearch，核验留自己，模板用官方格式，搜索接博查+本地知识库，基准对齐 DeepResearchGym。**

### Phase 0：并行验证（1-2 周，不动现有系统）

| 任务 | 内容 | 验收 |
|---|---|---|
| 0.1 起 DeepSearch 最小环境 | Docker Compose 最小模式（sqlite+in_memory），配 Qwen3.7-Max + 博查 | 服务健康，能跑通一次 query-only 报告 |
| 0.2 同材料对比 | 用工行年报同一批材料，DeepSearch vs 我们现版各出一份 | 对比报告质量、引用可溯源性、表格丰富度 |
| 0.3 模板验证 | 把年报 28 节骨架转官方 Markdown 模板，`generate_template` 注册 | 按模板生成的报告结构与骨架一致 |

**决策点**：0.2 对比如果 DeepSearch 报告质量 < 我们现版的 70%，切换到备选方案（GPT Researcher 引擎），不硬撑。

### Phase 1：核验门禁对接（2-3 周）

| 任务 | 内容 | 验收 |
|---|---|---|
| 1.1 artifact 契约泛化 | 把 `AnnualReportArtifacts` 泛化为 `ReportArtifact`（加 `reportType`），映射 DeepSearch 输出 | DeepSearch 报告能转成我们的 artifact 结构 |
| 1.2 核验器适配 | N1-N7/C1-C2 核验器读 DeepSearch 的 chunk 级引用格式（来源 URL/chunk ID 替代页码） | 工行材料的数字和引用在 DeepSearch 输出上照样核验 |
| 1.3 fail-closed 门禁串联 | DeepSearch 生成 → 核验 → 通过才放行 → 批准绑定指纹 | 负例（错单位/伪造数字/语境错配）照样 BLOCKING |

### Phase 2：深度搜索强化（2-3 周，与 Phase 1 并行）

| 任务 | 内容 | 验收 |
|---|---|---|
| 2.1 博查 + 本地知识库 | 配置博查搜索源；工行/招行/建行年报语料离线构建（向量+关键词索引） | 关键指标由检索自动补入，带可追溯引用 |
| 2.2 来源白名单 | 我们的"来源可信度分级"做成 DeepSearch 检索约束 | 引用带来源等级，低等级来源标记警告 |
| 2.3 借鉴 GPT Researcher 树状深挖 | 评估 DeepSearch 的 `execution_method=hybrid` 模式是否覆盖树状探索 | 对比纯 parallel vs hybrid 的覆盖率 |

### Phase 3：多报告类型泛化（2-3 周）

| 任务 | 内容 | 验收 |
|---|---|---|
| 3.1 三类模板 | 年报/投研/客户分析各出一份官方 Markdown 模板 | 三类报告按各自模板生成，结构正确 |
| 3.2 MetricKey 值对象化 | 完成之前的 P0-2：Java/Python 双路径合并 | 非年报指标在统一核验器上通过 |
| 3.3 端到端演示 | 投研报告从"给一份样例研报"到"按模板生成+核验通过" | 全链路演示成功 |

### Phase 4：基准对齐（1-2 周）

| 任务 | 内容 | 验收 |
|---|---|---|
| 4.1 扩充 benchmark | 从 15 例扩到 ≥100 例，覆盖 N1-N7/C1-C2 | precision/recall/F1 可复现 |
| 4.2 对齐 DeepResearchGym | 评估我们的核验层在 DeepResearchGym 数据集上的表现 | 与 GPT Researcher 85.36% 有可比口径 |
| 4.3 核验能力 MCP 化 | 把核验器封装为 MCP server | 外部 agent 可调用，输出可机读 PASS/FAIL |

### Phase 5：收敛与下线（1 周）

- 下线自研 Java 编排/工具网关/writer 调用链；
- 保留：核验内核 + 领域资产 + openJiuwen 栈；
- 用 `annual-report` 分支工行成品做最终等价性回归。

### 时间线与里程碑

```
Phase 0 (验证) ──→ 决策点 ──→ Phase 1 (核验对接) ─┐
                                              ├─→ Phase 3 (泛化) ─→ Phase 4 (基准) ─→ Phase 5 (收敛)
                    Phase 2 (搜索强化) ────────┘
```

- 总周期：约 8-12 周；
- 每阶段结束用工行成品做等价性回归（护城河不丢）；
- Phase 0 是硬决策点——DeepSearch 不行就切 GPT Researcher，不恋战。

## 六、模型选型（维持之前结论）

腾讯模型不采用（不在 openJiuwen 官方验证列表）。主配置：

| 槽位 | 模型 |
|---|---|
| general（必填） | Qwen3.7-Max |
| plan_understanding | Qwen3.7-Max 或 GLM-5.3 |
| info_collecting | Qwen3.8-Flash |
| writing_checking | Qwen3.7-Max |
| vlm_chart_generating | qwen3-vl-plus |

## 七、数据来源

- GPT Researcher：https://github.com/assafelovic/gpt-researcher（30.8k star，Apache 2.0，DeepResearchGym 第一）
- STORM：https://github.com/stanford-oval/storm（28k star，MIT，NAACL 2024，84.8%/85.2% 同行评审）
- openJiuwen DeepSearch：https://github.com/openJiuwen-ai/deepsearch（模板/混合检索/chunk 引用/HITL/模型配置）
- Tongyi DeepResearch：https://github.com/Tongyi-AILab/DeepResearch（30.5B，HLE 32.9）
- DeerFlow：https://github.com/bytedance/deer-flow（字节出品）
- 基准数据交叉验证：CMU DeepResearchGym（2025-05）、skillpack.co 独立评测、quidproquo.cc 开源工具全景（2026-09）

> 所有链接于 2026-11-17 实际抓取核对。
