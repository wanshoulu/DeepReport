/*
 * FrameworkAnalystAgent — 分析类 agent 的框架原生 Workflow 实现（S13.2 形态修正）。
 *
 * 形态判定（用户纠偏后基于任务特点选定）：分析工位 = 「输入指标 → LLM 归因 → 输出结论」
 * 的确定性单步流程，无工具、无自主控制流——本质是 Workflow（单 LLM 节点），不是 ReAct。
 * 此前用 ReActAgent 实现"能用但不贴切"，本版改为框架原生 Workflow。
 *
 * 数据流契约（spike 实测破解，源码 vertex.py:421-432/end_comp.py:426 佐证）：
 *   - add_connection 的边只定执行顺序，不传数据；
 *   - 数据经 inputs_schema 的 ${comp_id.field} 引用从 io_state 流动；
 *   - Start 也要 ${顶层键} 领取用户输入；End 先映射再 {{键}} 模板。
 *
 * fail-safe：构造或调用失败 → 返回 null，调用方降级确定性拼接（不阻断流水线）。
 */
package com.agenthub.w06.orchestrator.agent;

import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.WorkflowCard;
import com.openjiuwen.core.workflow.WorkflowOutput;
import com.openjiuwen.core.workflow.components.flow.StartComponent;
import com.openjiuwen.core.workflow.components.flow.EndComponent;
import com.openjiuwen.core.workflow.components.llm.LLMComponent;
import com.openjiuwen.core.workflow.components.llm.LLMCompConfig;
import com.openjiuwen.core.workflow.component.EndConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.session.internal.WorkflowSession;

import java.util.List;
import java.util.Map;

public final class FrameworkAnalystAgent {

    private final LLMCompConfig llmCfg;
    /** S13.4：中性单 LLM 配置（无角色 system prompt，user={{input}} 直通）——供 chatRaw 走 Workflow。 */
    private final LLMCompConfig rawCfg;
    private final String agentId;
    private final String role;

    public FrameworkAnalystAgent(String agentId, String role, String provider,
                                 String apiKey, String apiBase, String model, int maxTokens) {
        this.agentId = agentId;
        this.role = role;
        ModelRequestConfig reqCfg = ModelRequestConfig.builder()
                .modelName(model).temperature(0.3).maxTokens(maxTokens).build();
        ModelClientConfig cliCfg = ModelClientConfig.builder()
                .clientId("w06-" + agentId).clientProvider(provider)
                .apiKey(apiKey).apiBase(apiBase)
                .connectTimeoutSeconds(10.0).streamIdleSeconds(300.0).verifySsl(false).build();
        this.llmCfg = LLMCompConfig.builder()
                .modelConfig(reqCfg).modelClientConfig(cliCfg)
                .templateContent(List.of(
                        Map.of("role", "system", "content", systemPrompt(role)),
                        Map.of("role", "user", "content", "{{input}}")))
                .responseFormat(Map.of("type", "text"))
                .outputConfig(Map.of("analysis", Map.of("type", "string", "description", "分析结论")))
                .build();
        this.rawCfg = LLMCompConfig.builder()
                .modelConfig(reqCfg).modelClientConfig(cliCfg)
                .templateContent(List.of(Map.of("role", "user", "content", "{{input}}")))
                .responseFormat(Map.of("type", "text"))
                .outputConfig(Map.of("analysis", Map.of("type", "string", "description", "输出")))
                .build();
    }

