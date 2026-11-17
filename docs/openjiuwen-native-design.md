# 基于 openJiuwen 的通用长报告智能体设计

> 日期：2026-11-17　性质：架构设计（回答「基于 openJiuwen 该怎么设计 / 要不要模板 / 要不要强化深度搜索」）
>
> 本文先纠正一个方法论错误，再给出基于**官方一手资料**的设计结论。所有关键判断都标了来源，不凭记忆。

## 0. 先纠正：之前答偏了

之前我做的是「比较题」——拿我们系统和 OpenAI Deep Research / Hebbia / Manus 比能力差距。你要的是「设计题」——**基于 openJiuwen 这个框架，通用报告智能体到底该怎么搭**。这是两个问题，我之前用错了力气。

调研后还发现一个更关键的事实，它直接改变了设计结论（见第 2 节）。

## 1. 名词表（首次出现就地解释）

| 名词 | 含义 | 来源 |
|---|---|---|
| **openJiuwen** | 华为 2012 实验室等开源的企业级 Agent 平台（AgentOS），分层：DeepAgents / Studio / Framework / Runtime / System Service | 官方 GitHub |
| **agent-core** | openJiuwen 的核心 Python SDK，提供 Agent 编排、工作流、工具调用、上下文/记忆、断点恢复 | `openJiuwen-ai/agent-core` |
| **DeepSearch** | 官方开源的「知识增强深度研究与报告生成引擎」，built on agent-core；多智能体做查询规划→信息收集→理解反思→报告生成，带 chunk 级引用溯源 | `openJiuwen-ai/deepsearch` |
| **Swarm / JiuwenSwarm** | 官方多智能体协同框架：Leader 拆解任务、组团队、动态协商；SwarmFlow 是确定性多阶段工作流 | `openJiuwen-ai/jiuwenswarm` |
| **Skill / SwarmSkill** | 可复用能力包；SwarmSkill 是「团队协作流程沉淀的标准化技能包」，可自演进 | 官网 / skillhub |
| **Agentic Hub** | 官方资产市场，六类资产：Skill / SwarmSkill / plugin / connector / expert / expert group | `openJiuwen-ai/skillhub` |
| **HITL** | Human-In-The-Loop，人在环；在规划等关键节点暂停接受自然语言反馈 | DeepSearch 开发者指南 |
| **chunk 级引用** | 输出带校验过的引用，可预览、可打开，段落级追溯与置信度 | DeepSearch 产品文档 |

## 2. 决定性发现：官方已有 DeepSearch，别自己造内核

**openJiuwen 官方仓库里有一个 `DeepSearch` 项目，定位跟我们的目标高度重合**，而且已经做得比我们自研版完整：

- **深度研究 → 结构化报告生成**：多步、多源校验、严格推理的结构化研究报告（官方 use case 第一个就是「金融分析报告：接本地投研知识库 + web，产出投研报告」）；
- **模板支持**（官方原生）：`generate_template` 支持两种模式——给一份模板（Markdown/HTML/Word/PDF 提取结构），或给一份样本文档自动抽取模板，再按模板生成同类报告；模板可导出；
- **混合检索**：本地知识库关键词/向量/图/混合检索 + web 增强检索，在线知识构建、评估、精炼；
- **chunk 级引用溯源**：带校验引用、段落级追溯与置信度、关键论断的溯源推理与可视化；
- **富图表报告**：报告可嵌图，Markdown 输出可导出 Word / HTML；
- **HITL**：规划阶段自然语言反馈、协作式修订；
- **多模型分工**：`plan_understanding` / `info_collecting` / `writing_checking` / `general` / `vlm_chart_generating` 五个逻辑槽位可分别配模型。

**结论**：取数广度、深度搜索、模板、引用溯源这几块——也就是我们自认的全部短板——官方 DeepSearch 基本都提供了。继续自造是重复劳动，且质量大概率不如官方。**设计应该是「复用官方引擎 + 保留我们独有的核验门禁」，而不是从零重写。**

> 代价（诚实）：从 Java 自研栈迁移到 Python/openJiuwen 栈，是一次架构级重构，不是补丁。详见第 6 节。

## 3. 设计：四层架构

```
┌─────────────────────────────────────────────┐
│ ④ 核验内核【自研·护城河】                    │
│   N1–N7 数值复算 / C1–C2 引用核验 /          │
│   fail-closed 门禁 / 指纹绑定批准            │
├─────────────────────────────────────────────┤
│ ③ 领域资产【自研·配置】report-profiles       │
│   指标字典 / 报告模板 / 检查规则 / 画像配置   │
├─────────────────────────────────────────────┤
│ ② DeepSearch 引擎【复用官方】                │
│   查询规划 / 混合检索+搜索 / 理解分析 /       │
│   chunk 级引用成稿 / 模板 / HITL             │
├─────────────────────────────────────────────┤
│ ① openJiuwen agent-core【复用官方】          │
│   工作流编排 / 断点续跑 / HITL / 多智能体     │
└─────────────────────────────────────────────┘
```

### ① 编排底座：复用 agent-core

