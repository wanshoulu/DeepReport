---
name: annual-report
description: 银行年报简报生成与核验方法论：八指标口径、引用纪律、修订预算、信源权威分级、真实年报骨架（名称保留/深度收缩）、多 Agent 六工位分工。W06 五角色（资料/指标/经营分析/资产质量/写作）必须遵循。
triggers:
  - 生成年报简报
  - 年报指标抽取
  - 引用核验
  - 银行财务报告
  - 年报骨架配置
  - 多agent分工
---

# Annual Report Skill（W06 方法论）

## 使命

从固定年报快照生成可溯源、可核验、可交付的年度报告简报。**先结构化、再渲染**；每个数字可回溯到快照原文页；检查失败阻止验收。

## 角色与铁律

各角色进入对话前必须读本 skill 及对应 references：

| 角色 | 必读 | 铁律摘要 |
|---|---|---|
| 资料资料员 | `references/metric-dictionary.md` | 只读快照；缺材料如实报 MATERIAL_MISSING；返回 digest+page+excerpt 三元组 |
| 指标专员 | `references/metric-dictionary.md` | 值照抄原文（含千分位）；单位用原文；找不到标 NOT_FOUND |
| 经营/资产质量分析师 | `references/banking-domain.md` | 只用注入的指标子集；每论断恰好一条引用；派生值标"基于 [C-k] 计算"+算式 |
| 主笔 | `references/citation-style.md` | metrics 原样转抄一字不改；论断只来自两路草稿；修订只动指令指定项 |
| 图表绘制 | `references/agent-division.md` | 图表类型/挂载节/数据系列来自数据文件与配置；不改任何数值，不发明图表 |
| 核验员 | 本文件「核验协议」节 | FAIL 打回一次；再 FAIL 或语义存疑转 waiting_human；绝不放行 |

## 核验协议（fail-closed）

1. **数值核验（确定性，无模型）**：完整性（八缺一即 BLOCKING）→ 单位白名单 → 数字必须在快照页字面出现 → 派生口径复算（见指标字典「机器可复算口径」）→ 期间成对。
2. **引用核验（双层）**：L1 确定性——digest/page/excerpt 逐条比对，伪造/篡改即 BLOCKING；L2 语义——支持度三级 SUPPORTED/PARTIAL/UNSUPPORTED，PARTIAL 转 WARNING+人工，UNSUPPORTED 即 BLOCKING。
3. **信源纪律**：指标值必须来自权威等级=官方一手的注册信源；引用页必须在注册信源覆盖内。
4. **门禁状态机**：PASSED→deliverable；FAILED round=0→注入 REVISION_DIRECTIVE 定向修订一次；FAILED round=1 或 WAITING_HUMAN→人工接管。修订上限=1（与采纳图 revision_gate 预声明一致）。

## 已知限制（如实声明）

- 指标集为 W01 冻结的八项，不覆盖全量科目（分部收入、流动性、表外等在路线图）；
- 语义支持度灰区由 LLM Critic 兜底但最终转人工，不由 judge 单独批准；
- 快照固定，无实时联网（#16 设计约束，非缺陷）。

## 参考文件

- `references/metric-dictionary.md` — 八指标口径字典：定义、公式、单位、机器可复算规则
- `references/banking-domain.md` — 银行业知识与监管指标口径（监管 vs 会计口径差异）
- `references/source-library.md` — 金融信源库：官方一手/机构二手信源清单与评级标准
- `references/citation-style.md` — 引用格式与论断标注规范（[C-n] 协议）
- `references/report-skeleton.md` — 真实年报骨架方法：名称保留/深度收缩，report-structure.json 唯一事实源
- `references/agent-division.md` — 多 Agent 六工位分工：查数/分析/图表/写作/核验/主编的输入-输出-产物
