/*
 * OperationAgent / AssetQualityAgent — 双分析角色（W06）
 *
 * 隔离要求（v2 §3.3 / #16 正文）：两个分析分支并行，彼此不得读取对方未提交结果。
 * 本实现把隔离做在两处：
 *  1. 输入面：构造时只注入本分支允许消费的 metric 子集（operation 拿经营类指标，
 *     asset-quality 拿资产质量类指标），另一分支的指标根本不进入 prompt 上下文；
 *  2. 输出面：分析产出的是带 claim 标注的 SectionDraft，只能引用注入的指标，
 *     CitationChecker 会核对每条引用是否落在允许集合内（越界引用=FAIL）。
 */

package com.agenthub.w06.agents.operation;

import com.agenthub.w06.contract.MetricKey;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OperationAgentFactory {

    /** 经营分析分支允许消费的指标（越界引用由 citation-checker 拦截）。 */
    public static final Set<MetricKey> ALLOWED_METRICS = Set.of(
            MetricKey.OPERATING_REVENUE,
            MetricKey.NET_PROFIT_ATTRIBUTABLE,
            MetricKey.NET_INTEREST_MARGIN,
            MetricKey.COST_INCOME_RATIO);

    public static ReActAgent build(AnalysisAgentProperties props) {
        props.requireConfigured();
        ReActAgentConfig config = ReActAgentConfig.builder()
                .maxIterations(props.getMaxIterations())
                .sysOperationId(props.getSysOperationId())
                .promptTemplate(List.of(Map.of("role", "system", "content",
                        analysisPrompt("经营分析", ALLOWED_METRICS))))
                .build()
                .configureModelClient(
                        props.getProvider(), props.getApiKey(), props.getApiBase(),
                        props.getModelName(), props.isSslVerify());
        AgentCard card = AgentCard.builder()
                .id(props.getAgentId())
                .name("OperationAnalysisAgent")
                .description("经营分析角色：基于经营类指标产出带引用的分行分析草稿")
                .build();
        ReActAgent agent = new ReActAgent(card);
        agent.configure(config);
        return agent;
    }

    public static String analysisPrompt(String role, Set<MetricKey> allowed) {
        StringBuilder keys = new StringBuilder();
        allowed.forEach(k -> keys.append("- ").append(k.name())
                .append("（").append(k.getDisplayName()).append("）\n"));
        return """
                你是银行年报%s专员。输入是指标抽取 agent 的结构化结果（仅限下列指标）：
                %s
                输出契约（Markdown，论断以 [C-n] 编号）：
                ## %s
                <正文：每条论断后紧跟引用标记 [C-n]，n 全局递增>
                ## 引用
                - [C-n] digest=<source_document_digest> page=<页码> excerpt="<支撑原文>"
                
                铁律：
                1. 只能使用输入中给出的指标值；禁止出现上表之外的任何数字（同比等派生值
                   必须标注"基于 [C-k] 计算"并给出算式）；
                2. 每条论断必须恰好绑定一条引用；无法支撑的判断删除，不得写；
                3. 指标缺失时写"数据缺失：<指标名>"，禁止编造。
                """.formatted(role, keys, role);
    }
}
