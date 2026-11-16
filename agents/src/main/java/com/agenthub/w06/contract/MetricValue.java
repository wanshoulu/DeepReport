/*
 * W06 产物契约 —— Issue #16 / v2 方案 §4 的结构化工件层。
 * 先结构化、再渲染；每个数值携带 digest/page/excerpt 三元组（"来源可打开"）。
 */

package com.agenthub.w06.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record MetricValue(
        @JsonProperty("key") MetricKey key,
        @JsonProperty("value") String value,            // 原文口径字符串，如 "3374.88"
        @JsonProperty("unit") String unit,              // 亿元 / % 等，错误单位是 A07 反例
        @JsonProperty("period") String period,          // 如 "2025年度" / "2024年度"
        @JsonProperty("source_document_digest") String sourceDocumentDigest,  // 快照 manifest 内的 sha256
        @JsonProperty("page") int page,                 // 页码，来源可打开验收项
        @JsonProperty("evidence_excerpt_digest") String evidenceExcerptDigest // 证据片段 sha256
) {}

/** 引用条目 —— 一条论断 ↔ 一处证据的绑定。 */
