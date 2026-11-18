# 实时任务状态：设计说明（2026-11-18）

## 用户诉求
右侧面板实时展示任务状态（对照 Dify Tracing / Langfuse Timeline）。

## 交付前状态
面板 UI 完整（时间线/执行图/JSON 三视图、节点可展开、统计条），
但 `/v1/pipeline/run` 是同步阻塞接口——"实时"只有 loading 动画，
完成后一次性回放真实轨迹。

## 本次补齐

### 后端（CheckerHttpServer.java）
1. `POST /v1/pipeline/run?async=1`（或 body `{"async":true}`）：立即返回
   `{run_id, status:RUNNING}`，后台线程池执行；不带 async 参数时保持
   同步行为（旧调用方零破坏）。
2. `GET /v1/pipeline/status?run_id=`：返回 `{status, stages[], current,
   checks?, error?}`。stages 由各工位完成时**增量上报**（reportStage）。
3. 慢工位预上报：long_form 的 writer-agent（LLM，分钟级）调用前先上报
   RUNNING 占位节点，面板立即显示"写作·执行中"；完成后同名覆盖（upsert）。
4. `RUN_STATES` ConcurrentHashMap + 单独线程池（daemon），状态内存态。

### 前端（workbench.html）
`startAgentRun` 改为：异步启动拿 run_id → 1s 轮询 status → 按服务端权威
stage 名逐个把骨架节点替换为真实完成态 → live bar 显示"当前工位（n/6 完成）"
→ 终态渲染完整轨迹 + toast。前端只渲染不编造。

### 实测（远端容器端到端）
- brief 模式：秒级，一次轮询 6/6 SUCCEEDED。
- long_form 模式：t+5s 即点亮"写作·RUNNING"占位，LLM 期间持续显示进行中；
  writer-agent 300s 超时（既有行为）后 fallback，终态 6/6 SUCCEEDED + 完整轨迹。

## 泛化性
状态结构/工位名全部来自既有 stageNode 数据与骨架配置，前端骨架节点按服务端
name 权威匹配——无新增领域硬编码；新增工位自动进入面板（前端骨架需加一行，
或后续改为从 status 首帧动态生成骨架）。

## 已知边界
- long_form 的 writer-agent 300s 超时是既有行为（AgentChatClient），
  与本次改动无关。
- 引擎 patch（deepsearch 的 material_processing.py / report.py /
  planner/user.md）已导出宿主 `~/deepsearch-patches/`，容器重建需重放。
- Java 源码改动在容器内 /src 直接编译部署；仓库副本同步更新。
