---
name: annual-report
description: 银行年报简报与长文报告生成与核验方法论：八指标口径、引用纪律、修订预算、信源权威分级、真实年报骨架（名称保留/深度收缩）、多 Agent 工位分工、长文流水线写作方法。年报生成类任务（对话流或流水线）的各角色 agent 均应遵循。触发场景：生成年报简报、生成年报报告、年报指标抽取、引用核验、银行财务报告、年报骨架配置、多agent分工。
---

# Annual Report Skill（年报方法论）

## 使命

从固定年报快照生成可溯源、可核验、可交付的年度报告（简报或长文）。**先结构化、再渲染**；每个数字可回溯到快照原文页；检查失败阻止验收。本技能只承载方法与流程；任务分解、成稿与反思由受理的 agent 自主完成，编排器（确定性流水线）只注入运行时数据与输出协议。

## 角色与铁律

各角色进入任务前必须读本 skill 及对应 references：

| 角色 | 必读 | 铁律摘要 |
|---|---|---|
| 资料资料员 | `references/metric-dictionary.md` | 只读快照；缺材料如实报 MATERIAL_MISSING；返回 digest+page+excerpt 三元组 |
| 指标专员 | `references/metric-dictionary.md` | 值照抄原文（含千分位）；单位用原文；找不到标 NOT_FOUND |
| 经营/资产质量分析师 | `references/banking-domain.md` | 只用注入的指标子集；每论断恰好一条引用；派生值标"基于 [C-k] 计算"+算式 |
| 长文写作（writer-agent） | `references/long-form-writing.md` | 受理任务书自主分解、逐节成稿；每节正文 600-1200 字；[C-n] 标注；违规反馈只改受影响节；输出协议不变 |
| 主笔 | `references/citation-style.md` | metrics 原样转抄一字不改；论断只来自两路草稿；修订只动指令指定项 |
| 图表绘制 | `references/agent-division.md` | 图表类型/挂载节/数据系列来自数据文件与配置；不改任何数值，不发明图表 |
| 核验员 | 本文件「核验协议」节 | FAIL 打回一次；再 FAIL 或语义存疑转 waiting_human；绝不放行 |

## 长文写作（long_form 流水线）

长文模式由写作类 agent（如 writer-agent）受理编排器下发的「长文写作任务书」：

1. 先用 skill_tool 加载本技能与 `references/long-form-writing.md`（长文方法与流程的唯一事实源）；
2. 按方法自主分解章节写作计划（可用框架 todo 工具），逐节成稿；
3. 严格遵循任务书的输出协议（块首行仅 `[S-n]`，正文从下一行开始）——该协议是服务端解析契约；
4. 收到核验违规反馈时，按 `references/long-form-writing.md` 的修订响应流程自纠。

长文硬性纪律（主文件级铁律，任务书/续写/修订任何轮次都不得让步；写作细则见 reference）：

- **篇幅**：每节正文 600-1200 字，总-分-收结构，中段不少于两个自然段；低于 600 字视为该节未完成，不得交付；
- **口径**：数值与单位照抄任务书指标表，一字不改；同比表述用「同比变动-XX%」，禁用「同比增长/下降X%」句式；不得编造指标表之外的数字；
- **引用**：涉及数据的论断逐条带任务书 [C-n] 引用标注，不得虚构或挪用引用编号；
- **交付通道**：本轮要交付的全部成稿块必须在最终答复中完整输出——最终答复是编排器唯一解析对象，中间轮次的构思、草稿与 todo 记录不算交付；
- **格式精度**：块首行 `[S-n]` 与引用标注 `[C-n]` 的方括号内不加空格（写 `[S-1]`，不写 `[ S-1 ]`）；最终答复从第一个块开始、到最后一个块结束，首尾不得附加总结或说明文字；
- **增量**：续写轮只输出缺失节，修订轮只输出受影响的节，未涉及的节不要重复输出。

写作方法、口径句式、引用纪律全部以技能为准；编排器不下发写作要求，只注入任务数据（节清单/指标表/引用清单/快照页原文）。

## 核验协议（fail-closed）

1. **数值核验（确定性，无模型）**：完整性（八缺一即 BLOCKING）→ 单位白名单 → 数字必须在快照页字面出现 → 派生口径复算（见指标字典「机器可复算口径」）→ 期间成对。
2. **引用核验（双层）**：L1 确定性——digest/page/excerpt 逐条比对，伪造/篡改即 BLOCKING；L2 语义——支持度三级 SUPPORTED/PARTIAL/UNSUPPORTED，PARTIAL 转 WARNING+人工，UNSUPPORTED 即 BLOCKING。
3. **信源纪律**：指标值必须来自权威等级=官方一手的注册信源；引用页必须在注册信源覆盖内。
4. **门禁状态机**：PASSED→deliverable；FAILED round=0→注入 REVISION_DIRECTIVE 定向修订一次；FAILED round=1 或 WAITING_HUMAN→人工接管。修订上限=1（与采纳图 revision_gate 预声明一致）。

## 已知限制（如实声明）

- 指标集为 W01 冻结的八项，不覆盖全量科目（分部收入、流动性、表外等在路线图）；
- 语义支持度灰区由 LLM Critic 兜底但最终转人工，不由 judge 单独批准；
- 年报域默认冻结快照；通用域可经 deep-search agent 联网补充（抓取产物须 snapshot_commit 落盘注册后方可引用）。

## 参考文件

- `references/metric-dictionary.md` — 八指标口径字典：定义、公式、单位、机器可复算规则
- `references/banking-domain.md` — 银行业知识与监管指标口径（监管 vs 会计口径差异）
- `references/source-library.md` — 金融信源库：官方一手/机构二手信源清单与评级标准
- `references/citation-style.md` — 引用格式与论断标注规范（[C-n] 协议）
- `references/report-skeleton.md` — 真实年报骨架方法：名称保留/深度收缩，report-structure.json 唯一事实源
- `references/agent-division.md` — 多 Agent 工位分工：查数/分析/图表/写作/核验/主编的输入-输出-产物
- `references/long-form-writing.md` — 长文流水线写作方法：任务分解、分节成稿法、口径句式、修订响应流程
