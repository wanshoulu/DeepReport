# Phase 0 验证报告：openJiuwen DeepSearch 远端实跑结论

> 日期：2026-11-18 00:45　环境：192.144.137.185 远端 Docker（最小栈 sqlite+in_memory）
> 结论先行：**架构对接可行性已证实，且实测抓到一个决定性证据——必须保留我们的核验内核。**

## 一、部署与验证全记录

| 步骤 | 结果 | 说明 |
|---|---|---|
| DeepSearch 最小栈部署 | ✅ healthy | 8000(API)/8089(telemetry)，sqlite+in_memory |
| 年报 28 节模板注册 | ✅ template_id=1 | 我们的骨架 JSON → 官方 Markdown 模板 → `generate_template` 一次通过 |
| 搜索引擎注册 | ✅ engine_id=1 | API 通（placeholder key，实际检索无效） |
| LLM 配置 | ✅ qwen3.7-flash | 复用远端现成 OpenAI-compatible 端点 |
| 纯 web 搜索管线 | ✅ 跑通但 fail-honest | 无真实 key → 27 节并行收集全空 → 正确拒绝出报告（"Not found passages"） |
| **材料模式端到端** | ✅ **完整出报告** | 109 页工行年报快照 → 分组 28 条 → 完整报告 164,429 字符 |

## 二、材料模式实测数据（决定性）

**输入**：工行 2025 年报快照 109 页（`w06-checker:/snapshot/live/pages/`），合并为 28 个主题块（DeepSearch 素材上限 50 条），template_id=1（我们的年报骨架）。

**输出**（约 25 分钟，20.5k SSE 事件，12.5MB 事件流）：

| 指标 | 数值 |
|---|---|
| 报告正文字符 | **164,429**（约 16 万字） |
| 章节标题 | 194 个（按我们 28 节模板展开） |
| `checked_citation` 引用标记 | **632** 个，格式 `[checked_citation:N][[M]](pages-A-B.txt)` |
| 表格行 | 409 行 |
| 两位小数数字 | 709 个 |

**质量抽查（第 6 章样例）**：正文质量良好——总资产 534,777.73 亿元/较上年末增 46,560.27 亿元/增长 9.5%，数据与快照原文一致，引用页码正确指向来源。

## 三、决定性发现：为什么必须保留我们的核验内核

对 315 个带引用的数值主张跑我们 C1 风格的字面命中核验：

| 结果 | 数量 | 占比 |
|---|---|---|
| 引用页范围内字面命中 | 231 | **73.3%** |
| 未命中 | 84 | 26.7% |

对未命中样本逐个溯源，发现**两类问题**：

1. **页码范围标注不准**（约占未命中多数）：如 305,061.14 亿元实际在 page-26，报告标了 pages-005-008——数字真实存在但引用页错。**修复路径：适配层的引用重定位（用我们的证据 store 反查真实页码）。**
2. **模型自行复算派生值**（约 12 个样例）：原文「增长7.5%」，报告写「7.52%」——模型用 21338.85/283722.29 自己算了一遍，与官方披露不一致。投资增长 19.5%→19.47%、存款 7.10%、成本收入比 13.63% 同理。

**结论**：DeepSearch 的生成引用（73.3% 命中）远好于裸 LLM，但仍有 1/4 引用不精确 + 派生值自算偏差。**这正是我们 N1–N7/C1–C2 核验内核的用武之地**——"官方引擎写得快写得全，我们的门禁保证数字与官方披露一致"。Phase 1 适配层直接消费这些 `checked_citation` 标记做复验，架构闭环成立。

## 四、踩坑记录（运维知识）

1. **远端 docker build 卡死**：dockerhub 拉镜像 + debian apt 源僵死。解法：先手动 `docker pull python:3.11-slim-bookworm`，构建时 `--build-arg APT_MIRROR=mirrors.tuna.tsinghua.edu.cn`。
2. **SSH 后台任务被杀**：nohup 也会随会话退出被杀，必须 `setsid bash script.sh < /dev/null`。
3. **本地 GitHub 断网**：git bundle 传远端中转 push（远端可达 GitHub），token 临时文件用完即删。
4. **DeepSearch 素材上限 50 条**：109 页须合并分组；`url` 必须绝对路径；content 单条建议 ≤30k 字符（系统有 map-reduce 摘要）。
5. **web/local 检索配置强制二选一**：材料模式也必须传 `web_search_config`（服务端校验 `agent.py`），材料走 metadata。
6. **API 结构**：`POST /api/v1/agent/deepsearch/run/`，space_id 在 body；SSE 流式；最终报告在 `end` agent 事件的 `response_content`（JSON 字符串内嵌）。

## 五、下一步（Phase 1 输入已就绪）

1. **A1 适配层**：`deepsearch_adapter.py` 解析 `checked_citation` 标记 → ReportArtifact（真实样本已在 `/tmp/final_report_clean.md` 与本仓 artifacts）；
2. **引用重定位**：未命中 84 条反查真实页码（全库搜索数字即得），作为适配层自动修复步骤；
3. **派生值核验**：对「自算百分比」用 N4 派生复算规则 + 官方披露优先策略（材料原文有值必须用原文值）；
4. **真实搜索 key**：拿到 bocha key 后跑「检索增强」模式，验证取数广度（Web 证据 + 本地材料融合）。

## 六、产出物

- 成品报告：`artifacts/phase0_deepsearch_icbc_report.md`（16.4 万字，工行 2025 年报，632 引用）
- 冒烟脚本：`benchmark/phase0_smoke.py`（health/引擎/模板/材料模式全流程）
- 事件流样本：远端 `/tmp/phase0_mat_report.md.events`（12.5MB，Phase 1 适配层开发的真实输入）
