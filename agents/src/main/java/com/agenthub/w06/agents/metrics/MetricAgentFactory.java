/*
 * MetricAgent — 指标抽取角色（W06）
 *
 * 数字纪律的第一道关口：八指标的结构化抽取只能由本 agent 从 MaterialAgent
 * 返回的原文片段完成，输出必须是 MetricValue JSON —— value/unit/period/digest/page
 * 五元组齐全。下游 NumericChecker 会用确定性规则复算（如 净息差=利息净收入/生息资产
 * 的口径核对、单位换算），writer 口述数字与本 artifact 不一致一律 FAIL。
 */

package com.agenthub.w06.agents.metrics;

import com.agenthub.w06.contract.MetricKey;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.List;
import java.util.Map;

public final class MetricAgentFactory {

    public static ReActAgent build(MetricAgentProperties props) {
        props.requireConfigured();
        ReActAgentConfig config = ReActAgentConfig.builder()
                .maxIterations(props.getMaxIterations())
                .sysOperationId(props.getSysOperationId())
                .promptTemplate(List.of(Map.of("role", "system", "content", systemPrompt())))
                .build()
                .configureModelClient(
                        props.getProvider(), props.getApiKey(), props.getApiBase(),
                        props.getModelName(), props.isSslVerify());
        AgentCard card = AgentCard.builder()
                .id(props.getAgentId())
                .name("MetricAgent")
                .description("指标角色：从资料 agent 的原文片段抽取八指标结构化值，输出 MetricValue JSON 数组")
                .build();
        ReActAgent agent = new ReActAgent(card);
        agent.configure(config);
        return agent;
    }

    private static String systemPrompt() {
        // 八指标枚举在 prompt 里显式列出，避免模型自由发挥指标集
        StringBuilder keys = new StringBuilder();
        for (MetricKey k : MetricKey.values()) {
            keys.append("- ").append(k.name()).append("（").append(k.getDisplayName()).append("）\n");
        }
        return """
                你是银行年报指标抽取专员。输入是资料 agent 返回的原文片段（带 digest/page）。
                
                固定八项指标（不得增删）：
                %s
                输出契约（严格 JSON，无其他文字）：
                [{"key":"<枚举名>","value":"<原文口径字符串>","unit":"<原文单位>","period":"<期间>",
                  "source_document_digest":"<输入片段的 digest>","page":<页码>,
                  "evidence_excerpt_digest":"<引用片段的 sha256，由输入给出>"}]
                  
                铁律：
                1. value 只能照抄原文数字（含千分位），禁止换算、四舍五入或推算；
                2. unit 用原文单位（亿元/% 等）；原文未写单位则输出 "UNSPECIFIED"，禁止猜；
                3. 找不到的指标输出该条并标 value="NOT_FOUND"，禁止编造数字；
                4. 每条必须携带输入片段的 digest 与 page；无来源的指标禁止输出。
                """.formatted(keys);
    }
}