- 用 `WorkflowAgent`（预定义流程、确定性执行）承载报告主流水线；用 `ReActAgent` 承载需要动态决策的子任务（如深度搜索的迭代检索）。
- 用它的**异步并行图执行器**做组件并发；用**状态中断与恢复 / checkpoint** 支撑长报告的断点续跑（替代我们现在的 writer 超时 + 缺节续写兜底）；用**多工作流跳转**支撑「同一会话里写年报 / 转投研」。
- 来源：agent-core README「Agent Orchestration」「High-Performance Execution Engine」。

### ② 引擎：复用 DeepSearch

- 主流程用 `AgentFactory.create_agent(agent_config)`，按 `execution_method` 选 `parallel`（DeepresearchAgent）/ `dependency_driving` / `hybrid`；
- 检索用它的混合检索 + 可插拔 web 搜索；
- 报告用它的 `run(message, report_template=...)` + `generate_template(...)`；
- 在规划节点开 HITL（`workflow_human_in_the_loop` / `outline_interaction_enabled`）让人确认大纲；
- **来源**：DeepSearch 产品文档、Developer Guide。

### ③ 领域资产：我们的 report-profiles，对齐官方模板格式

- 把现有 `report-profiles/*/task_book` + `skeletons/*.json` **改造成官方模板格式**：Markdown 顶层章节 + `> Functional summary:` + `> Core section: true`，通过 `generate_template(is_template=True)` 注册，`run(report_template=...)` 使用；
- 指标字典、检查规则保持我们自己的配置（官方没有确定性数值复算）；
- 新增报告类型 = 加一份 Markdown 模板 + 一份指标字典，不动内核。

### ④ 核验内核：我们唯一真正自研且必须守住的层

官方 DeepSearch 的「chunk 级引用」解决的是**引用可追溯+置信度**，但它**没有**我们这套：

- **数值确定性复算**（N1–N7：谁的数、什么单位、派生关系对不对）；
- **fail-closed 门禁**：检查失败不是「建议」，而是阻断交付；
- **工件指纹绑定批准**：改正文 → 旧检查/旧批准失效，必须重查重批。

这三层是官方不提供的、也是对标后确认的差异化优势。**设计成 DeepSearch 输出之后的一道独立门禁**：DeepSearch 产出结构化 artifact → 我们的核验器复算 → 通过才放行 → 批准签名绑定指纹。

> 设计取舍：为什么核验做成「后置门禁」而不是塞进 DeepSearch 的生成流程？因为核验必须**独立于生成模型**才有意义——生成方自己判自己对，等于没判。fail-closed 的价值正来自这种分离。代价是 artifact 要有一套严格的结构化契约（我们现在有 `AnnualReportArtifacts`，需泛化为领域无关）。

## 4. 三个问题的直接回答

### Q1：是否需要模板支持？——**需要，而且直接用官方的，不要自己造**

- 官方 DeepSearch **原生支持**示例驱动生成：`generate_template` 可从样本文档（Markdown/HTML/Word/PDF，PDF 最多 512 页）抽取模板，或从给定 Markdown 模板规范化；`run(report_template=...)` 按模板生成同构报告。
- 官方模板支持「功能说明」「核心章节标记」，可导出复用——正好对应我们 report-profiles 想要的「骨架 + 章节语义」。
- **动作**：放弃自造 `skeletons/*.json` 私有格式，改用官方 Markdown 模板格式；`annual_report / research / client_analysis` 各出一份官方模板。
- 附带收益：官方还支持 `report_type`（brief / professional）、`user_materials`（直接喂给定材料参与规划与写作）——我们现在的「固定快照卷」可以借此升级为「用户材料驱动」。

### Q2：是否需要强化深度搜索能力？——**需要，这是最大短板，且官方正好能补**

- 我们现状：固定快照 + 通用 web 工具，无来源分级、无付费/登录态源、无本地知识图谱。
- 官方 DeepSearch 提供：本地知识库（关键词/向量/**图**/混合检索）+ web 增强（google / serper / tavily / **bocha 博查** / jina / perplexity 等 10 种引擎可插拔）+ 在线知识构建/评估/精炼。
- **动作**：直接接官方检索栈；把我们的「来源白名单 / 可信度分级」做成 `search_engine_config` 的约束参数喂进去；本地年报/研报语料走它的离线知识构建。
- 来源：DeepSearch Developer Guide「Web search / augmentation configuration」。

### Q3：基于 openJiuwen 怎么设计？——**复用 ①②，自研 ③④，核验后置门禁**

见第 3 节四层架构。一句话：**让官方负责「找得全、搜得深、写得像、引得出」，我们负责「数算得对、引用真支撑、错了就拦、改了就重查」**。

## 5. 模型选型（腾讯模型不采用）

你判断得对：腾讯模型不适合做这件事。理由与选型如下——

**为什么不用腾讯模型**：

