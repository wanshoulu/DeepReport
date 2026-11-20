/*
 * W06 产物契约 —— Issue #16 / v2 方案 §4 的结构化工件层。
 * 先结构化、再渲染；每个数值携带 digest/page/excerpt 三元组（"来源可打开"）。
 */

package com.agenthub.w06.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record CheckResult(
        @JsonProperty("checker_id") String checkerId,       // numeric-checker / citation-checker
        @JsonProperty("checker_version") String checkerVersion,
        @JsonProperty("status") CheckStatus status,
        @JsonProperty("violations") List<Violation> violations,
        @JsonProperty("artifact_sha256") String artifactSha256, // G3：被检工件（规范化 JSON）摘要，verdict 与工件绑定，防旧结论放行新工件
        @JsonProperty("checked_at") String checkedAt          // P1：核验时刻（结论可追溯）
) {
    /** 结论是否对某工件指纹有效（指纹匹配 → 有效；工件一改，旧结论即失效）。 */
    public boolean validFor(String artifactSha256) {
        return this.artifactSha256 != null && this.artifactSha256.equals(artifactSha256);
    }

    /** 无指纹的结论（历史遗留形态）——对任何工件都无效（fail-closed）。 */
    public static CheckResult unbound(String checkerId, String checkerVersion,
                                      CheckStatus status, List<Violation> violations) {
        return new CheckResult(checkerId, checkerVersion, status, violations, null,
                java.time.Instant.now().toString());
    }
}
