# 我们这套「研报智能体」vs 市面主流报告智能体——能力对比与短板清单

**版本**：v1.0 · 2026-11-17
**调研口径**：多源交叉（官方文档 / 产品页 / 第三方独立评测），非单一样本；对标数字均标注来源与测量口径，我们的数字来自线上成品报告（指纹 `ec73959e…`）与架构文档。
**目的**：回答「和别人比，我们差在哪、强在哪、哪些优势不能丢」。

---

## 〇、先说结论（给不看细节的人）

1. **我们不是「又一个写报告的 Agent」，而是「把可审计性做到极致的长报告交付系统」。** 市面主流产品（OpenAI Deep Research / Perplexity / Manus）官方都承认会编造引用、需人工逐条核对；我们用的是「数字只从工具来 + 引用字面命中 + 检查失败阻止交付」的门禁纪律——这是它们没有的。
2. **我们真正的短板不在「写」，在「取数广度」和「数据汇总分析深度」**——正是你大侄指出的那点。对标 Hebbia 的 Matrix 和 Manus 的数据分析，我们目前只有轻量的 `table_extract/table_analyze`，招行/建行成品 0 张表就是证据（快照只有 4 页纯文字节选，属诚实限制而非 bug）。
3. **我们做得好、绝不能丢的**：数值纪律（N1–N7）、引用三元组字面命中 + `sha256` 绑定、工件指纹绑定检查结论（改正文旧检查即失效）、fail-closed 门禁、27 章 27003 字长文骨架。这些对标后确认是差异化优势，不是自嗨。

---

## 一、调研样本与名词释义

先解释对标文献里的专有名词，避免拿项目内用法当定义（首次出现就地说明）：

- **Deep Research（深度研究）**：不是某个产品名，是一类范式的统称——模型自主规划多步、浏览几十到几百个来源、产出带引用的长报告。代表：OpenAI Deep Research、Perplexity Deep Research、Gemini Deep Research。
- **Matrix（矩阵）**：Hebbia 的核心交互范式，行=文档/公司、列=问题/抽取任务，把同一组问题一次打到成千上万份文件上，逐格可溯源。**「表格即产品」**，不是报告里顺带插的表。
- **ISD（Iterative Source Decomposition，迭代式来源分解）**：Hebbia 的推理引擎，把复杂查询拆成子任务、多代理并行后重组，输出句级内联引用（可定位到源段落甚至页码）。
- **Pre-writing（预写作）**：STORM 提出的概念——写作前先做「多视角提问→大纲」，把「研究什么」和「怎么写」拆成两阶段。
- **检索期引文 vs 生成期引文**：一个关键架构分野。Perplexity 在检索阶段就把引文标记写进 prompt，模型无从编造 URL；OpenAI 早期让模型记住来源再吐引用，因此有幻觉风险。

**对标样本（6 主 + 2 参照）**：OpenAI Deep Research、Perplexity Deep Research、Manus、STORM（Stanford OVAL 开源）、GPT Researcher（开源）、Hebbia（金融垂直）；参照 Gemini Deep Research、Claude Research。样本跨越「通用黑箱 / 检索优先 / 并行子代理 / 结构化流水线 / 文档级表格推理」五类架构，足够覆盖设计空间。

---

## 二、格局：五类玩家，没有一家通吃

| 架构类型 | 代表 | 核心机制 | 强项 | 弱项 |
|---|---|---|---|---|
| 前沿模型 + 工具调用（闭环） | OpenAI DR、Gemini DR | 一个强推理模型自己规划/浏览/读/写 | 长文叙事质量、推理深度 | 引用透明度、过程可审计 |
| 检索优先（citation-first） | Perplexity | 检索期嵌入引文，模型只对已结构化上下文写作 | 引用溯源、速度 | 长文深度、综合力 |
| 并行子代理（wide research） | Manus | 主代理拆解→数十子代理沙箱并行 | 大规模多对象覆盖、交付形态 | 单点深度、成本可预测性 |
| 结构化预写作流水线 | STORM、GPT Researcher | 多视角提问→大纲→分节写作 | 结构化长文、可自托管 | 交互性、企业数据接入 |
| 文档级表格推理 | Hebbia | Matrix 网格 + ISD 句级溯源 | 表格化汇总、逐句引用 | 开放域发现、自然语言长文 |

**判断**：市面上没有「表格 + 长文 + 可审计」三者全占的单一产品。现实做法是组合（Hebbia/Manus 出结构化表，OpenAI/Claude 写叙事）。我们的机会恰恰在这条缝里——把「长文 + 可审计」做到位，补上「表格」。

