/*
 * WriterAgentFactory — 综合报告角色（W06）
 *
 * 只能消费两路已提交(committed)的分析草稿与指标 artifact（v2 §3.3：
 * "只能消费正式 artifacts；修订输出新 digest"），输出 AnnualReportArtifacts
 * 结构化工件——先结构化、再渲染（#16 D4 口径）。
 */

package com.agenthub.w06.agents.writer;

import com.agenthub.w06.contract.AnnualReportArtifacts;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.List;
import java.util.Map;

public final class WriterAgentFactory {

    public static ReActAgent build(WriterAgentProperties props) {
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
                .name("WriterAgent")
                .description("综合报告角色：合并两路已提交分析，产出结构化 AnnualReportArtifacts JSON")
                .build();
        ReActAgent agent = new ReActAgent(card);
        agent.configure(config);
        return agent;
    }

    private static String systemPrompt() {
        return """
                你是年报主笔。输入：两路已提交的分析草稿（各带引用）+ 八指标结构化 artifact。
                
                输出契约（严格 JSON，符合 AnnualReportArtifacts schema，无其他文字）：
                {"task_id":"<输入>","company":"<输入>","report_period":"<输入>",
                 "metrics":[<原样转抄八指标，禁止改动任何字段>],
                 "citations":[<两路草稿引用合并去重，claim_id 保留原编号>],
                 "sections":[{"section_id":"s1","title":"...","body":"...","claim_ids":[...]}]}
                 
                铁律：
                1. metrics 数组必须原样转抄输入 artifact 的八条记录，一个字符都不许改
                   （改数字/单位会在 numeric-checker 被 FAIL）；
                2. 正文论断只能来自两路草稿；不得新增无引用论断；
                3. 每条论断保留 [C-n] 引用标记，claim_ids 与正文一致；
                4. 定向修订模式（输入含 REVISION_DIRECTIVE 时）：只修改指令指定的论断，
                   其余章节原样保留；新论断必须带新引用。
                """;
    }
}
