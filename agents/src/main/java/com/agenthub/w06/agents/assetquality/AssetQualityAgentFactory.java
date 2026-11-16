/*
 * AssetQualityAgentFactory — 资产质量分析角色（W06）
 * 与 OperationAgentFactory 同构；差异仅在允许指标集合（隔离边界的另一半）。
 */

package com.agenthub.w06.agents.assetquality;

import com.agenthub.w06.contract.MetricKey;
import com.agenthub.w06.agents.operation.OperationAgentFactory;
import com.agenthub.w06.agents.operation.AnalysisAgentProperties;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AssetQualityAgentFactory {

    /** 资产质量分支允许消费的指标（与经营分支互斥，交集为空=硬隔离）。 */
    public static final Set<MetricKey> ALLOWED_METRICS = Set.of(
            MetricKey.NPL_BALANCE,
            MetricKey.NPL_RATIO,
            MetricKey.PROVISION_COVERAGE,
            MetricKey.CET1_RATIO);

    public static ReActAgent build(AnalysisAgentProperties props) {
        props.requireConfigured();
        ReActAgentConfig config = ReActAgentConfig.builder()
                .maxIterations(props.getMaxIterations())
                .sysOperationId(props.getSysOperationId())
                .promptTemplate(List.of(Map.of("role", "system", "content",
                        OperationAgentFactory.analysisPrompt("资产质量分析", ALLOWED_METRICS))))
                .build()
                .configureModelClient(
                        props.getProvider(), props.getApiKey(), props.getApiBase(),
                        props.getModelName(), props.isSslVerify());
        AgentCard card = AgentCard.builder()
                .id(props.getAgentId())
                .name("AssetQualityAnalysisAgent")
                .description("资产质量分析角色：基于资产质量类指标产出带引用的分行分析草稿")
                .build();
        ReActAgent agent = new ReActAgent(card);
        agent.configure(config);
        return agent;
    }
}