---

## 三、逐维度对比（别人怎么做 / 我们怎么做 / 判定）

### 维度 1 · 数据源获取：明显落后

- **别人**：OpenAI DR 公开网 + 上传 + MCP 连接应用，单次浏览 50–200 源（[OpenAI 官方](https://openai.com/index/introducing-deep-research/)）；Perplexity 混合检索、深度读数百源，有 Academic 模式限学术源；Manus 云端 VM 爬虫 + 416 个连接器 + Similarweb/SEC 等付费 API + 登录态浏览器；GPT Researcher 20+ 检索器 + MCP + 本地文档，支持「公开网页 + 私有数据」混合；Hebbia 联邦 28 个持牌/私有源（FactSet、CapIQ、PitchBook、VDR、Snowflake…），累计处理 15 亿+页文档（[Hebbia 官方](https://www.hebbia.com/resources/investment-research-software)）。
- **我们**：固定年报快照（`read_page` 只读挂载）+ 有限 MCP 工具（`web_search`/`web_fetch`/`pdf_extract` + 新增 `table_extract`/`table_analyze`）；无登录态浏览器、无付费数据源连接器、无 「Academic 模式」类的来源域约束（有 `snapshot_commit` 做固定信源，但那是冻结而非广域检索）。
- **判定：落后。** 开放域检索广度和私有数据源接入，差距是数量级的。我们的设计初衷是「从固定快照出报告」（对应 GPT Researcher 的 local 模式取舍），这在合规上是对的，但直接导致「资料不够就写不厚」。
- **补齐方向**：① MCP 工具层加「限定域名 / 来源可信白名单」参数（对齐 OpenAI 2026-02 的 Sites 能力）；② 接 1–2 个真实金融数据源（东方财富/巨潮/SEC filings）的 MCP server，把「信源不足时用工具补」从口号变成通路；③ 保留快照冻结作为「可复现基线」，新增「广域补数」为可选增强层——两者不冲突。

### 维度 2 · 数据汇总分析与表格：明显落后（这是最痛的短板）

- **别人**：Hebbia Matrix 是唯一「表格即产品」的形态，同问题批量打多文档、列间可级联，预置 Company Screening / Precedent Transactions；Manus 上传 15,000 行 CSV 无压力，写 pandas/plotly 做清洗、出可编辑公式的财务模型和交互仪表盘；GPT Researcher 用 Jev（学习型相关性打分器）做上下文过滤，相关段落保留率 73%（embeddings 仅 46%）。
- **我们**：新增 `table_extract` / `table_analyze`（确定性复算同比/占比/合计）+ 成品报告 50 张 HTML 数据表（含表名、单位、来源页、分析注脚）。但表格是「报告里插的表」，不是可交互的 Matrix；跨银行表格目前 0 张（快照只有纯文字节选页）。
- **判定：落后，但已有骨架。** 我们有「确定性复算」这个对的起点（不心算、数字从工具来），缺的是「批量抽取 + 结构化网格 + 可视化」。
- **补齐方向**：① 把 `table_extract` 从「单页抽取」升级为「跨页/跨文档批量抽取 → 统一表结构」，让招行/建行从 0 表变成有表；② `table_analyze` 增加跨期/跨行对比与异常标注；③ 出一版可交互 HTML 表格（排序/筛选）作为交付物——这是低成本高感知的改进。

### 维度 3 · 引用溯源：我们领先（对标后确认）

- **别人**：Perplexity 检索期引文，结构上无法编造 URL，第三方测引用准确率约 94.3%；Hebbia ISD 句级 + 页码级引用，抽不到就显式声明「未完整提取」（报缺口不抹平）；OpenAI DR 引用由模型生成，独立评测参考文献幻觉率约 26.57%（ChatGPT-4o 为 39.14%）；Manus 官方 Help Center 自认「编造论文与引用」是已知失败模式。
- **我们**：`CitationChecker` 逐条核验，`evidence_excerpt` 必须在页面文本字面命中，`digest = sha256(excerpt)`；每论断恰好一条 `[C-n]` 引用；语义存疑转人工（PARTIAL→`waiting_human`）；成品 81 条引用、双 PASSED。
- **判定：领先（在「可核验」这一维度）。** 我们的机制接近 Perplexity 的「结构上难以编造」而非 OpenAI 的「生成后校验」。注意诚实边界：我们不是 Perplexity 式的检索期嵌入，而是「生成后字面核验」——比模型自己吐 URL 强，但依赖快照页文本质量。
- **不能丢**：引用三元组（digest/page/excerpt）字面命中 + 逐条核验，这套纪律是我们区别于通用产品的核心，任何优化都不许放松。

### 维度 4 · 长文生成：我们领先（结构化篇幅）

- **别人**：OpenAI DR 20–36 页、10–30 分钟，叙事质量标杆，但有输出长度上限（约 5k 词量级）、Plus 25 次/月；Perplexity 1,500–3,000 词、偏摘要型；STORM 维基式长文、组织性比基线提升 25pp（NAACL 2024），但官方明言「非可发布稿」；GPT Researcher 2,000+ 词、树状递归可加深；Manus 交付形态丰富（PDF/PPT/仪表盘/视频）但单点深度不足。
- **我们**：27 章、27,003 字完整年报，六段式章节结构，缺节续写 + 反思修订 + 双核验 + 审批 + 导出的完整流水线；成品经 `APR-0004` 具名批准。
- **判定：领先（在结构化长篇 + 交付闭环）。** 27 章的篇幅和「生成后必过门禁」的完整性，是 STORM/GPT Researcher 这类开源骨架和 Perplexity 这类快报产品都不具备的。
- **诚实边界**：我们的章节是固定 27 章模板（`report-structure.json`），不是 STORM 式「多视角动态提问生成大纲」。对「换一个题目就重新规划研究路径」的灵活性，我们弱于 STORM/GPT Researcher。

### 维度 5 · 可信与可审计：我们领先（最强项）

- **别人**：STORM/GPT Researcher 这类开源项目基本没有企业级的「提交权/版本绑定/失败门禁」；商业产品里只有 Hebbia 有签核检查点（Matrix 2.0 每工作流内置人签字）和 SOC2/ISO 认证。OpenAI/Perplexity/Manus 都是「出报告给你，你自己核对」。
- **我们**：① 工件指纹绑定检查结论——改正文则 `artifact_sha256` 变，旧 PASS 自动失效，必须重查重批（对应 E03 验收用例）；② fail-closed——检查 FAIL 不是「建议面」，而是阻断交付、定向修订一次、再失败进 `waiting_human`；③ 三权分立（推进权 Java / 提交权 Go+PG / 干活权专家 Agent）；④ 数值纪律 N1–N7（数字只从工具来、百分点 vs 百分比严格区分）。
- **判定：领先，且是架构级领先。** 这一维度上我们甚至比部分商业产品更严格——「检查失败阻止交付」不是所有产品都做得到的（OpenAI DR 官方明说会幻觉、要人工把关）。
- **不能丢**：这是我们的立身之本。任何「简化核验提速」的提议都要先过这一关。

### 维度 6 · 交付形态与工程化：混合

- **别人**：Manus 交付形态最丰富（PDF/PPT/HTML/CSV zip/视频）；OpenAI DR 可导 Markdown/Word/PDF；Hebbia 出 PPT/Excel/Memo/Dashboard + API/MCP；GPT Researcher 以工程化完整著称（有 MCP server、A2A card、LangGraph 8-agent 版）。
- **我们**：成品为单份 HTML（27 章 50 表 4 图表）+ JSON 工件（metrics/sections/citations/tables）；有 A2A wrapper 真协议、MCP 5 server、三注册表。**工程化骨架真实存在**，但交付物只有 HTML/JSON，无 Word/PDF/PPT 导出，无对外 API/MCP 暴露我们的能力。
- **判定：混合。** 内部工程化不弱，外部交付形态和开放接口薄。
- **补齐方向**：① 加 HTML→PDF/Word 导出（低成本）；② 把我们「核验能力」以 MCP server 暴露（对外提供「给一份报告，返回数值/引用核验结果」）——这是把我们的差异化优势产品化。

---

## 四、短板清单（按优先级，诚实版）

| 优先级 | 短板 | 对标参照 | 补齐动作 | 代价 |
|---|---|---|---|---|
| **P0** | 数据汇总分析弱：跨文档/跨期表格批量抽取缺失，招行/建行 0 表 | Hebbia Matrix、Manus pandas | `table_extract` 升级为批量 + 统一表结构；`table_analyze` 加跨期对比 | 中；直接回应你大侄的批评 |
| **P0** | 数据源获取窄：无广域检索白名单、无金融数据源连接器 | OpenAI Sites、Perplexity Academic、Hebbia 28 源 | MCP 加来源白名单参数；接 1–2 个真实金融数据源 | 中；需外部 API/授权 |
| **P1** | 研究规划固定：27 章模板，非动态多视角提问 | STORM pre-writing、GPT Researcher 树状递归 | 在模板之上加「研究计划」阶段（可先做轻量版：按题目微调章节权重） | 中高；动编排逻辑 |
| **P1** | 交付形态薄：仅 HTML/JSON，无 PDF/Word/PPT | Manus、OpenAI DR、Hebbia | 加导出器；核验能力以 MCP 暴露 | 低-中 |
| **P2** | 单点深度：强推理/矛盾信息综合弱于 Gemini/Claude DR | Gemini DR、Claude Research | 非自研短板，靠接更强模型解决 | 外部依赖 |
| **P2** | 跨团队协作契约未落地（执行身份 o/a/b、角色挂 RDC、PG 提交） | ——（这是项目内部架构合规，非产品对标） | 见 `w06-gap-vs-github-1117.md`，按 W04/W05 契约推进 | 已单列，不在此展开 |

**注**：P2 最后一项是 W06 在整个多团队协作中的职责边界问题，和「作为报告智能体产品 vs 市面产品」是两条线，不要混为一谈。它不影响我们作为「研报智能体」的能力评价，但影响能否并入主系统。

---

## 五、我们做得好、对标后确认必须守住的（「别给搞没了」清单）

1. **数字纪律**：数字只从工具来（`MetricAgent` 抽取 + `NumericChecker` 复算），writer 口述数字与工件不一致一律 FAIL。→ 对标 Hebbia 的「never recalculates or invents a value」，我们同款。
2. **引用字面核验**：`evidence_excerpt` 页内字面命中 + `sha256` 绑定，不用模型自报引用。→ 对标 Perplexity 的「结构上无法编造」，我们同款思路。
3. **工件指纹绑定检查/批准**：改工件→旧检查/批准全失效。→ 对标 E03 验收，比多数商业产品严格。
4. **fail-closed 门禁**：检查失败阻止交付，不做事后建议。→ Hebbia 有签核点，我们更自动化。
5. **27 章长文骨架 + 完整流水**：缺节续写→反思修订→双核验→审批→导出。→ 篇幅与闭环完整性领先开源骨架。
6. **三权分立 + 确定性调度**：智能在规划期/节点内，确定性在调度与提交。→ 架构级优势，STORM/GPT Researcher 完全没有。
7. **诚实报缺口**：快照没有的页不出表（招行/建行 0 表），不编造数据填充。→ 对标 Hebbia「报缺口不抹平」，这是加分项不是 bug。

---

## 六、收敛建议：如果只补三件事

1. **补「批量取数 + 成表」**（P0）：直接回应大侄批评，把 50 表从「工行专属」变成「三家银行都有」，让 `table_extract/table_analyze` 真正成为数据引擎而非装饰。
2. **补「广域补数通路」**（P0）：MCP 加来源白名单 + 接一个真实金融数据源，让「信源不足用工具补」成为可复现能力，而不是靠人工贴 PDF 链接。
3. **把「核验能力」产品化**（P1，最被低估）：我们的核验纪律市面独有，把它做成 MCP server 对外暴露，比再写一个报告生成器更有差异化——别人出报告让你自己核对，我们出报告且自带可机读的核验结论。

**一句话定位（更新）**：**数据可溯、数字可核验的长报告智能体**——短板在「取数与分析」，护城河在「核验与门禁」，两者不能混。

---

## 附录 · 关键数据来源

- [OpenAI Deep Research 官方发布与限制](https://openai.com/index/introducing-deep-research/)（2025-02，2026-02 更新 MCP/Sites）
- [Deep research agents explained: Perplexity / GPT / Gemini](https://datarekha.com/blog/deep-research-agents-explained)（架构分野与检索期引文机制）
- [STORM 论文（NAACL 2024, Stanford OVAL）](https://storm-project.stanford.edu/research/storm)（pre-writing、组织性 +25pp、red herrings 误差分析）
- [GPT Researcher 官网](https://gptr.dev/)（20+ 检索器、Jev 73%/46%/51%、CMU DeepResearchGym 引用精度 85.36%/召回 90.82%）
- [Hebbia 投资研究软件页](https://www.hebbia.com/resources/investment-research-software)（Matrix、ISD、28 源联邦、15 亿页、SOC2/ISO）
- [Manus for Researchers and Analysts](https://professionalstoolkit.com/articles/manus-ai-for-researchers)（416 连接器、付费数据源、官方自认编造引用为失败模式）

> 说明：各评测口径（引用准确率、幻觉率、源数）不同来源有出入，本文标注的是调研时拿到的公开口径，非我方实测；我方数字来自线上成品与架构文档，指纹可核。