    /** S13.4：原始 prompt 直通（中性 system，不套角色指令）——供 Workflow 的 LLM 节点
     *  （deep-search 查询规划/充分性评估）使用，prompt 自带完整指令。单节点 Workflow，失败返回 null。 */
    public String chatRaw(String prompt) {
        try {
            Workflow wf = new Workflow(new WorkflowCard("wf-raw-" + agentId, "单次 LLM 调用"));
            wf.setStartComp("node_start", new StartComponent(), Map.of("input", "${input}"), null);
            wf.addWorkflowComp("node_llm", new LLMComponent(rawCfg), Map.of("input", "${node_start.input}"), null);
            wf.setEndComp("node_end", new EndComponent(new EndConfig("{{answer}}")),
                    Map.of("answer", "${node_llm.analysis}"), null);
            wf.addConnection("node_start", "node_llm");
            wf.addConnection("node_llm", "node_end");
            WorkflowSession session = new WorkflowSession("w06-raw-"
                    + java.util.UUID.randomUUID().toString().substring(0, 8));
            WorkflowOutput out = wf.invoke(Map.of("input", prompt), session, null);
            if (out == null || out.getResult() == null) return null;
            Object result = out.getResult();
            String text = (result instanceof Map<?, ?> m && m.get("response") != null)
                    ? String.valueOf(m.get("response")) : String.valueOf(result);
            return text.isBlank() ? null : text;
        } catch (Throwable e) {
            System.err.println("[FrameworkAnalystAgent:" + agentId + "] chatRaw failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 对一组（本角色域的）指标做归因分析。每次调用新建 Workflow 实例（无状态、线程安全）。
     * @return 分析结论文本；失败返回 null（调用方降级）
     */
    public String analyze(String company, String metricsBrief) {
        try {
            Workflow wf = new Workflow(new WorkflowCard("wf-" + agentId, displayName(role) + "工作流"));
            // 契约：Start 用 ${顶层键} 领取 invoke 输入
            wf.setStartComp("node_start", new StartComponent(), Map.of("metrics", "${metrics}"), null);
            // 契约：LLM 节点用 ${node_start.metrics} 引用上游
            wf.addWorkflowComp("node_llm", new LLMComponent(llmCfg), Map.of("input", "${node_start.metrics}"), null);
            // 契约：End 先映射 ${node_llm.analysis} 再 {{answer}} 模板渲染
            wf.setEndComp("node_end", new EndComponent(new EndConfig("{{answer}}")),
                    Map.of("answer", "${node_llm.analysis}"), null);
            wf.addConnection("node_start", "node_llm");
            wf.addConnection("node_llm", "node_end");

            WorkflowSession session = new WorkflowSession("w06-" + agentId + "-"
                    + java.util.UUID.randomUUID().toString().substring(0, 8));
            String userMsg = "general".equals(role)
                    ? "报告主题：" + company + "\n调研资料摘录（事实与数字以此为准）：\n" + metricsBrief
                            + "\n请按你的职责输出分析结论（只输出分析文本）。"
                    : "主体：" + company + "\n指标数据（值与口径以此为准）：\n" + metricsBrief
                            + "\n请按你的职责输出分析结论（只输出分析文本）。";
            WorkflowOutput out = wf.invoke(Map.of("metrics", userMsg), session, null);
            if (out == null || out.getResult() == null) return null;
            Object result = out.getResult();
            String text;
            if (result instanceof Map<?, ?> m && m.get("response") != null) {
                text = String.valueOf(m.get("response"));
            } else {
                text = String.valueOf(result);
            }
            return text.isBlank() ? null : text;
        } catch (Throwable e) {
            System.err.println("[FrameworkAnalystAgent:" + agentId + "] workflow failed: " + e.getMessage());
            return null;
        }
    }

    private static String displayName(String role) {
        return switch (role) {
            case "finance" -> "财务分析 Agent";
            case "risk" -> "风险与拨备 Agent";
            case "capital" -> "资本管理 Agent";
            case "general" -> "通用主题分析 Agent";
            default -> role + " Agent";
        };
    }

    private static String systemPrompt(String role) {
        if ("general".equals(role)) {
            // S15.1：通用域主题分析——不绑银行域，材料是调研资料摘录而非银行指标
            return "你是资深研究分析师，职责是" + displayName(role) + "，擅长对任意主题（科技/行业/公司/政策等）的调研资料做结构化分析。\n"
                    + "【铁律·防幻觉】① 只能引用用户在本轮提供的资料摘录中的事实与数字；② 严禁引入资料之外的任何数字或事实——"
                    + "包括行业均值、历史数据、其他来源数据等一切\"常识数字\"，哪怕你确信其正确；"
                    + "③ 需要参照系时只做定性表述，不得给出具体数值；④ 每个判断须能追溯到资料中的具体内容。\n"
                    + "【分析框架】① 主题现状与关键事实归纳（资料覆盖了什么、核心结论是什么）；"
                    + "② 内在逻辑与因果关系分析（为什么会这样、驱动因素是什么）；"
                    + "③ 风险点与不确定性（资料暴露了哪些隐患、哪些信息缺失）；"
                    + "④ 趋势判断与建议（基于资料可合理推断的方向，明确标注是推断）。"
                    + "输出 5-8 句结构化分析，每个判断后用（据资料：…）标注依据。";
        }
        String head = "你是银行业资深分析师，职责是" + displayName(role) + "。\n"
                + "【铁律·防幻觉】① 只能引用用户在本轮明确提供的指标数值；② 严禁引入任何输入之外的数字——"
                + "包括监管阈值（如资本充足率红线）、行业均值、历史数据、其他银行数据等一切“常识数字”，"
                + "哪怕你确信其正确；③ 需要参照系时，只做定性表述（如“处于较高水平”），不得给出具体数值；"
                + "④ 每个判断必须能追溯到某个给定指标名。\n"
                + "【铁律·角色聚焦】用户会给你本角色域的指标，只从" + displayName(role) + "的视角深入分析，"
                + "输出必须体现本角色的独有专业判断。\n\n";
        String body = switch (role) {
            case "finance" -> "【聚焦盈利与效率】分析：① 营收与利润的量价归因（息差变动如何影响净利息收入）；"
                    + "② 盈利效率趋势（利润增速与营收增速的剪刀差）；③ 成本管控。输出 3-5 句，每句标注支撑指标名。";
            case "risk" -> "【聚焦资产质量】分析：① 不良率水平与资产质量；② 拨备覆盖率的风险缓冲充足性；"
                    + "③ 减值计提的审慎性。输出 3-5 句，每句标注支撑指标名。";
            case "capital" -> "【聚焦资本充足】分析：① 资本充足水平与缓冲；② 资本回报效率；"
                    + "③ 资本对业务扩张的支撑能力。输出 3-5 句，每句标注支撑指标名。";
            default -> "对给定指标做专业分析。";
        };
        return head + body;
    }
}
