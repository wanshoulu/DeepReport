# 能力分层调研留痕（2026-11-20）

> 触发背景：用户指出上一轮把「风控报告 0 表 0 图」归因为「模板没写表格、材料没数值矩阵」是现象层打补丁；
> 正确问题是**能力该属于哪一层**：模板声明、Agent 路由、Skill 封装方法、MCP 执行，以及门禁对账。
> 本文记录为写 `docs/capability-routing-design.md` 而真调研的原文，单样本不算调研。

## 4 份一手原文与各自学到的机制名

| # | 原文 | 学到的机制名（原文用语） | 对本项目的映射 |
|---|---|---|---|
| 1 | [MCP · Tools](https://modelcontextprotocol.io/docs/concepts/tools) | `capabilities.tools` 能力声明、`tools/list` 发现、`tools/call` 调用、`inputSchema`/`outputSchema` 契约、**model-controlled**（"the language model can discover and invoke tools automatically based on its contextual understanding and the user's prompts"） | 工具必须先在注册表声明，Agent 才能发现；工具描述是模型决策的依据，不是硬编码调用 |
| 2 | [Anthropic · Equipping agents with Agent Skills](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills) | **progressive disclosure** 三级（name/description 预载进 system prompt → SKILL.md 正文 → 附加 references/scripts）、"Claude will use these when deciding whether to trigger the skill in response to its current task"、skill 可打包脚本当工具执行、"complement MCP servers by teaching agents more complex workflows" | Skill 承载「怎么做」，MCP 承载「原子执行」；技能选择应由任务触发，不是写死首句 |
| 3 | [LangChain · Tools](https://docs.langchain.com/oss/python/langchain/tools) | "The model decides when to invoke a tool based on the conversation context, and what input arguments to provide"、docstring/`description` + `args_schema` 是模型可见契约 | 工具的参数契约要显式声明；模型依据契约决定调哪个 |
| 4 | [Anthropic · Building effective agents](https://www.anthropic.com/engineering/building-effective-agents) | **workflows**（"LLMs and tools are orchestrated through predefined code paths"）vs **agents**（"LLMs dynamically direct their own processes and tool usage"）、**orchestrator-workers**（"subtasks aren't pre-defined, but determined by the orchestrator based on the specific input"）、prompt chaining 的 **gate**（程序化检查中间产物 "ensure that the process is still on track"） | DeepReport 现状是 workflow；缺的是按声明动态路由 + 中间产物门禁 |

## 交叉验证出的共识

1. **能力必须先声明、可发现**：MCP 的 `capabilities.tools` + `tools/list`、Agent Skills 的 `name`/`description` 预载、LangChain 的 `description`/`args_schema`——三者都把「有哪些能力」做成机器可读契约，而不是散落在代码或提示词里。
2. **选择权属于 Agent，不属于硬编码**：MCP "model-controlled"、LangChain "model decides when to invoke"、Anthropic "agents dynamically direct their own processes and tool usage"。硬编码 `if (mode) callTool()` 属于 workflow，不是 agent。
3. **Skill 与 Tool 分工，互补而非替代**：Anthropic 明确 "Skills ... complement MCP servers by teaching agents more complex workflows that involve external tools and software"；Skill 里可放确定性脚本执行。
4. **编排要有程序化 gate**：prompt chaining 允许在中间步骤加检查 "ensure that the process is still on track"——对应本项目缺的「声明-交付对账」。

## 与本项目现状的对照（证据来自本地源码，非推断）

| 业界机制 | DeepReport 现状 | 证据 |
|---|---|---|
| 能力声明可机读 | 只有 `required_metrics` 是声明，无 `capabilities` | `source/config/report-profiles/research.json:11` |
| 声明被 Agent 消费 | `requiredMetrics()` 全仓零消费点 | `.../checker/ReportProfile.java:114` |
| Skill 按任务触发 | 任务书首句硬编码 `请先用 skill_tool 加载 annual-report 技能` | `.../server/CheckerHttpServer.java:1635` |
| 工具由模型决策调用 | `extractDataTables(resolver, bank)` 无条件硬编码执行 | `.../server/CheckerHttpServer.java:1210` |
| 能力声明-交付对账 | 批准门禁只跑 numeric + citation | `.../server/CheckerHttpServer.java:643-653` |
| 图表能力已注册 | `w06-charts（图表）待 chart-agent 有实现基础后注册` | `source/w06-annual-report-agent/ARCHITECTURE-v3.md:156` |

## 本轮补充的既有调研

`reviews/readme-rewrite-research-1119.md` 已有 RAGFlow / Dify / Prometheus / FastAPI / GPT Researcher / STORM / DeerFlow 的 README 结构调研。
本轮 4 份是**能力分层**主题，与 README 排版主题互补，不重复计数。
