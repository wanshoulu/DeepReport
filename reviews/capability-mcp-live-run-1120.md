# 能力下沉 MCP 实跑记录（2026-11-20）

> 本文记录「能 MCP 就尽量 MCP」这次整改：**把成表/成图的确定性执行从 Skill 脚本上移为标准 MCP 工具，并接进生产网关**，以及过程中发现并更正的一处此前误判。所有数字来自本次真实运行，口径随条目列出。

## 1. 整改前的问题

上一轮（`reviews/capability-routing-live-run-1120.md`）虽然做出了「声明 → 路由 → 交付 → 对账」闭环，但两个能力的执行者是 `skills/*/scripts/*.py` 的本地子进程：

- `table_compose` / `chart_render` 的能力**没有注册**，Agent 无从通过 `tools/list` 发现；
- `ToolGateway.java:127` 是硬编码 `switch`，7 个 case，每加一个工具都要改 Java；
- `ARCHITECTURE-v3.md:156` 的 `w06-charts 待注册` 仍未兑现。

也就是说：能力存在，但**不在 MCP 层**。这不是"模板没写表格"的现象层问题，是分层归属没落实。

## 2. 改动清单

| 层 | 文件 | 变更 |
|---|---|---|
| MCP 工具契约 | `mcp/tools.json`（新增） | 两个工具的 `name` / `description` / `inputSchema` / `executor` 机读定义，唯一事实源 |
| MCP Server | `mcp/capability_server.py`（新增） | JSON-RPC 2.0，`initialize` / `tools/list` / `tools/call`；stdio + HTTP 双 transport；零第三方依赖；执行逻辑从 Skill 原样上移 |
| MCP client | `mcp/client.py`（新增） | 三种 transport（stdio / http / gateway）；`tools/list` 映射生产网关 `GET /v1/tools`；不硬编码工具名 |
| Agent | `source/capability-router/router.py` | 改为 MCP client：`tools/list` 发现 → 路由表 → `tools/call` 执行 → 回填；新增 `SOURCE_GUARD` 与 `SKILL_CONTRACT` 校验 |
| 路由表 | `source/config/capability-routes.json` | `skill` 改为 `mcp_tool`，新增 `methodology_skill`；`chart.registered_tool` 由 `null` 改为 `chart_render` |
| Skill | `skills/table-composition/SKILL.md`、`skills/chart-composition/SKILL.md` | 去掉 `entry`，改声明 `mcp_tool` + `layer: methodology`；只讲何时用、参数、降级 |
| Skill 脚本 | 两个 `scripts/*.py` | 覆写为「通过 MCP client 调用工具」的示例，**消除与 Server 的重复实现** |
| 生产网关注册表 | `source/config/tools.json` | 新增 `table_compose` / `chart_render`，带 `executor=mcp_bridge`、`mcp_tool`、`default_args.mcp_endpoint`；gateway 段补 `mcp_bridge_note` |
| 生产网关执行器 | `ToolGateway.java` | `call()` 加通用 `mcp_bridge` 分支 + `mcpBridge()` 方法：转发 JSON-RPC、取 `structuredContent` 回填、fail-closed |

设计取舍：**通用桥 vs 每工具一个 Java case**。前者多一次 HTTP 跳转（实测单次桥接调用 <1s，材料在请求体内），换来「新增 MCP 工具零 Java 改动」；代价是 MCP Server 成为新的可用性依赖，故桥接 fail-closed，不可达时明确返回 `mcp_bridge_error` 而非伪造结果。

## 3. 真跑证据

### 3.1 MCP 协议自检（本地）

```
python3 mcp/capability_server.py --selftest    -> tools/list 返回 table_compose / chart_render
stdio 端到端：initialize -> tools/list -> tools/call
  table_compose -> 1 行（迷你材料），chart_render -> 3 节点带 evidence，SVG 2454 字符
```

### 3.2 Agent 闭环（两条路径都真跑）

命令（工作目录 `deepreport_eval/`，材料为 5 份风控原文）：

```bash
# 标准 MCP stdio
python3 source/capability-router/router.py --materials-dir materials \
  --out mcp_stdio_result.json --markdown-out ... --html-out ...

# 生产网关映射
python3 source/capability-router/router.py --materials-dir materials \
  --mcp-transport gateway --mcp-url http://192.144.137.185:18083 \
  --out mcp_gateway_result.json
```

| 路径 | `tools/list` 发现 | 结果 | 交付 |
|---|---|---|---|
| stdio | 2 个 MCP 工具 | `PASSED` | 2 表 29 行 + 1 图 4 节点，全带原文证据 |
| gateway | 9 个工具（7 原生 + 2 桥接） | `PASSED` | 同上，`produced_by=mcp:table_compose` / `mcp:chart_render` |

决策日志关键帧（两条路径一致）：

```
DECLARATION    3 条能力声明
MCP_DISCOVERY  tools/list 返回 [...]
SKILL_CONTRACT 方法论引用 table-composition（mcp_tool=table_compose，声明 entry 执行=无）
TOOL_CALL      网关 table_extract 返回 ok=True table_count=100
SOURCE_GUARD   模板 source_of_truth=materials，拒绝回填快照表（避免混合主体库串数据）
DECISION       改调 MCP 工具 table_compose
MCP_CALL       tools/call table_compose 返回 rows=5 / 24；chart_render 返回 nodes=4
BACKFILL       artifact.tables / artifact.charts
```

