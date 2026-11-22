/*
 * FrameworkAnalystAgent — 分析类 agent 的框架原生实现（S13 整改第一批）。
 *
 * 背景（合规差距）：finance/risk/capital-analyst 此前是 checker 里的字符串拼接函数
 * （ins.append(value).append(unit)），冒称 "分析 Agent" 却无任何推理。本类用 openJiuwen
 * 框架原生 ReActAgent 重建——真调 LLM 做归因推理，符合框架规范。
 *
 * 验证依据：ReactSpike 实测 ReActAgent.configureModelClient("OpenAI",key,base,model,false)
 * + configurePrompt + configureMaxIterations + AgentSession.createAgentSession + invoke
 * 返回 {output=...}，qwen-plus 真推理可用（spike 备份于 /home/ubuntu/w06-spike-ref/）。
 *
 * fail-safe：构造或调用失败 → 返回 null，调用方降级回确定性拼接（不阻断流水线）。
 */
package com.agenthub.w06.orchestrator.agent;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.session.AgentSession;

import java.util.List;
import java.util.Map;

public final class FrameworkAnalystAgent {

    private final ReActAgent agent;
    private final String agentId;
    private final String role;

    /** 按角色构造框架原生 ReActAgent。role ∈ {finance, risk, capital}。 */
    public FrameworkAnalystAgent(String agentId, String role, String provider,
                                 String apiKey, String apiBase, String model, int maxIterations) {
        this.agentId = agentId;
        this.role = role;
        ReActAgentConfig cfg = new ReActAgentConfig()
                .configureModelClient(provider, apiKey, apiBase, model, false)
                .configurePrompt(systemPrompt(role))
                .configureMaxIterations(maxIterations);
        AgentCard card = AgentCard.builder()
                .id(agentId)
                .name(displayName(role))
                .description(description(role))
                .build();
        this.agent = new ReActAgent(card).configure(cfg);
    }

    /**
     * 对一组指标做归因分析推理。
     * @param company   主体名称
     * @param metricsBrief 指标摘要（已由调用方拼装成"名称 值 单位 同比"文本）
     * @return 分析结论文本；失败返回 null（调用方降级）
     */
    public String analyze(String company, String metricsBrief) {
        try {
            AgentSession session = AgentSession.createAgentSession(
                    "w06-" + agentId + "-" + java.util.UUID.randomUUID().toString().substring(0, 8), null, null);
            String userMsg = "【铁律·防幻觉】① 只能引用下方明确给出的指标数值；"
                    + "② 严禁引入任何未提供的数字——包括监管阈值、行业均值、历史数据、同业数据，"
                    + "哪怕你确信其正确（例如不得写\"高于监管要求 7.5%\"）；"
                    + "③ 需要参照系时只做定性表述（如\"处于较高水平\"）；④ 每个判断须可追溯到某个指标名。\n\n"
                    + "主体：" + company + "\n指标数据（值与口径以此为准，不得修改或推算）：\n"
                    + metricsBrief + "\n\n请按你的职责输出分析结论（只输出分析文本）。";
            Object out = agent.invoke(userMsg, session);
            if (out == null) return null;
            if (out instanceof Map<?, ?> m && m.get("output") != null) return String.valueOf(m.get("output"));
            return String.valueOf(out);
        } catch (Throwable e) {
            System.err.println("[FrameworkAnalystAgent:" + agentId + "] analyze failed: " + e.getMessage());
            return null;
        }
    }

    private static String displayName(String role) {
        return switch (role) {
            case "finance" -> "财务分析 Agent";
            case "risk" -> "风险与拨备 Agent";
            case "capital" -> "资本管理 Agent";
            default -> role + " Agent";
        };
    }

    private static String description(String role) {
        return switch (role) {
            case "finance" -> "基于给定财务指标做归因分析（营收/利润/息差结构），框架原生 ReActAgent";
            case "risk" -> "基于资产质量指标做风险研判（不良率/拨备覆盖率/迁徙），框架原生 ReActAgent";
            case "capital" -> "基于资本指标做充足性与结构分析（核心一级/总资本充足率），框架原生 ReActAgent";
            default -> "分析 Agent";
        };
    }

    private static String systemPrompt(String role) {
        String head = "你是银行业资深分析师，职责是" + displayName(role) + "。\n"
                + "【铁律·防幻觉】① 只能引用用户在本轮明确提供的指标数值；② 严禁引入任何输入之外的数字——"
                + "包括监管阈值（如资本充足率红线）、行业均值、历史数据、其他银行数据等一切\"常识数字\"，"
                + "哪怕你确信其正确；③ 需要参照系时，只做定性表述（如\"处于较高水平\"），不得给出具体数值；"
                + "④ 每个判断必须能追溯到某个给定指标名。违反任一条即为不合格输出。\n"
                + "【铁律·角色聚焦】用户会给你全部指标，但你只从" + displayName(role) + "的视角解读——"
                + "聚焦与你职责相关的指标深入分析，与本职无关的指标最多一句带过或不提；"
                + "输出必须体现你这一角色的独有专业判断，不得与其他角色雷同地罗列全部指标。\n\n";
        String body = switch (role) {
            case "finance" -> "【聚焦盈利与效率】只看营收、净利润、净息差、成本收入比等盈利效率类指标。"
                    + "分析：① 营收与利润的量价归因（息差变动如何影响净利息收入）；② 盈利效率趋势。"
                    + "不要分析资产质量或资本充足（那是其他分析师的职责）。输出 3-5 句，每句标注支撑指标名。";
            case "risk" -> "【聚焦资产质量】只看不良贷款率、拨备覆盖率、信用减值损失等风险类指标。"
                    + "分析：① 不良率水平与资产质量；② 拨备覆盖率的风险缓冲充足性；③ 减值计提的审慎性。"
                    + "不要分析盈利结构或资本充足（那是其他分析师的职责）。输出 3-5 句，每句标注支撑指标名。";
            case "capital" -> "【聚焦资本充足】只看核心一级资本充足率、总资本充足率、净资产收益率等资本类指标。"
                    + "分析：① 资本充足水平与缓冲；② ROE 反映的资本回报效率；③ 资本对业务扩张的支撑能力。"
                    + "不要分析盈利结构或资产质量（那是其他分析师的职责）。输出 3-5 句，每句标注支撑指标名。";
            default -> "对给定指标做专业分析。";
        };
        return head + body;
    }
}
