# DeepReport Capability MCP Server

一句话：**把成表、成图这类确定性原子能力注册成标准 MCP 工具**，让 Agent 通过 `tools/list` 发现、`tools/call` 调用，而不是在 Skill 里偷偷跑本地脚本。

MCP 即 **Model Context Protocol**（模型上下文协议）：工具必须先注册、以 `name` + `description` + `inputSchema` 暴露给模型，由模型决定何时调用，运行时执行后回传结果。本 Server 是该协议的最小实现（JSON-RPC 2.0，零第三方依赖）。

## 这是什么

| 层 | 在这里是什么 |
|---|---|
| 模板 | `examples/capability-declared-template.md` 的 `capabilities` 声明要什么，以及 `source_of_truth` 指定数据来自哪 |
| Agent | `source/capability-router/router.py`：解析声明 → `tools/list` 发现 → 查路由表 → 依据工具真实返回调整决策 → 回填工件 |
| Skill | `skills/table-composition/` / `skills/chart-composition/`：只写「何时用、参数怎么组织、失败怎么降级」，**不执行** |
| MCP 工具 | 本目录 `capability_server.py`：`table_compose` / `chart_render`，原子执行、带原文证据 |
| 生产网关 | `ToolGateway.java` 的通用 `mcp_bridge` 分支 + `source/config/tools.json` 注册，让 `18083` 也能发现和调用 |
| 门禁 | 声明-交付对账，`required` 未交付即 BLOCKING |

工具定义在 [`tools.json`](tools.json)（唯一事实源）：`tools/list` 返回它，`source/config/tools.json` 注册同名工具，两份字段对得上才能路由。

## 工具

| 工具 | 做什么 | 输入 | 纪律 |
|---|---|---|---|
| `table_compose` | 从材料原文抽指标数值矩阵或条款对照矩阵 | `capability{id,spec}` + `materials[{file,text}]` | 数字照抄原文、附原文摘录与来源 sha256，抽不到记 `extraction_miss` |
| `chart_render` | 渲染四层架构内联 SVG | `capability{id,chart_type}` + `materials[]` | 节点文字必须命中原文，SVG 内联无外链无脚本，未命中层标注「材料未覆盖」 |

当前 `chart_render` 只实现 `chart_type=flow`，其它图型返回 `unsupported chart_type`，不静默降级。

## 三种 transport

同一套工具定义、同一份执行逻辑，只是接入方式不同：

```bash
# 1) stdio：本地 Agent 以子进程拉起（默认）
python3 mcp/capability_server.py --transport stdio

# 2) http：标准 MCP JSON-RPC over HTTP，供远端/网关调用
python3 mcp/capability_server.py --transport http --host 172.17.0.1 --port 8090
curl -s http://172.17.0.1:8090/health
curl -s -X POST http://172.17.0.1:8090/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# 3) gateway：McpClient 把生产网关 /v1/tools 与 /v1/tools/call 映射成 MCP 语义
python3 source/capability-router/router.py --materials-dir materials \
  --mcp-transport gateway --mcp-url http://192.144.137.185:18083
```

[`client.py`](client.py) 是极简 MCP client，三种 transport 对上层接口一致，且**不硬编码任何工具名**——可用工具一律来自 `tools/list`。

## 生产网关接线

`ToolGateway.java::mcpBridge()` 是一个**通用桥**，不认识任何具体工具语义：

```java
ObjectNode def = REGISTRY.get(tool);
if (def != null && "mcp_bridge".equals(def.path("executor").asText(""))) {
    return mcpBridge(tool, def, argsOf(body));
}
```

端点与目标工具名从 `tools.json` 读（`default_args.mcp_endpoint` / `mcp_tool`），请求转成 JSON-RPC `tools/call`，结果取 `structuredContent` 原样回填。**以后新增 MCP 工具只改两份 JSON，不改 Java switch**——这是与「每加一个工具写死一个 case」的差别。

部署形态（远端 `192.144.137.185`）：

```text
deepreport-mcp 容器  python:3.12-slim  172.17.0.1:8090  仅 docker bridge 可达
w06-checker   容器  w06-checker:0.4.0-mcp  18083:18095  公网可访问、无鉴权
```

恢复脚本 `/home/ubuntu/w06-recover.sh` 会先拉起 MCP 容器再重建 checker；镜像 `0.4.0-mcp` 已固化新 class 与 9 工具注册表。

## 安全须知

- MCP Server **只绑 `172.17.0.1`**（docker bridge 网关），公网不可达；不要改成 `0.0.0.0`。
- `18083` 仍是公网裸 IP + 无鉴权，桥接工具会接收调用方传入的 `materials` 原文；**不要往里放真实客户数据或非公开材料**。
- Server 不落盘材料、不访问公网、不调用模型；但网关侧没有调用方身份校验。

## 已知限制

- Java 六工位与 DeepSearch 引擎 agent 序列**仍未按模板声明自动触发路由**；目前是 Agent 原型（Python）驱动，生产网关只是提供了可被发现、可被调用的工具。
- 路由为配置驱动以求可审计，未实现 LLM 自主选工具（`model-controlled`）。
- `chart_render` 只有 `flow`；`bar` 等图型在 Skill 方法论里规划但未实现。
- 桥接是 fail-closed：MCP Server 不可达时返回 `ok:false` + `mcp_bridge_error`，不会伪造结果。

## 复现

```bash
python3 mcp/capability_server.py --selftest
python3 -c "import sys; sys.path.insert(0,'mcp'); from client import McpClient; \
with McpClient('stdio') as c: print([t['name'] for t in c.list_tools()])"
```

实跑证据见 [`reviews/capability-mcp-live-run-1120.md`](../reviews/capability-mcp-live-run-1120.md)，架构契约见 [`docs/capability-routing-design.md`](../docs/capability-routing-design.md)。
