/*
 * OrchestratorAgentFactory — W06 root 编排 agent（库层，无 Spring）
 *
 * 照 multi-deep-research-demo 的 DeepResearchAgentFactory 模式：
 *  - root DeepAgent + Rails；五个角色子 agent 与 critic 经 A2A remote-agents 注入
 *    （wrapper 层 application.yml 的 openjiuwen.service.a2a.remote-agents）；
 *  - 与上游的差异：GatekeepRail（fail-closed 交付门禁）替换 best-effort verify。
 */

package com.agenthub.w06.orchestrator.agent;

import com.agenthub.w06.contract.AnnualReportArtifacts;

import com.agenthub.w06.checker.CitationChecker;
import com.agenthub.w06.checker.NumericChecker;
import com.agenthub.w06.orchestrator.rail.GatekeepRail;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.nio.file.Path;
import java.util.List;

public final class OrchestratorAgentFactory {

    public static DeepAgent build(OrchestratorProperties props,
                                  NumericChecker.SnapshotTextResolver resolver,
                                  Path workspaceRoot) {
        props.requireConfigured();

        // 门禁 rail：checker hook = 确定性数值核验 + 引用核验（语义层由 critic 子 agent 补）
        GatekeepRail gatekeep = new GatekeepRail(workspaceRoot, props.getGatekeepMaxRounds());
        gatekeep.setCheckerHook(draft -> List.of(
                new NumericChecker().check(draft, resolver),
                new CitationChecker().check(draft, resolver)));

        // S14：MCP 合规接入——deepreport-capability-mcp（官方 FastMCP streamable-http）
        // 经框架 DeepAgentConfig.mcps(McpServerConfig) 注册为 root DeepAgent 的原生工具，
        // 不再是"只声明未接入"的孤岛。MCP_URL 可配；disabled 显式关闭。
        String mcpUrl = System.getenv().getOrDefault("DEEPREPORT_MCP_URL",
                "http://deepreport-mcp:8090/mcp");
        // S15.2：联网工具 MCP（web_search/web_fetch/pdf_extract/snapshot_commit）一并注册
        String webtoolUrl = System.getenv().getOrDefault("DEEPREPORT_WEBTOOL_MCP_URL",
                "http://deepreport-webtool-mcp:8091/mcp");
        java.util.List<com.openjiuwen.core.foundation.tool.mcp.McpServerConfig> mcps = new java.util.ArrayList<>();
        if (!"disabled".equalsIgnoreCase(mcpUrl)) {
            mcps.add(com.openjiuwen.core.foundation.tool.mcp.McpServerConfig.builder()
                    .serverId("deepreport-capability-mcp")
                    .serverName("DeepReport 能力 MCP 服务")
                    .serverPath(mcpUrl)
                    .clientType("streamable-http")
                    .connectTimeoutSeconds(10.0)
                    .callTimeoutSeconds(60.0)
                    .build());
        }
        if (!"disabled".equalsIgnoreCase(webtoolUrl)) {
            mcps.add(com.openjiuwen.core.foundation.tool.mcp.McpServerConfig.builder()
                    .serverId("deepreport-webtool-mcp")
                    .serverName("DeepReport 联网工具 MCP 服务")
                    .serverPath(webtoolUrl)
                    .clientType("streamable-http")
                    .connectTimeoutSeconds(10.0)
                    .callTimeoutSeconds(120.0)
                    .build());
        }

        DeepAgentConfig config = DeepAgentConfig.builder()
                .mcps(mcps)
                .systemPrompt(systemPrompt())
                .maxIterations(props.getMaxIterations())
                .enableTaskLoop(true)
                .enableTaskPlanning(true)
                .completionTimeout((double) props.getCompletionTimeout().toSeconds())
                .workspacePath(props.getWorkspacePath())
                .model(props.modelConfig())
                .backend(props.backendConfig())
                .rails(List.of(gatekeep))
                .build();
        Workspace workspace = Workspace.builder()
                .rootPath(props.getWorkspacePath())
                .language("zh-CN")
                .build();
        AgentCard card = AgentCard.builder()
                .id("w06-orchestrator")
                .name("AnnualReportOrchestrator")
                .description("W06 年报报告编排：资料→指标→双分析并行→写作→确定性+语义双门禁→交付/修订/人工")
                .build();
        return HarnessFactory.createDeepAgent(card, config, workspace);
    }

    private static String systemPrompt() {
        return """
                你是 DeepReport 主智能体（主编编排器）。全系统唯一主控：受理任务、编排专家、对交付质量负责。

                ## 核心编排原则（最高优先级）
                【自主取数是你的职责，不是用户的责任】用户只给任务目标，不给资料是常态。
                资料不足时禁止向用户索要资料——你的专家里有 deep-search（联网调研），
                派它去搜。只有当你明确请求过信源、且 deep-search 多轮搜索后仍无权威信源时，
                才可向用户请求人工补充（如实说明已尝试的搜索轮次与不足之处）。

                ## 编排流程
                1. 理解任务：判定报告类型（年报域 bank 快照已有冻结资料；通用/研究域默认无资料）。
                2. 取数编排：
                   - 年报域：调 run_pipeline（bank=cmb/icbc/ccb），冻结快照已就绪；
                   - 通用/研究域：先确认深搜预算（run_pipeline 内置 deep-search 多轮联网：
                     规划查询→搜索→抓取→snapshot_commit 落盘注册→评估充分性，最多 3 轮），
                     直接触发 run_pipeline——搜索是流水线第一工位的一部分，不要单独索要资料。
                3. 执行：调 run_pipeline（report_type=general/research + subject_hint=主题），
                   传 plan_summary=你的编排计划摘要（选了哪些专家、为什么）。
                4. 门禁：等待 CONTRACT_VERDICT。PASSED → 宣布完成；REVISION_REQUESTED →
                   按修订指令重跑一次；WAITING_HUMAN → 如实报告等待原因与已尝试的手段，
                   禁止自行放行。
                5. 用户主动给资料/PDF/URL：欢迎——如实告知会经 snapshot_commit 注册后使用；
                   但这始终是可选捷径，不是编排前置条件。

                ## 铁律
                - 报告里任何数字必须来自流水线产物（快照或已落盘注册的信源）；
                - 禁止口述数字、禁止虚构指标；
                - 搜索不到权威信源时如实降级（标注资料范围），不得以"没资料"为由拒绝整个任务。
                - 【最高优先级】报告正文的唯一合法来源是 run_pipeline 返回的工件。你在对话中
                  只能输出：结论摘要、工件指纹、门禁结论、下一步建议——禁止在对话里用你的
                  "专业知识"直接编写报告正文/章节内容（哪怕内容看起来真实）。看起来真实但
                  未经信源落盘的正文，与虚构无异。
                """;
    }
}
