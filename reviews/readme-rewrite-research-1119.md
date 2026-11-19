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

---

## 第二轮重写（2026-11-20）：按优秀 README 的**第一屏结构**返工

第一版把标准条目堆齐了，但第一屏就是 11 行名词表 + 6 张数据表，读起来像内部审计报告，不像 GitHub 首页。用户要求「参考人家优秀的项目咋写 readme」，因此重新拉了 **5 份原文**，这次逐份拆的是**排版与叙事节奏**，不是条目清单。

### 样本四：RAGFlow（`infiniflow/ragflow`）

`https://raw.githubusercontent.com/infiniflow/ragflow/main/README.md`

- 第一屏顺序：Logo → badge 行 → 一行链接导航（`Cloud | Documentation | Roadmap | Discord`）→ **1200px 产品大图** → `<details open>` 折叠目录。
- 目录在第一个 `##` 之前；`## 💡 What is RAGFlow?` 之后立刻是 `## 🎮 Get Started`，Get Started 里再放 GIF 演示。
- 结论：**长 README 必须有可见 TOC，且图和"怎么开始"要排在任何术语解释之前**。

### 样本五：Dify（`langgenius/dify`）

`https://raw.githubusercontent.com/langgenius/dify/main/README.md`

- 第一行就是封面图 `![cover-v5-optimized](./images/GitHub_README_if.png)`，然后一行链接、badge 行、一段简介。
- `## Quick start` 排在 `## Key features` **之前**；安装后明确写出「Once the services are running, open http://localhost/install」——**告诉读者看到什么算成功**。
- Key features 是 `**1. Workflow**:` / `**2. Comprehensive model support**:` 这种「编号 + 加粗机制名 + 一句解释」，不是表格。
- 尾部有 Contributing / Community / Security disclosure / License 四节。

### 样本六：Prometheus（`prometheus/prometheus`）

`https://raw.githubusercontent.com/prometheus/prometheus/main/README.md`

- 定义句只讲动作：*"It collects metrics from configured targets at given intervals, evaluates rule expressions, displays the results, and can trigger alerts..."*，不用形容词。
- 区分清单用 `* A **multi-dimensional** data model (...)` 的加粗短语 + 括号补解释；`## Architecture overview` 紧跟其后放架构图。
- Install 给三条路径，Docker 那条一行命令 + "Prometheus will now be reachable at <http://localhost:9090/>"。

### 样本七：FastAPI（`fastapi/fastapi`）

`https://raw.githubusercontent.com/fastapi/fastapi/master/README.md`

- 开头一行 `**Documentation**: ... **Source Code**: ...`，紧接着一句话定位 + 加粗特性清单，没有任何铺垫段落。
- 性能声明 `on par with **NodeJS** and **Go** (thanks to Starlette and Pydantic)` 用**机制归因**，并给脚注 `estimation based on tests conducted by an internal development team`。

### 样本八：GPT Researcher 全文重读（`AssafElovic/gpt-researcher`）

- 第一屏：Logo → badges → **加粗一句话定位** → `## Why GPT Researcher?` 列 5 条具体痛点（*"Objective conclusions for manual research can take weeks"*）→ `## Demo` 视频封面图 → `## Architecture`（planner / execution / publisher 三个机制名 + 5 个步骤）。
- Features 用短句 + 具体数字（`exceeding 2,000 words`、`over 20 sources`），Getting Started 最后写 `Visit http://localhost:8000 to start.`

### 五份原文的共识（本轮改动的依据）

| 共性 | 第一版的问题 | 本轮改法 |
|---|---|---|
| 第一屏是图 / demo / 一句话定位 | 标题后直接长 tagline + 改名说明 + 11 行名词表 | 真实工作台截图（2x PNG）+ badge + 一行导航 + 一句话定位，名词表折叠到文末 |
| 长 README 有可见 TOC | 无 | 加 `<details open>` 目录 |
| Features = 编号 + **加粗机制名** + 一句解释 | 7 条平铺 + 独立术语大表，机制与定义割裂 | 重组成 7 条编号机制，首次出现的名词就地括号解释 |
| 有「为什么需要它」的具体痛点 | 无，直接进问题域抽象论述 | 新增 4 条朴素痛点（引用自评、补数字、指纹失配、改代码） |
| 有「看到成品」的示例输出 | 无，只有命令 | 新增 `一眼看懂：0.85%`（取自 `benchmark/results.json` 真实例子）+ 真实 artifact JSON 节选 + 报告引用标记 |
| Quickstart 写清「看到什么算成功」 | 只有 4 行 curl，没有预期结果 | 补返回片段、成功标志、约 5 分钟的耗时提示，并新增 `examples/quickstart.sh` |
| 架构图在前部 | 无图 | 新增 Mermaid 三段链路图（GitHub 原生渲染，不依赖外链） |
| 尾部有 License / 参与 | 无 | 新增「路线图与参与」「许可证」 |

### 本轮连带修正（真调研的收益）

- 核对 compose 时发现 `README` 原先指向的部署编排实际位于 `source/w06-annual-report-agent/deploy/compose.yaml`，且是 Agent Bus 内部网络版（`networks: control-net external: true`，**不映射宿主端口**），并非能单独起本机服务的编排；远端真实启动方式是 `docker run -p 18083:18095 -v ...:/snapshot w06-checker:0.3.9`，镜像未在公共 registry 发布。因此把「没有一键部署」写进已知限制，Quickstart 改为如实声明前置条件，不再给读者一条跑不通的路径。
- 仓库根目录**没有 LICENSE 文件**。第一版对此只字未提；本轮新增「许可证」节，明确写"未明确授予开源使用许可"，不默认按 MIT / Apache-2.0 使用。
- 截图按既有规范交付：`docs/images/deepreport-workbench-1120.png`，Chrome headless `--force-device-scale-factor=2` 输出 2400×1440 PNG，用新文件名避免 CDN 缓存吐旧图。
