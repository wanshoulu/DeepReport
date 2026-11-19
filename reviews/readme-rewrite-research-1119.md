# README 重写调研留痕（2026-11-19）

按 `repo-docs-standard` 要求，动笔前实调了 **3 份同类型项目 README 原文**（深度研究 / 报告生成方向），逐个分析章节骨架；并与 2026-09 已调的 Ollama / Mermaid / FastAPI / Prometheus 四份范式交叉验证。本次拉的是原文，不是凭印象复述。

## 样本一：GPT Researcher

来源：`https://raw.githubusercontent.com/AssafElovic/gpt-researcher/master/README.md`

- 首句定位：*"GPT Researcher the first open deep research agent designed for both web and local research on any given task."* —— 一句话说清是什么、覆盖哪两类输入。
- 第一屏放 Demo 视频截图，安装命令之前先让人看到成品。
- 机制名而非形容词：`planner` / `execution` agents / `publisher`，架构节用这三个词串起五步流程。
- 数字带口径：Features 写 *"Generate detailed reports exceeding 2,000 words"*、*"Aggregate over 20 sources"*；Deep Research 写 *"Takes ~5 minutes per deep research"*、*"Costs ~$0.4 per research (using `o3-mini` on "high" reasoning effort)"* —— 耗时与成本都挂在具体模型与推理档位上。
- 灵感来源挂 arXiv 链接（Plan-and-Solve / RAG），不空口说"受前沿研究启发"。

**采用**：把实测数字全部挂测量口径与环境；第一屏给可运行结果；用机制名组织特性。

## 样本二：STORM（Stanford OVAL）

来源：`https://raw.githubusercontent.com/stanford-oval/storm/main/README.md`

- 诚实自评：*"While the system cannot produce publication-ready articles that often require a significant number of edits, experienced Wikipedia editors have found it helpful in their pre-writing stage."* —— 论文项目主动写明"产出不可直接发布"。
- 加粗机制名：**Perspective-Guided Question Asking**、**Simulated Conversation**、**a collaborative discourse protocol**、**mind map**。
- 学术出处挂在标题下方：STORM Paper (arXiv:2402.14207)、Co-STORM Paper (arXiv:2408.15232, EMNLP 2024)、NAACL 2024。
- 采用数据带来源：*"More than 70,000 people have tried our live research preview"*。

**采用**：机制名加粗成清单；诚实写明本项目「无公开基准评测」「不对材料源真实性背书」；第三方基准挂出处。

## 样本三：DeerFlow（ByteDance）

来源：`https://raw.githubusercontent.com/bytedance/deer-flow/main/README.md`

- 缩写就地展开：*"DeerFlow (**D**eep **E**xploration and **E**fficient **R**esearch **Flow**)"*。
- 状态声明直白：*"DeerFlow 2.0 is a ground-up rewrite. It shares no code with v1."*。
- Quick Start 三行 `git clone` → `cd` → `make setup`，另给 "One-Line Agent Setup"。
- `Guides` 是一张按目标导航的入口对照表；`⚠️ Security Notice` 明确写出公网暴露风险与前置阅读要求。

**采用**：产品名就地解释构词；按目标导航表；独立「安全须知」节；最短上手控制在四行命令内。

## 与既有四份范式的关系

Ollama / Mermaid（工具可视化型）要求「截图先行 + 两行跑通 + 零形容词」；FastAPI / vLLM（框架库型）要求「一句话定位 + 特性=机制名清单 + 性能声明挂第三方基准」；Prometheus（基础设施型）要求「一段完整简介 + 加粗机制名区分清单」；graph-builder（系统型）要求「入口对照表 + 按目标导航 + 实测数据 + 已知限制」。

本项目是**系统/平台型**（带工作台、核验内核、多类型配置），因此主骨架取 graph-builder 的入口对照表 + 实测数据 + 限制清单，文风取 Prometheus 的"简介 + 加粗机制名"，再叠加上面三份同类项目的：诚实自评（STORM）、数字挂口径（GPT Researcher）、缩写展开与安全须知（DeerFlow）。

## 落地清单自查

- [x] 3+ 同类型项目原文实调（本次 3 份 + 既有 4 份交叉验证）
- [x] 范式选对：系统/平台型，不是可视化工具型
- [x] 名词表就地解释；DeepSearch / agent-core 定义来自官方 README 与官网，不是照项目内用法描述
- [x] tagline 之后有完整「项目简介」节（问题域 / 机制 / 交付物 / 状态声明）
- [x] 所有实测数字带口径与环境；第三方基准挂出处；明确承认无公开基准
- [x] 设计决策逐条带代价
- [x] 已知限制、安全须知单列
- [x] 文档之间不重复定义：核验规则细节指向 `reviews/` 与 `skills/`，路线指向 `ROADMAP.md`

## 名词调研的连带修正

写名词表时实测 `https://github.com/openJiuwen/deepsearch` 返回 404，改用官方组织 `openJiuwen-ai/deepsearch` 的 README 与 PyPI 包页核对，确认：

- 官方定义是 *"knowledge-augmented, high-performance, high-precision deep retrieval and research engine"*，基于 `openJiuwen agent-core`；
- 官方核心能力名为 **Example-driven report generation** 与 **Segment-level provenance**（片段级结果溯源）；
- 官方首个应用场景确实是金融分析研报。

因此 README 名词表中区分了 DeepSearch 的「片段级溯源」与本项目自研的「证据摘录字面命中 + sha256 绑定」，没有把两者混为一个机制。
