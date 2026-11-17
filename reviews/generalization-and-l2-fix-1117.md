# 泛化性改造与 L2 语义核验修复（2026-11-17）

## 缘起

评审指出两点：(1) 引用支持度的确定性判定是「数字/实体字符串交集」，存在语境错配误判；(2) 系统只能写银行年报，投研投顾、客户分析等报告类型不支持。本文记录修复方案、验证结果与诚实的已知限制。

## 调研依据（先调研后动手）

**泛化范式**：多源调研（OpenAI DR / Perplexity / Manus / Hebbia / STORM / GPT Researcher 官方文档 + 第三方评测）一致结论——业界已放弃「每种报告造专用 Agent」，转向**「一个通用内核 + 一组可插拔领域资产」**。会随领域变化的四类东西必须外置为配置：

| 资产 | 抽象什么 | 本项目的落点 |
|---|---|---|
| 任务模板 | 产出物形状 | `report-profiles/*/task_book` + `skeletons/*.json` |
| 领域技能 | 专业流程/术语 | `report-profiles/*/skill_set` |
| 指标字典 | 数字的语义 | `report-profiles/metrics/*.json` |
| 检查规则 | 什么算合格 | N1–N7（Java 年报路径）+ `verify_metrics_generic.py`（通用路径） |

**L2 判定对标**：Perplexity 在检索期嵌入引文（结构上无法编造）；GPT Researcher 引用精度 85.36%/召回 90.82%（CMU DeepResearchGym）；Hebbia 句级内联+缺口显式声明。我们的旧 L2 用字符串交集，明显弱于这些做法。

## 改动一：L2 语境绑定支持度（修复 7.3）

**文件**：新增 `checker/src/main/java/com/agenthub/w06/checker/SemanticSupportAssessor.java`；`CitationChecker.java` 委托给它（版本 0.2.1→0.3.0）。

**算法**（确定性、无 LLM、可审计）：
1. 把论断拆成数值主张 `(主体词, 数字)`；4 位年份是期间词，不作数值主张；
2. 对每个数字，比较「论断中该数字前最近的主体词」与「摘录中该数字前最近的主体词」，一致才算语境匹配；
3. 数字在但最近主体词不同 → `UNSUPPORTED`（BLOCKING）——抓住「营收 0.85%」命中「不良率 0.85%」的错配；
4. 数字在但主体/单位/期间不全 → `PARTIAL`（转人工）；无可抽取数值且主体词重合 → 也降 `PARTIAL`；
5. 主体词表通过 `ReportProfile.Lexicon` 注入，**删除硬编码的「招商银行/营业收入/不良…」词表**。

**关键修正过程**：首版用「数字前后 24 字符窗口 contains 主体词」，实测两个反例仍误判（同页出现"营业收入"字样就把"不良率 0.85%"误关联）。改为「最近主体词归属」后 8/8 用例通过。

## 改动二：报告类型配置层（泛化 7.x）

**新增 `source/config/report-profiles/`**：
- `index.json` 注册表（`default_report_type` + 类型→profile 路径映射）；
- `annual_report.json`（年报，向后兼容基线）、`research.json`（投研投顾）、`client_analysis.json`（客户分析）；
- `metrics/equity_research.json`（ROE/PE/毛利率…）、`metrics/client_analytics.json`（满意度/NPS/续费率…）；
- `skeletons/research.json`、`skeletons/client_analysis.json`（章节骨架随类型切换）。

**新增 `checker/src/main/java/com/agenthub/w06/checker/ReportProfile.java`**：加载 profile 为运行时对象，实现 `Lexicon`，把主体/期间/单位词喂给引用核验器；并提供 `required_metrics / skill_set / task_book / evidence_locator` 供后续编排层消费。

**新增 `skills/verify_metrics_generic.py`**：配置驱动的通用数值核验器。与年报硬编码版 `verify_metrics.py` 同源 N1–N4，但指标集/单位/派生规则全部从 metric registry 读入——**换报告类型零改代码**。

## 验证（可复现）

1. **L2 评测基准** `benchmark/eval_checkers.py`（带标注正负样本）：
   - `exact_match=15/15`，二分类 precision=1.0 / recall=1.0 / F1=1.0，误报率=漏报率=0（在明确标注的用例上）；
   - 1 例语义等价（ROE↔净资产收益率）诚实标注 `NEEDS_MODEL`，由确定性层保守降级，不假装解决；
   - 三种报告类型全部通过。
   - 结果落盘 `benchmark/results.json`。
2. **JUnit 反例** `CheckerNegativeCasesTest.java` 新增 4 例：语境错配、同页异主体错配、年份不误判、泛化主体词（满意度）。
3. **通用核验器实跑** `benchmark/fixtures/`：
   - 投研 artifact（6 个股票指标）→ `WAITING_HUMAN`（派生基数缺失，正确降级人工）；
   - 客户分析 artifact（5 个非财务指标）→ `PASSED`；
   - 客户分析负例（单位错 + 伪造数字）→ `FAILED`，`UNIT_MISMATCH` + `FABRICATED_NUMBER` 两条 BLOCKING。**证明泛化不是放松核验**。

复现命令：

```bash
python benchmark/eval_checkers.py
python skills/verify_metrics_generic.py benchmark/fixtures/client_artifact.json \
  benchmark/fixtures/snapshots/client \
  source/config/report-profiles/metrics/client_analytics.json --report-type client_analysis
```

## 已知限制（诚实）

1. **Java NumericChecker 仍是年报专用**（八指标 enum 编译期锁定，61 处引用）。非年报数值核验走 Python 通用脚本；两条路径并存，未合并为单一 Java 内核。彻底合并需把 `MetricKey` 从 enum 改为值对象并重构契约——收益明确但风险高，列为后续项。
2. **语义等价仍未解决**：ROE vs 净资产收益率、中英文缩写等，确定性层一律降级人工，需要接 NLI/语义蕴含模型。这正是 L2 上层 CriticAgent 的职责，当前其结论与确定性层的绑定还不够强。
3. **report-profiles 的 task_book/skeleton/skill_set 尚未被 Java 编排层消费**：本次完成了"配置就绪 + 引用核验层接入"，任务书与章节骨架的按类型切换还需改 `CheckerHttpServer.buildTaskBook()`（下一步）。
4. **evidence_locator 泛化**：profile 声明了 `page/row/page_or_row`，但 Java resolver 仍只认 `page-N.txt`；客户分析的 row locator 在 Python 脚本层用页码模拟。真实 CRM/日志表接入待做。
5. **benchmark 规模小**（15 例，聚焦 L2 支持度），不构成完整系统评测；数值核验 N1–N7 的 precision/recall 仍需扩充标注集。

## 一句话总结

修复了 L2 语境错配误判（最近主体词归属），并把「会随报告类型变化的东西」外置为配置，用投研/客户分析两种报告验证了泛化可用；同时用评测基准把「更严格」变成可复现数字。护城河（字面命中、指纹绑定、fail-closed）全部保留，泛化路径下负例照样拦截。
