# Phase 1 适配层设计：DeepSearch 输出 → 核验门禁

> 前提：Phase 0 冒烟已确认服务可用。本文设计「生成方（DeepSearch）」与「核验方（我们）」之间的契约映射，原则：**生成方不知道核验方存在，核验方只消费结构化中间产物**。

## 1. 总体数据流

```
DeepSearch run (SSE)
  ├─ final_result (dict)  ←── 工作流最终结果快照(含报告+引用+大纲)
  ├─ 报告 Markdown (含 inline 引用标记)
  └─ /reports/convert → HTML/DOCX (交付形态)

        │ 适配层(我们写)
        ▼
ReportArtifact (我们的契约, 领域无关版)
  ├─ metrics[]     ← 数值主张(主体/值/单位/期间/证据摘要digest)
  ├─ sections[]    ← 章节(标题/正文/引用锚点)
  ├─ citations[]   ← 引用(claimId/证据摘录/来源URL/chunkId/digest)
  └─ report_type   ← annual_report | research | client_analysis

        │ 核验门禁(现有, 已泛化)
        ▼
  N1–N7 数值核验 (verify_metrics_generic.py, 读 metric registry)
  C1–C2 引用核验 (SemanticSupportAssessor + 字面命中)
        │
        ▼
  fail-closed: BLOCKING violation → 拦截交付
  通过 → 批准(指纹绑定) → 导出(DeepSearch /reports/convert HTML/DOCX)
```

## 2. 适配层职责（一个 Python 模块 `deepsearch_adapter.py`）

### 2.1 引用抽取
DeepSearch 的 chunk 级引用在 `final_result` 中以结构化形式存在（引用列表含 source URL/标题/片段）。适配层做三件事：
1. **摘录映射**：把 chunk 片段映射为我们 `citations[].evidence_excerpt`，digest=sha256(摘录)；
2. **来源定位**：URL+chunkId 替代页码（`page` 字段泛化为 `locator`：`{type: page|url_chunk, ...}`）；
3. **正文锚点**：扫描报告 Markdown 的 inline 引用标记，建立 claim→citation 关联。

### 2.2 数值主张抽取
从报告正文 + DeepSearch 检索片段中抽取数值主张 `(主体词, 数值, 单位, 期间)`：
- 用 SemanticSupportAssessor 同源的主体词表（从 report-profiles 注入，领域无关）；
- 数值必须落到某个 citation 的证据摘录上，否则标 `ORPHAN_NUMBER`（警告级，转人工）。

### 2.3 证据落盘与核验衔接
- DeepSearch 引用的网页/chunk 内容缓存到本地 evidence store（复用我们的 snapshot 目录结构）；
- C1 字面命中检查改为「摘录 ∈ 缓存 chunk 文本」（URL 源）或「摘录 ∈ 快照页文本」（本地源）——核验逻辑不变，只是文本来源变了。

## 3. 契约变更（最小化）

现有 `ReportArtifact` 契约只需两处扩展：

```json
{
  "report_type": "research",
  "evidence_store": {
    "type": "deepsearch_chunks",
    "items": [{"locator": {"url": "...", "chunk_id": "..."}, "text": "...", "digest": "sha256..."}]
  },
  "citations": [
    {
      "claim_id": "C-1",
      "claim_text": "...",
      "evidence_excerpt": "...",
      "evidence_excerpt_digest": "sha256...",
      "locator": {"type": "url_chunk", "url": "...", "chunk_id": "..."}
    }
  ]
}
```

- `locator.type=page` 时走现有页码逻辑（年报快照），`=url_chunk` 走 chunk 文本命中；
- Java `AnnualReportArtifacts` 加 `@JsonIgnoreProperties(ignoreUnknown=true)` 已具备向前兼容（历史上为 tables 做过同样扩展）。

## 4. 核验规则映射（不变的部分）

| 规则 | 现有实现 | 适配后 |
|---|---|--- Citation | C1 字面命中 | 摘录 ∈ 快照页文本 | 摘录 ∈ evidence_store 对应 chunk 文本（digest 先行校验） |
| C2 语义支持度 | 最近主体词归属匹配 | **不变**（主体词表来自 report-profiles） |
| N1 数值在证据中 | 数字 ∈ 证据摘录 | 不变（证据摘录来自 chunk） |
| N2 单位一致 | registry expected_unit | 不变 |
| N4 派生复算 | registry derived 公式 | 不变 |
| 门禁 | BLOCKING → 拦截 | 不变 |

**关键点：核验器零改动或近零改动**——这正是把核验做成独立层的回报。

## 5. 与 fail-closed 的交互

- DeepSearch 报告中**无法溯源的数字**（没有落在任何引用 chunk 上）→ 适配层标 `ORPHAN_NUMBER`，视配置转 WARNING（转人工）或 BLOCKING（严格模式）。默认严格模式，与年报基线一致；
- 模板章节 vs 实际产出章节的一致性检查（新增 N8 候选）：DeepSearch 按模板生成后，校验产出章节集合 ⊆ 模板章节集合，缺节 BLOCKING（对应我们年报的"缺节续写"兜底语义）。

## 6. 实施清单

| # | 任务 | 产出 | 依赖 |
|---|---|---|---|
| A1 | `deepsearch_adapter.py`：final_result → ReportArtifact | 适配模块 + 单测 | Phase 0 实跑拿到真实 final_result 样本 |
| A2 | evidence store 落盘 | URL/chunk 缓存目录 + digest 索引 | A1 |
| A3 | C1 命中检查扩展 locator.type | CitationChecker/verify_citation 泛化 | A1 |
| A4 | ORPHAN_NUMBER 规则 | 核验规则 + 反例测试 | A1 |
| A5 | N8 章节完整性检查 | 核验规则 + 测试 | A1 |
| A6 | 端到端串联脚本 | 生成→适配→核验→门禁→导出 一条命令 | A1-A5 |

**A1 依赖 Phase 0 实跑拿到真实 final_result 结构**——这是当前阻塞项，等部署完成。