1. DeepSearch 官方**验证过的模型列表里没有腾讯混元**——官方实测可用的是 Qwen3.7-Max（推荐）、Qwen3.8-Flash、Qwen3.7-Plus、Qwen3-Max、GLM-5.3 系列、DeepSeek V3.2、Kimi-K2.5；
2. 官方明确提示「用更强模型生成报告以平衡质量与稳定性，模型能力不足会影响报告质量或完整性」——报告生成是长链路高难任务，用未经该框架验证的模型风险高；
3. DeepSearch 只认 OpenAI-compatible 端点（`model_type=openai` / `siliconflow`），腾讯模型要走兼容层且无官方调参背书。

**推荐配置**（对应五个逻辑槽位，按官方建议强模型做 general）：

| 槽位 | 职责 | 推荐模型 | 理由 |
|---|---|---|---|
| `general`（必填） | 兜底默认 | **Qwen3.7-Max** | 官方首选，质量/稳定性平衡最好 |
| `plan_understanding` | 意图识别、大纲、规划 | Qwen3.7-Max 或 GLM-5.3 | 减少规划幻觉，用强模型 |
| `info_collecting` | 信息收集 | Qwen3.8-Flash | 量大、可降本提速 |
| `writing_checking` | 正文与富内容生成 | Qwen3.7-Max | 报告正文质量优先 |
| `vlm_chart_generating` | 图表处理 | qwen3-vl-plus | 官方实测 4.68s/图，性价比最佳 |

- 全部走 OpenAI-compatible API；`LLM_SSL_VERIFY=false`（国内自签环境）；
- 报告生成默认关思考模式（`ServiceConfig.llm_thinking_enabled=False`）以保证稳定性，与官方一致。

## 6. 落地路线（从现有代码迁移）

| 阶段 | 动作 | 验收 |
|---|---|---|
| **S0 并行验证** | 不动现有系统，起一套 DeepSearch Docker（sqlite+in_memory 最小模式），用同一批材料跑投研/年报，对比成品质量与引用溯源 | DeepSearch 产出报告质量 ≥ 我们现版，引用可预览可打开 |
| **S1 模板对齐** | 把 3 份 report-profiles 骨架转成官方 Markdown 模板，`generate_template` 注册成功 | 三类报告都能按模板生成同构章节 |
| **S2 检索接入** | 配 bocha/jina 搜索源 + 本地知识库（年报语料离线构建），加来源白名单约束 | 关键数字由检索自动补入，带可追溯引用 |
| **S3 核验门禁** | 把 N1–N7/C1–C2 做成 DeepSearch 输出后的独立服务，artifact 泛化，`fail-closed` | 负例（错单位/伪造数字/语境错配）照样 BLOCKING；benchmark 不退化 |
| **S4 编排迁移** | 主流水线迁到 agent-core WorkflowAgent，断点续跑替代超时兜底，HITL 让人确认大纲 | 长报告断网/中断后可续跑；大纲可人工干预 |
| **S5 收敛** | 下线自研 Java 编排/工具网关/ writer 调用链，只留核验内核 + 领域资产 + 官方栈 | 代码量大幅下降，护城河能力不丢 |

每个阶段都必须用 `annual-report` 分支的工行成品做等价性回归（见 `BRANCHES.md`）。

## 7. 已知限制与风险（诚实）

1. **迁移成本**：S4/S5 是架构级重写，现有 41 个 Java 类大部分会废弃；短期用 S0–S3 的「双轨并行」控制风险。
2. **官方栈不含模型**：DeepSearch 明确声明「只是工作流编排工具，不含 AI 模型」，模型/搜索 API key 自备、成本自担。
3. **引用 ≠ 核验**：官方 chunk 级引用做到「可追溯+置信度」，但**不保证数字算对**；这正是我们核验内核的必要性，不能因为「官方有引用」就砍掉双检。
4. **模板格式约束**：官方模板是 Markdown + 特定注释语法，自由度低于的能力（如精确控制每节字数）需靠 prompt 约束而非模板结构。
5. **国内网络**：部分搜索源（perplexity 等）国内不可直连，需配代理/转发；jina 需显式设 `s.jinaai.cn`。
6. **本文结论基于文档调研**，未在容器实跑 DeepSearch；S0 的首要任务就是用真实材料验证本文假设。

## 8. 数据来源（官方一手）

- openJiuwen 总览与仓库矩阵：https://github.com/openJiuwen-ai
- agent-core（Agent 编排/工作流/断点恢复）：https://github.com/openJiuwen-ai/agent-core
- DeepSearch 总览：https://github.com/openJiuwen-ai/deepsearch
- DeepSearch 产品文档：https://github.com/openJiuwen-ai/deepsearch/blob/main/deepsearch/docs/en/1.Product%20Introduction/1.Product%20Introduction.md
- DeepSearch 开发者指南（模板/HITL/模型配置/搜索源）：https://github.com/openJiuwen-ai/deepsearch/blob/main/deepsearch/docs/en/3.Developer%20Guide/README.md
- JiuwenSwarm（Swarm/SwarmFlow/Skill 自演进）：https://github.com/openJiuwen-ai/jiuwenswarm
- Agentic Hub（六类资产）：https://github.com/openJiuwen-ai/skillhub
- 官网：https://openjiuwen.com/

> 所有链接于 2026-11-17 实际抓取核对。本文判断如与官方新版本冲突，以官方文档为准。