**负向对照**：空材料目录 → `FAILED`、6 条 BLOCKING（`MATERIALS_EMPTY` 1 + `MCP_CALL_FAILED` 2 + `CAPABILITY_MISSING` 3）；工具拒绝产出空表壳。

### 3.3 生产网关（远端 192.144.137.185）

| 验证 | 实测返回 |
|---|---|
| `GET /v1/tools` | 9 个工具；`table_compose` / `chart_render` 带 `executor=mcp_bridge`、`mcp_tool`、`mcp_endpoint` |
| `POST /v1/tools/call` `table_compose`（指标） | `ok=true, via=mcp_bridge, rows=5`；首行 `核心一级资本充足率 / 5% / 第二十六条 / 原文摘录 / 02.txt` |
| `POST /v1/tools/call` `table_compose`（条款矩阵） | `ok=true, via=mcp_bridge, rows=24` |
| `POST /v1/tools/call` `chart_render` | `ok=true, via=mcp_bridge, nodes=4, svg_len=2637` |
| 未知工具 | `{"code":"unknown_tool"}`（原分支未破坏） |
| 原工具 `table_extract` 回归 | `ok=true, table_count=100` |
| 重建后复验 | 执行 `/home/ubuntu/w06-recover.sh` 从 `w06-checker:0.4.0-mcp` 重建 → health OK、9 工具、`chart_render` 仍返回 4 节点 SVG |

部署形态：`deepreport-mcp`（`python:3.12-slim`，仅 `172.17.0.1:8090`）+ `w06-checker`（`0.4.0-mcp`，`18083:18095`）。容器内 `javac 21.0.13` 热编译 `ToolGateway.java`，编译前备份 `ToolGateway.class.bak` / `tools.json.bak`，`docker commit` 固化镜像，恢复脚本同步更新。

截图：[`docs/images/capability-mcp-1120.png`](../docs/images/capability-mcp-1120.png)（两张表 + 架构图）、[`docs/images/capability-mcp-chart-1120.png`](../docs/images/capability-mcp-chart-1120.png)（SVG 单独渲染）。

## 4. 更正一处此前误判（重要）

`reviews/capability-routing-live-run-1120.md` 与 `capability_assessment.md` 曾写：「`table_extract` 对 `icbc` 年报快照全量扫描也返回 `table_count:0`，实时抽取并不可靠，年报那些表确实是离线塞进 artifact 的」。

**这个结论是错的。** 本次重建后实测：

```
POST /v1/tools/call {"tool_name":"table_extract","arguments":{"bank":"live"}}
-> ok=true, table_count=100
首表 T-6-1「财务数据」：利息净收入 635,126 / 637,405 / 655,013 …
/snapshot/live/pages 共 221 页
```

即 `table_extract` 对含财报表格的 `live` 快照**确实能实时抽出 100 张表**。此前返回 0 是当时指定页码（`pages:[15,73]`）或无表格快照集导致的，不能推广为「工具不可靠」。年报成品的 tables 有离线增强 artifact 这一路，但**不等于实时抽取能力缺失**。

由此还暴露一个真实风险并已修复：Agent 若把这 100 张年报表无条件回填进风控报告，就是混合主体库串数据。模板已声明 `source_of_truth: materials`，但旧 router 没有消费它。本次新增 `SOURCE_GUARD` 机制——**依据模板声明决定采纳哪个工具结果**，快照表只作路由探测依据，不回填；`source_of_truth=snapshot` 时才采纳。两条路径的日志均可见该帧。

## 5. 仍未接线（诚实边界）

「MCP 工具已注册并可调」≠「线上报告编制会自动产出图表」。剩余生产端接线点：

1. `CheckerHttpServer` 六工位未调用 `capability-router`：模板 `capabilities` 声明仍需 Agent 原型驱动；
2. `emptyArtifactFromSkeleton()`（`CheckerHttpServer.java:1355`）对非年报仍硬编码 `charts = new ArrayList<>()`，需改为接收 router 回填结果；
3. 批准门禁（`:643-653`）仍只有 numeric + citation，未加第三道「声明-交付对账」；
4. `subject_hint` / `source_of_truth` 未贯通 Java 侧报告主体字段；
5. DeepSearch 引擎 agent 序列（`outline/plan/collector/sub_reporter/end`）没有 chart/table agent，材料模式报告不经过工作台六工位；
6. `18083` 无鉴权、MCP 桥接收调用方材料原文；上生产前需补身份校验与材料隔离。
7. 模板 frontmatter 与 `report-profiles/*.json` 的 `capabilities` 双处维护，尚无一致性校验。

`ARCHITECTURE-v3.md:156` 的 `w06-charts 待注册` 已在本轮补状态说明（原文保留作演变留痕）。

## 6. 复现

```bash
# 本地闭环（无需远端）
python3 mcp/capability_server.py --selftest
python3 source/capability-router/router.py --materials-dir materials --out out.json

# 生产网关发现与调用
curl -s http://192.144.137.185:18083/v1/tools | python3 -m json.tool | head -30
curl -s -X POST http://192.144.137.185:18083/v1/tools/call \
  -H 'Content-Type: application/json' \
  -d '{"tool_name":"table_compose","arguments":{"capability":{"id":"x","spec":"对比表"},"materials":[{"file":"a.txt","text":"…"}]}}'
```
