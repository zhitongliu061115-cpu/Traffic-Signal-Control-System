# 接口协作规范

## 基本原则

1. 前端只调用 Spring Boot 主后端。
2. Spring Boot 内部调用 Python CityFlow 服务。
3. 静态路网使用 REST 获取。
4. 实时仿真帧使用 WebSocket 推送。
5. WebSocket 消息遵循 CFRP 协议。
6. 新增字段只能向后兼容，不能删除或改变已有字段含义。
7. 任何接口、DTO、WebSocket 消息字段、认证头或服务地址变化，都必须同步更新本文档；涉及部署方式时还必须同步更新 `DEPLOYMENT.md` 或对应 runbook。

## Spring Boot REST 响应格式

普通 REST 接口统一返回：

```json
{
  "success": true,
  "message": "ok",
  "data": {}
}
```

失败示例：

```json
{
  "success": false,
  "message": "simulation session not found",
  "data": null
}
```

## 当前后端已实现接口总览（2026-07-12）

本节是后端对外可调用接口的完整索引，来源为当前代码中的 `@RestController`、`@*Mapping` 和 WebSocket 注册配置。除特别说明外，REST 响应统一包裹为：

```json
{
  "success": true,
  "message": "ok",
  "data": {}
}
```

当前后端尚未接入全局鉴权拦截器，除请求体校验外，大多数接口不要求认证头。业务异常返回 HTTP 400 + `success=false`，未处理异常返回 HTTP 500 + `success=false`。

### A. 认证与账号接口

| 方法 | 路径 | 请求参数/请求体 | 返回 `data` | 功能说明 |
|---|---|---|---|---|
| `POST` | `/api/auth/send-captcha` | JSON：`email` | `null` | 向指定邮箱发送验证码。依赖 `spring.mail.*` 与 `auth.mail.*` 配置；验证码默认 5 分钟有效，默认 60 秒内不能重复发送。 |
| `POST` | `/api/auth/login` | JSON：`username`、`email`、`password` | `AuthResult` | 用户名密码登录。当前按 `username` 查询账号并校验密码，`email` 字段主要用于前端表单兼容。 |
| `POST` | `/api/auth/captcha-login` | JSON：`email`、`captcha` | `AuthResult` | 邮箱验证码登录。校验验证码后返回临时 token 和用户信息。 |
| `POST` | `/api/auth/register` | JSON：`username`、`email`、`password`、`inviteCode` | `AuthResult` | 注册账号。邀请码由 `auth.invite-code` 配置控制，密码以 PBKDF2-SHA256 哈希入库。 |

`AuthResult`：

```json
{
  "token": "uuid-token",
  "user": {
    "id": "user-uuid",
    "username": "admin",
    "email": "admin@traffic.local"
  }
}
```

注意：当前 `token` 是临时 UUID 登录态标识，尚未接入 JWT 或统一鉴权拦截器。

### B. 首页大屏与数据分析展示接口

| 方法 | 路径 | 请求参数/请求体 | 返回 `data` | 功能说明 |
|---|---|---|---|---|
| `GET` | `/api/v1/dashboard/bootstrap` | 无 | `DashboardBootstrapResponse` | 一次性返回交通大屏所需的展示数据，包括路口、道路、车辆、应急车辆、应急路线、告警、统计指标、策略对比指标、拥堵趋势和助手预置回复。当前主要服务现有 dashboard 展示数据。 |
| `GET` | `/api/v1/data-analysis/bootstrap` | 无 | `DataAnalysisBootstrapResponse` | 一次性返回数据分析页展示数据，包括指标卡、状态分布、日/小时序列、建筑/监控记录、热力图、构成图、散点图和 toast。当前是数据分析展示模块的 bootstrap 数据源。 |

### C. 数据库状态接口

| 方法 | 路径 | 请求参数/请求体 | 返回 `data` | 功能说明 |
|---|---|---|---|---|
| `GET` | `/api/v1/database/status` | 无 | `DatabaseStatusResponse` | 检查后端数据库连接、数据库产品名、JDBC URL、核心表行数和缺失表。用于判断云端数据库是否接入、表结构是否完整、核心表是否已有数据。 |

`DatabaseStatusResponse` 字段：

- `connected`：数据库是否连通。
- `databaseProductName`：数据库产品名，例如 PostgreSQL/H2。
- `url`：当前 JDBC URL。
- `tableCounts`：核心表行数。
- `missingTables`：缺失的核心表名。

### D. 路口旧展示表接口

这组接口操作旧展示表 `intersections`，不是标准路网表 `intersection`。适合现有页面展示和状态维护，不等价于 CityFlow runtime 路口详情。

| 方法 | 路径 | 请求参数/请求体 | 返回 `data` | 功能说明 |
|---|---|---|---|---|
| `GET` | `/api/v1/intersections` | 无 | `List<IntersectionResponse>` | 查询旧展示表中的全部路口。 |
| `GET` | `/api/v1/intersections/{code}` | Path：`code` | `IntersectionResponse` | 按业务路口 code 查询单个旧展示路口。 |
| `PATCH` | `/api/v1/intersections/{code}/status` | Path：`code`；JSON：`status` | `IntersectionResponse` | 更新旧展示路口状态。`status` 只允许 `online`、`maintenance`、`offline`。 |

`IntersectionResponse` 主要字段：`id`、`code`、`name`、`district`、`longitude`、`latitude`、`status`、`metadata`、`createdAt`、`updatedAt`。

### E. 场景路网接口

| 方法 | 路径 | 请求参数/请求体 | 返回 `data` | 功能说明 |
|---|---|---|---|---|
| `GET` | `/api/v1/scenes/{sceneId}/roadnet` | Path：`sceneId` | `RoadnetResponse` | 查询指定场景的静态路网结构。返回 `sceneId`、`intersections`、`roads`、`roadLinks`、`phases`。主要用于前端绘制路网、后端缓存 roadnet、Agent 解释路口/道路关系。 |

`RoadnetResponse` 结构：

- `intersections[]`：`id`、`x`、`y`、`virtual`。
- `roads[]`：`id`、`from`、`to`、`points[]`、`laneCount`。
- `roadLinks[]`：`intersectionId`、`index`、`fromRoadId`、`toRoadId`、`type`。
- `phases[]`：`intersectionId`、`phaseIndex`、`phaseCode`、`roadLinkIndexes[]`。

### F. 仿真会话与应急调度接口

| 方法 | 路径 | 请求参数/请求体 | 返回 `data` | 功能说明 |
|---|---|---|---|---|
| `POST` | `/api/v1/simulations` | JSON：`sceneId`、`speed?`、`warmupSeconds?`、`controllerType?` | `CreateSimulationResponse` | 创建仿真会话。后端会调用 Python CityFlow 创建 session，缓存 roadnet，写入/更新 `simulation_session`，并注册内存运行会话。 |
| `POST` | `/api/v1/simulations/{sid}/start` | Path：`sid` | `null` | 启动仿真。后端转发 start 到 Python CityFlow，更新内存状态和数据库 session 状态。 |
| `POST` | `/api/v1/simulations/{sid}/pause` | Path：`sid` | `null` | 暂停仿真。后端转发 pause 到 Python CityFlow，更新内存状态和数据库 session 状态。 |
| `POST` | `/api/v1/simulations/{sid}/stop` | Path：`sid` | `null` | 停止仿真。后端转发 stop 到 Python CityFlow，更新 session 状态并释放运行会话。最近几帧仍可短期保留在 `LiveSimulationStateService` 中供 Agent 查询。 |
| `POST` | `/api/v1/simulations/{sid}/dispatch` | Path：`sid`；JSON：`startCoord`、`endCoord`、`evId`、`evType?`、`priority?`、`maxSpeed?` | `EVDispatchResponse` | 发起应急车辆调度。要求仿真会话处于 running 状态。当前会调用应急服务生成/下发 EV 任务，返回 CityFlow 车辆 ID、路线和预计时间。 |

创建仿真请求：

```json
{
  "sceneId": "jinan_3x4",
  "speed": 1.0,
  "warmupSeconds": 0,
  "controllerType": "fixed-time"
}
```

创建仿真响应：

```json
{
  "sid": "run_xxx",
  "sceneId": "jinan_3x4",
  "status": "created",
  "controllerType": "fixed-time"
}
```

应急调度请求：

```json
{
  "startCoord": {"x": 0.0, "y": 0.0},
  "endCoord": {"x": 100.0, "y": 100.0},
  "evId": "ev-001",
  "evType": "fire_truck",
  "priority": 1,
  "maxSpeed": 20.0
}
```

应急调度响应：`cfVehicleId`、`sid`、`evId`、`evType`、`priority`、`route[]`、`routeRoads[]`、`estimatedTravelTime`。

### G. Runtime 历史/数据库复盘查询接口

`/api/v1/runtime/**` 是数据库视角的历史/复盘查询层。注意：Agent 实时状态工具已经改为读取 `LiveSimulationStateService` 内存缓存；如果要回答“当前正在发生什么”，优先调用 Agent tools 中的实时接口，而不是 runtime 历史接口。

| 方法 | 路径 | 请求参数/请求体 | 返回 `data` | 功能说明 |
|---|---|---|---|---|
| `GET` | `/api/v1/runtime/simulations/current` | Query：`sid?` | `CurrentSimulationState` | 数据库视角查询指定或最近 session 的状态、最新已落库 frame、信号摘要。当前默认不再全量落库实时快照，因此该接口更适合历史/采样数据检查，不适合作为 Agent 实时状态来源。 |
| `GET` | `/api/v1/runtime/intersections/{intersectionId}` | Path：`intersectionId`；Query：`sid?`、`sceneCode?` | `IntersectionDetail` | 数据库视角查询路口基础信息、相位、roadLink 和最近已落库 movement/相位状态。 |
| `GET` | `/api/v1/runtime/roads/{roadId}` | Path：`roadId`；Query：`sid?`、`sceneCode?` | `RoadDetail` | 数据库视角查询道路基础信息、lane 列表和最近已落库道路状态。 |
| `GET` | `/api/v1/runtime/control-decisions` | Query：`sid?`、`intersectionId?`、`limit=20` | `List<ControlDecisionSummary>` | 查询最近控制决策。支持按 session 和路口过滤，最大 `limit` 为 100。 |
| `GET` | `/api/v1/runtime/control-decisions/{decisionId}/trace` | Path：`decisionId` | `DecisionTraceResponse` | 查询单条控制决策及其 trace 阶段记录。`decisionId` 必须是 UUID。 |
| `GET` | `/api/v1/runtime/system-health` | Query：`limit=20` | `SystemHealthResponse` | 查询数据库视角健康摘要：关键表行数、session 状态分布、最近 `service_health_snapshot`。当前不主动探测 Python CityFlow 或 Traffic-R。 |
| `GET` | `/api/v1/runtime/model-inferences` | Query：`sid?`、`intersectionId?`、`limit=20` | `List<ModelInferenceLogSummary>` | 查询 Traffic-R 推理日志和逐路口推理结果。 |
| `GET` | `/api/v1/runtime/fallback-events` | Query：`sid?`、`intersectionId?`、`limit=20` | `List<FallbackEventSummary>` | 查询策略 fallback 事件，例如 Traffic-R 降级到 MaxPressure。 |
| `GET` | `/api/v1/runtime/safety-events` | Query：`sid?`、`intersectionId?`、`decisionId?`、`limit=20` | `List<SafetyEventSummary>` | 查询安全约束事件。`SafetyLayerService` 拦截非法相位、相位映射错误、持续时间越界或最小保持时间不足时会写入 `safety_constraint_event`。 |
| `GET` | `/api/v1/runtime/alerts` | Query：`sid?`、`level?`、`status?`、`limit=20` | `List<AlertEventSummary>` | 查询系统告警事件。 |
| `GET` | `/api/v1/runtime/emergency-events` | Query：`sid?`、`status?`、`limit=20` | `List<EmergencyEventSummary>` | 查询应急事件主记录。 |

常用返回 DTO：

- `CurrentSimulationState`：`session`、`latestFrame`、`persistedFrameCount`、`signals[]`。
- `IntersectionDetail`：路口基础字段、`latestState`、`movements[]`、`phases[]`、`roadLinks[]`。
- `RoadDetail`：道路基础字段、`latestState`、`lanes[]`。
- `ControlDecisionSummary`：策略来源、请求相位、最终相位、持续时间、状态、原因、置信度、metadata、错误信息。
- `ModelInferenceLogSummary`：请求、prompt、原始输出、解析相位、合法性、耗时、状态、逐路口结果。

### H. Agent 聊天、会话、审计接口

| 方法 | 路径 | 请求参数/请求体 | 返回 `data` | 功能说明 |
|---|---|---|---|---|
| `POST` | `/api/v1/agent/chat` | JSON：`message`、`sessionId?`、`sid?`、`conversationId?`、`context?` | `AgentChatResponse` | Agent 统一聊天入口。后端创建/读取会话，写入用户消息，调用 LLM 生成工具规划，执行白名单工具，写入工具调用审计，再生成助手回复。 |
| `POST` | `/api/v1/agent/conversations` | JSON：`userId?`、`sid?`、`externalSessionId?`、`title` | `ConversationResponse` | 创建 Agent 会话。 |
| `GET` | `/api/v1/agent/conversations` | Query：`sid?`、`externalSessionId?`、`userId?`、`limit=20` | `List<ConversationResponse>` | 查询 Agent 会话列表，支持按仿真 session、外部 session、用户过滤。 |
| `GET` | `/api/v1/agent/conversations/{conversationId}` | Path：`conversationId` | `ConversationResponse` | 查询单个 Agent 会话。 |
| `POST` | `/api/v1/agent/conversations/{conversationId}/messages` | Path：`conversationId`；JSON：`role`、`content` | `MessageResponse` | 向会话写入一条消息。`role` 可为 `user`、`assistant`、`system` 等调用方约定值。 |
| `GET` | `/api/v1/agent/conversations/{conversationId}/messages` | Path：`conversationId`；Query：`limit=20` | `List<MessageResponse>` | 查询指定会话的最近消息。 |
| `POST` | `/api/v1/agent/messages/{messageId}/tool-calls` | Path：`messageId`；JSON：`toolName`、`arguments?`、`result?`、`status?`、`latencyMs?`、`errorMessage?` | `ToolCallResponse` | 手动记录某条 Agent 消息关联的工具调用。通常 `/api/v1/agent/chat` 会自动写入，外部 MCP/调试工具也可手动记录。 |
| `GET` | `/api/v1/agent/messages/{messageId}/tool-calls` | Path：`messageId`；Query：`limit=20` | `List<ToolCallResponse>` | 查询某条消息下的工具调用记录。 |
| `GET` | `/api/v1/agent/tool-calls` | Query：`toolName?`、`status?`、`limit=20` | `List<ToolCallResponse>` | 按工具名和状态查询最近工具调用记录。 |

`AgentChatRequest`：

```json
{
  "message": "请分析当前路口拥堵原因",
  "sessionId": "frontend-session-001",
  "sid": "run_xxx",
  "conversationId": "conversation-uuid",
  "context": {}
}
```

`AgentChatResponse`：`reply`、`sessionId`、`source`、`fallback`、`conversationId`、`messageId`、`toolCalls[]`、`evidence[]`、`planTrace`。其中 `reply` 是前端面向用户展示的最终文本，后端会清洗模型误输出的 JSON/过程字段；`toolCalls[]`、`evidence[]`、`planTrace` 仅供调试、审计和开发面板使用，普通用户界面不应直接展示。

### I. Agent 工具 HTTP 兼容接口

这些接口用于 MCP/外部工具调用或前端调试，路径与 Agent 工具名保持一致。旧版基础查询接口支持可选 Query：`messageId`；传入后会把工具名、参数、结果、状态、耗时和错误写入 `agent_tool_call`。新增的增强工具 HTTP 入口主要用于调试验收，正式 Agent 对话中的工具审计由 `/api/v1/agent/chat` 编排层统一记录。

实时状态类工具读取 `LiveSimulationStateService` 内存最近帧，不查询数据库快照：

| 方法 | 路径 | 请求参数 | 返回 `data` | 功能说明 |
|---|---|---|---|---|
| `GET` | `/api/v1/agent/tools/get_current_simulation_state` | Query：`sid?`、`messageId?` | `CurrentSimulationState` | 查询当前或指定仿真会话的内存实时状态，包括最新帧、车辆数、排队数、平均速度、等待时间和信号摘要。 |
| `GET` | `/api/v1/agent/tools/get_intersection_detail/{intersectionId}` | Path：`intersectionId`；Query：`sid?`、`sceneCode?`、`messageId?` | `IntersectionDetail` | 查询内存实时路口详情，包括当前相位、movement 状态、相位列表和 roadLink。当前以 CityFlow 路口 ID 为主。 |
| `GET` | `/api/v1/agent/tools/get_road_detail/{roadId}` | Path：`roadId`；Query：`sid?`、`sceneCode?`、`messageId?` | `RoadDetail` | 查询内存实时道路详情，包括车辆数、排队数、平均速度、拥堵等级和 lane 列表。当前以 CityFlow 道路 ID 为主。 |

历史复盘/日志类工具读取数据库：

| 方法 | 路径 | 请求参数 | 返回 `data` | 功能说明 |
|---|---|---|---|---|
| `GET` | `/api/v1/agent/tools/get_latest_control_decisions` | Query：`sid?`、`intersectionId?`、`limit=20`、`messageId?` | `List<ControlDecisionSummary>` | 查询最近控制决策，用于 Agent 解释策略来源、请求相位、最终相位和执行原因。 |
| `GET` | `/api/v1/agent/tools/get_decision_trace/{decisionId}` | Path：`decisionId`；Query：`messageId?` | `DecisionTraceResponse` | 查询指定控制决策链路。 |
| `GET` | `/api/v1/agent/tools/get_system_health` | Query：`limit=20`、`messageId?` | `SystemHealthResponse` | 查询数据库视角系统健康摘要和服务健康快照。 |
| `GET` | `/api/v1/agent/tools/get_model_inference_log` | Query：`sid?`、`intersectionId?`、`limit=20`、`messageId?` | `List<ModelInferenceLogSummary>` | 查询 Traffic-R 模型推理日志、原始输出和解析结果。 |
| `GET` | `/api/v1/agent/tools/get_fallback_events` | Query：`sid?`、`intersectionId?`、`limit=20`、`messageId?` | `List<FallbackEventSummary>` | 查询策略 fallback 事件。 |
| `GET` | `/api/v1/agent/tools/get_safety_events` | Query：`sid?`、`intersectionId?`、`decisionId?`、`limit=20`、`messageId?` | `List<SafetyEventSummary>` | 查询安全约束事件，可用于 Agent 解释某个策略建议为何被安全层 fallback 或拒绝。 |
| `GET` | `/api/v1/agent/tools/get_alert_events` | Query：`sid?`、`level?`、`status?`、`limit=20`、`messageId?` | `List<AlertEventSummary>` | 查询系统告警事件。 |
| `GET` | `/api/v1/agent/tools/get_emergency_events` | Query：`sid?`、`status?`、`limit=20`、`messageId?` | `List<EmergencyEventSummary>` | 查询应急事件主记录。 |

增强/混合工具调试入口返回统一 `AgentToolResult`，字段为 `success`、`toolName`、`data`、`evidence`、`warnings`、`timestamp`：

| 方法 | 路径 | 请求参数 | 返回 `data` | 功能说明 |
|---|---|---|---|---|
| `GET` | `/api/v1/agent/tools/get_system_health/enhanced` | Query：`limit=20` | `AgentToolResult` | 增强版系统健康探测，聚合 Spring Boot 自检、数据库、CityFlow `/health`、Traffic-R `/health`、本地隧道、WebSocket 连接数和实时状态缓存。 |
| `GET` | `/api/v1/agent/tools/get_decision_trace/{decisionId}/enhanced` | Path：`decisionId` | `AgentToolResult` | 增强版决策追踪，聚合控制决策、Traffic-R 推理结果、安全层校验、fallback 事件、trace 时间线和 CityFlow 下发相关 metadata。 |
| `GET` | `/api/v1/agent/tools/search_knowledge_base` | Query：`query`、`topK=5`、`scope?` | `AgentToolResult` | 混合知识库检索。本地项目 `.md/.txt` 文档始终可用；百炼知识库通过官方 `bailian20231229` OpenAPI SDK 调用单个 `index-id` 的 `Retrieve` 接口，返回语义切片并作为 Agent 工具证据转给 LLM。未配置或调用失败时返回 warning 并回退本地检索。 |
| `GET` | `/api/v1/agent/tools/get_emergency_vehicle_status` | Query：`sid?`、`vehicleId?`、`limit=20` | `AgentToolResult` | 查询应急车辆实时状态，优先读取 `LiveSimulationStateService` 最近帧中的应急车辆、路线进度、ETA 和绿波状态，同时附带数据库应急事件摘要。 |
| `GET` | `/api/v1/agent/tools/draft_emergency_dispatch` | Query：`sid?`、`startIntersection`、`endIntersection`、`evId?`、`evType?`、`priority=1` | `AgentToolResult` | 生成应急调度和绿波草案，只返回路线、经过路口、建议动作和人工确认项，不调用应急执行接口，不下发 CityFlow。 |
| `GET` | `/api/v1/agent/tools/audit_configuration_consistency` | Query：`sid?`、`sceneCode?` | `AgentToolResult` | 检查 CityFlow roadnet、实时信号、Traffic-R phaseCode、数据库 signal phase 和 live frame 的配置一致性，用于排查 Traffic-R 被安全层阻断、相位映射异常等问题。 |

### J. WebSocket 实时推送接口

| 协议 | 路径 | 参数 | 推送内容 | 功能说明 |
|---|---|---|---|---|
| `WS` / `WSS` | `/ws/v1/simulations/{sid}` | Path：`sid` | `WsMessage<SimFrameData>`、`WsMessage<List<ControlDecision>>` | 前端订阅指定仿真会话的实时消息。后端会推送 `sim.frame` 实时仿真帧和 `control.decision` 控制决策事件。真实信号灯状态必须以后续 `sim.frame.data.signals` 为准，`control.decision` 可能包含安全层审计决策；只有 `metadata.safetyAllowed=true` 且进入 CityFlow 下发流程的决策才代表提交动作，被标记 `metadata.safetyRejected=true` 的决策不会下发。 |

连接示例：

```text
ws://localhost:8080/ws/v1/simulations/{sid}
```

统一消息信封：

```json
{
  "v": "1.0",
  "type": "sim.frame",
  "sid": "run_xxx",
  "seq": 1,
  "simTime": 12.0,
  "sentAt": "2026-07-12T10:00:00+08:00",
  "data": {}
}
```

当前已推送的主要 `type`：

- `sim.frame`：仿真帧，`data` 为 `SimFrameData`，包括车辆、道路、laneStates、路口、信号、指标、应急事件和应急状态。
- `control.decision`：控制决策事件，`data` 为 `List<ControlDecision>`。

### K. Agent 内部 LangChain4j 工具接口（非 HTTP 路由）

下面这些是后端内部 Agent 编排层可调用的 `@Tool` 工具，不是直接暴露给前端的 REST 路由。外部调用方如果需要通过 HTTP 调用工具，只能调用上一节 `/api/v1/agent/tools/**` 已列出的 HTTP 兼容接口；诊断类、知识库检索类工具当前主要由 `/api/v1/agent/chat` 在编排过程中调用。

| 工具名 | 所属类 | 主要入参 | 数据来源 | 功能说明 |
|---|---|---|---|---|
| `get_current_simulation_state` | `TrafficRuntimeAgentTools` | `sid?` | 内存实时缓存 | 查询当前或指定仿真会话的实时状态，不读数据库快照。 |
| `get_intersection_detail` | `TrafficRuntimeAgentTools` | `intersectionId`、`sid?`、`sceneCode?` | 内存实时缓存 + roadnet | 查询路口实时相位、movement/lane 状态、相位候选和 roadLink。 |
| `get_road_detail` | `TrafficRuntimeAgentTools` | `roadId`、`sid?`、`sceneCode?` | 内存实时缓存 + roadnet | 查询道路实时车辆、排队、速度、拥堵等级和车道信息。 |
| `get_latest_control_decisions` | `TrafficDecisionAgentTools` | `sid?`、`intersectionId?`、`limit?` | 数据库 | 查询最近控制决策，用于解释策略来源、最终相位和执行原因。 |
| `get_decision_trace` | `TrafficDecisionAgentTools` | `decisionId` | 数据库 | 增强版决策链路聚合，查询控制决策、trace 阶段、Traffic-R 推理、安全层校验、fallback 和 CityFlow 下发相关 metadata。 |
| `get_model_inference_log` | `TrafficDecisionAgentTools` | `sid?`、`intersectionId?`、`limit?` | 数据库 | 查询 Traffic-R 推理请求、模型输出、解析结果和耗时。 |
| `get_system_health` | `TrafficHealthAgentTools` | `limit?` | 主动探测 + 数据库 | 探测 Spring Boot、CityFlow、Traffic-R、WebSocket、数据库、本地隧道和实时状态缓存。 |
| `audit_configuration_consistency` | `TrafficHealthAgentTools` | `sid?`、`sceneCode?` | 内存 roadnet + 数据库 + 配置 | 检查 roadnet、实时信号、Traffic-R phaseCode、CityFlow phaseIndex 和数据库相位配置一致性。 |
| `search_knowledge_base` | `TrafficKnowledgeAgentTools` | `query`、`topK?`、`scope?` | 本地项目文档 + 百炼 Retrieve | 查询项目文档、接口规范、部署资料、Agent 设计和算法说明；百炼侧只使用当前配置的单个知识库 `index-id`，`Retrieve` 返回的 `Data.Nodes[].Text` 会写入 `hits[].snippet`，作为 LLM 生成最终回答的证据切片。 |
| `diagnose_congestion` | `TrafficDiagnosisAgentTools` | `targetType`、`targetId?`、`sid?`、`sceneCode?` | 内存实时缓存为主 | 基于实时排队、等待、低速 movement/road 证据诊断拥堵原因，只输出建议不执行控制。 |
| `detect_signal_anomaly` | `TrafficDiagnosisAgentTools` | `sid?`、`intersectionId?`、`limit?` | 数据库 + 内存实时缓存 | 检测相位长时间不变、安全事件、异常决策和相位映射疑似问题。 |
| `detect_spillback_risk` | `TrafficDiagnosisAgentTools` | `sid?`、`roadId?`、`intersectionId?`、`sceneCode?` | 内存实时缓存 | 检测道路或路口下游溢出风险，输出证据、影响范围和人工确认项。 |
| `get_region_metrics` | `TrafficDiagnosisAgentTools` | `sid?`、`regionId?`、`intersectionIds?`、`limit?` | 数据库低频摘要/采样 | 聚合区域或路口集合的等待、排队、速度和拥堵指标。 |
| `compare_strategy_metrics` | `TrafficDiagnosisAgentTools` | `sids?`、`sceneCode?`、`limit?` | 数据库实验/摘要数据 | 对比 Fixed-Time、MaxPressure、Traffic-R、Hybrid 等策略指标。 |
| `get_fallback_events` / `get_fallback_log` | `TrafficDiagnosisAgentTools` | `sid?`、`intersectionId?`、`limit?` | 数据库 | 查询策略降级/fallback 事件；`get_fallback_log` 是语义化别名。 |
| `get_safety_events` / `get_safety_constraint_log` | `TrafficDiagnosisAgentTools` | `sid?`、`intersectionId?`、`decisionId?`、`limit?` | 数据库 | 查询安全约束事件；`get_safety_constraint_log` 是语义化日志工具。 |
| `get_alert_events` | `TrafficDiagnosisAgentTools` | `sid?`、`level?`、`status?`、`limit?` | 数据库 | 查询告警事件，用于异常诊断和运行风险分析。 |
| `get_emergency_events` | `EmergencyAgentTools` | `sid?`、`status?`、`limit?` | 数据库 | 查询应急事件主记录，只读，不生成或执行绿波控制。 |
| `get_emergency_vehicle_status` | `EmergencyAgentTools` | `sid?`、`vehicleId?`、`limit?` | 内存实时缓存 + 数据库 | 查询应急车辆当前位置、路线进度、ETA、绿波状态和关联应急事件。 |
| `draft_emergency_dispatch` | `EmergencyAgentTools` | `sid?`、`startIntersection`、`endIntersection`、`evId?`、`evType?`、`priority?` | 内存 roadnet | 根据起终点生成应急路线和绿波调度草案，只生成建议，不执行控制动作。 |

所有 Agent 内部工具统一返回 `AgentToolResult`：`success`、`toolName`、`data`、`evidence`、`warnings`、`timestamp`。工具失败时应返回 `success=false` 的结构化结果，不能让 Agent 整体崩溃；所有工具均为只读工具。

### L. Spring Boot 内部依赖的外部服务接口索引

这组接口不是前端直接调用的后端 REST API，而是 Spring Boot 后端内部依赖的服务契约。排查联调问题时需要同时核对这些协议。

| 服务 | 方法/路径 | 调用方 | 功能说明 |
|---|---|---|---|
| Python CityFlow | `GET /cityflow/health` | Spring Boot / 运维检查 | 检查 CityFlow 服务模式、运行状态和会话数量。 |
| Python CityFlow | `GET /cityflow/scenes/{sceneId}/roadnet` | `SceneService` / `SimulationService` | 获取指定场景静态路网，供前端绘制、实时缓存和 Agent 解释 roadnet。 |
| Python CityFlow | `POST /cityflow/simulations` | `SimulationService` | 创建 CityFlow 仿真会话。 |
| Python CityFlow | `GET /cityflow/simulations/{sid}/frame` | `SimulationFrameScheduler` | 拉取 Python 端最新仿真帧；真实模式下不由该接口阻塞推进仿真。 |
| Python CityFlow | `POST /cityflow/simulations/{sid}/start` | `SimulationService` | 启动 Python 后台仿真 worker。 |
| Python CityFlow | `POST /cityflow/simulations/{sid}/pause` | `SimulationService` | 暂停 Python 后台仿真 worker。 |
| Python CityFlow | `POST /cityflow/simulations/{sid}/stop` | `SimulationService` | 停止并释放 Python 侧 CityFlow 会话。 |
| Python CityFlow | `POST /cityflow/simulations/{sid}/actions` | `SimulationService` | 下发最终控制决策；策略不得绕过 Spring Boot 直接调用。 |
| Python CityFlow | `POST /cityflow/simulations/{sid}/dispatch` | `EmergencyService` | 下发应急车辆调度请求。 |
| Traffic-R | `POST {traffic-r.base-url}{traffic-r.predict-path}` | `RlController` | 单路口 Traffic-R 推理。 |
| Traffic-R | `POST {traffic-r.base-url}{traffic-r.batch-predict-path}` | `TrafficRBatchController` | 多路口批量 Traffic-R 推理。 |

## 认证接口

当前登录、注册和邮箱验证码接口使用 `/api/auth/**` 前缀。前端通过 `sys-frontend/src/api/auth.ts` 统一调用，后端由 `com.traffic.auth.AuthController` 承载。

### 发送邮箱验证码

```http
POST /api/auth/send-captcha
Content-Type: application/json
```

请求：

```json
{
  "email": "operator@example.com"
}
```

响应：

```json
{
  "success": true,
  "message": "ok",
  "data": null
}
```

验证码发送依赖 `spring.mail.*` 和 `auth.mail.*` 配置。验证码默认 5 分钟有效，默认 60 秒内不能重复发送。

### 用户名密码登录

```http
POST /api/auth/login
Content-Type: application/json
```

请求：

```json
{
  "username": "admin",
  "email": "admin@traffic.local",
  "password": "123456"
}
```

当前后端按 `username` 查询账号并校验密码，`email` 字段保留给前端表单和后续兼容。

### 邮箱验证码登录

```http
POST /api/auth/captcha-login
Content-Type: application/json
```

请求：

```json
{
  "email": "operator@example.com",
  "captcha": "123456"
}
```

### 注册

```http
POST /api/auth/register
Content-Type: application/json
```

请求：

```json
{
  "username": "operator",
  "email": "operator@example.com",
  "password": "secret",
  "inviteCode": "123456"
}
```

登录、验证码登录和注册成功时返回：

```json
{
  "success": true,
  "message": "ok",
  "data": {
    "token": "uuid-token",
    "user": {
      "id": "user-uuid",
      "username": "admin",
      "email": "admin@traffic.local"
    }
  }
}
```

注意：

- 当前 `token` 是后端生成的临时 UUID 登录态标识，尚未接入 JWT、服务端会话校验或接口鉴权拦截器。
- 默认初始账号由 `auth.initial-account.*` 配置创建，默认用户名 `admin`，默认密码 `123456`。生产或公网演示前必须修改默认密码和邀请码。
- 密码以 PBKDF2-SHA256 哈希写入 `auth_user.password_hash`，不得在日志、文档或接口返回中暴露明文密码。

## 前端调用接口

### 获取静态路网

```http
GET /api/v1/scenes/{sceneId}/roadnet
```

示例：

```http
GET /api/v1/scenes/jinan_3x4/roadnet
```

用途：

- 前端初始化地图。
- 绘制路口、道路、车道数量和信号相位。

### 创建仿真会话

```http
POST /api/v1/simulations
Content-Type: application/json
```

请求：

```json
{
  "sceneId": "jinan_3x4",
  "speed": 1.0,
  "warmupSeconds": 0.0,
  "controllerType": "fixed-time"
}
```

字段说明：

| 字段 | 必需 | 含义 |
|---|---:|---|
| `sceneId` | 是 | 场景 ID，例如 `jinan_3x4` 或 `jinan_3x4_stress` |
| `speed` | 否 | Python CityFlow 后台推进倍速，默认 `1.0`，当前由 Python 端 `SIM_MAX_SPEED` 限制上限 |
| `warmupSeconds` | 否 | 创建会话后的预热仿真时间，默认 `0.0` |
| `controllerType` | 否 | 控制器类型，默认 `fixed-time` |

`controllerType` 当前允许：

| 值 | 含义 |
|---|---|
| `fixed-time` | 固定配时 baseline |
| `max-pressure` | Max Pressure baseline |
| `traffic-r` | 云端 Traffic-R / RL 控制器入口 |
| `rl` | 兼容别名，后端会归一化为 `traffic-r` |

每次创建都会生成独立 `sid`，不会停止或覆盖已有会话。Python 服务允许多个会话并行运行，新建会话不再因为旧会话数量达到 `SIM_MAX_ACTIVE_SESSIONS` 而返回 429；`SIM_MAX_ACTIVE_SESSIONS=0` 表示不设置创建数量上限。旧会话依赖 stop、自然结束、`SIM_SESSION_IDLE_TTL_SECONDS`、`SIM_SESSION_ABANDONED_TTL_SECONDS` 和 `SIM_SESSION_MAX_LIFETIME_SECONDS` 自动释放。其中 `SIM_SESSION_ABANDONED_TTL_SECONDS` 用于清理已经 running 但一段时间没有任何 `/frame`、`/actions`、`/pause`、`/stop` 等后端请求的遗弃会话。

响应：

```json
{
  "success": true,
  "message": "ok",
  "data": {
    "sid": "run_001",
    "sceneId": "jinan_3x4",
    "status": "created",
    "controllerType": "fixed-time"
  }
}
```

### 控制仿真会话

```http
POST /api/v1/simulations/{sid}/start
POST /api/v1/simulations/{sid}/pause
POST /api/v1/simulations/{sid}/stop
```

这三个接口会先转发到 Python CityFlow：

- `start`：Python 后台 worker 开始连续推进 CityFlow，并持续刷新缓存快照。
- `pause`：Python 暂停后台推进，Spring Boot 暂停推送新帧。
- `stop`：Python 停止 worker 并释放该会话持有的 CityFlow Engine、应急任务状态和临时配置，Spring Boot 同步移除运行态会话。

当 flow 中最后一批车辆已经发出且路网上活跃车辆数归零时，Python 会自动结束并释放会话。若严重拥堵导致车辆始终无法清空，超过 `SIM_SESSION_DRAIN_TIMEOUT_SECONDS`（默认 600 秒仿真时间）后也会强制结束。最后一帧的 `data.status` 为 `finished`，Spring Boot 收到后停止继续轮询该 `sid`。

### 3.4 数据库连接状态

```http
GET /api/v1/database/status
```

用途：

- 验证 Spring Boot 是否能连接 PostgreSQL。
- 返回核心业务表是否存在以及行数统计。

### 3.5 运行时查询与 Agent 工具接口

这些接口只读取 Spring Boot 后端掌握的真实数据，不直接推进 CityFlow，也不下发控制动作。实时状态与历史复盘的数据源不同：

- `/api/v1/runtime/**`：数据库视角的运行时/历史复盘查询，主要读取已落库的 session、决策、推理、fallback、告警等记录。
- `/api/v1/agent/tools/get_current_simulation_state`、`get_intersection_detail`、`get_road_detail`：Agent 实时状态工具，读取 `LiveSimulationStateService` 内存最近帧，不再查询数据库快照。
- `/api/v1/agent/tools/get_latest_control_decisions`、`get_decision_trace`、`get_model_inference_log`、`get_fallback_events` 等复盘类工具：继续通过 `RuntimeQueryService` 查询数据库。

`/api/v1/agent/tools/**` 支持可选查询参数 `messageId`。当 MCP 网关或前端已经创建 `agent_message` 后，把该消息 ID 传给工具接口，后端会自动把工具名、参数、结果摘要、状态、耗时和错误写入 `agent_tool_call`。不传 `messageId` 时只返回查询结果，不写审计。

#### 当前仿真状态 / `get_current_simulation_state`

```http
GET /api/v1/runtime/simulations/current?sid={sid}
GET /api/v1/agent/tools/get_current_simulation_state?sid={sid}
```

参数：

| 参数 | 必需 | 含义 |
|---|---:|---|
| `sid` | 否 | 指定仿真会话；不传时返回最近创建或启动的会话 |

`/api/v1/runtime/simulations/current` 返回数据库视角的历史记录；`/api/v1/agent/tools/get_current_simulation_state` 返回内存实时缓存中的会话摘要、最新帧、缓存帧数和最新帧信号状态。Agent 实时回答必须使用后者；没有实时缓存时必须说明无法获取，不能回退到旧快照编造当前状态。

#### 路口详情 / `get_intersection_detail`

```http
GET /api/v1/runtime/intersections/{intersectionId}?sid={sid}&sceneCode={sceneCode}
GET /api/v1/agent/tools/get_intersection_detail/{intersectionId}?sid={sid}&sceneCode={sceneCode}
```

`/api/v1/runtime/intersections/{intersectionId}` 查询数据库历史视角；`/api/v1/agent/tools/get_intersection_detail/{intersectionId}` 读取内存实时帧和创建仿真时缓存的 roadnet。实时工具当前以 CityFlow 路口 ID 为主，返回路口基础信息、最新相位/排队状态、movement-level 状态、相位列表和 roadLink 列表。

#### 道路详情 / `get_road_detail`

```http
GET /api/v1/runtime/roads/{roadId}?sid={sid}&sceneCode={sceneCode}
GET /api/v1/agent/tools/get_road_detail/{roadId}?sid={sid}&sceneCode={sceneCode}
```

`/api/v1/runtime/roads/{roadId}` 查询数据库历史视角；`/api/v1/agent/tools/get_road_detail/{roadId}` 读取内存实时帧和 roadnet。实时工具当前以 CityFlow 道路 ID 为主，返回道路基础信息、最新道路状态和 lane 列表。

#### 最新控制决策 / `get_latest_control_decisions`

```http
GET /api/v1/runtime/control-decisions?sid={sid}&intersectionId={intersectionId}&limit=20
GET /api/v1/agent/tools/get_latest_control_decisions?sid={sid}&intersectionId={intersectionId}&limit=20
```

参数：

| 参数 | 必需 | 含义 |
|---|---:|---|
| `sid` | 否 | 按仿真会话过滤 |
| `intersectionId` | 否 | 可传标准 UUID、CityFlow 路口 ID 或地图路口 ID |
| `limit` | 否 | 返回条数，默认 20，后端最大限制 100 |

返回 `control_decision` 及关联相位 code、置信度、metadata 和错误信息。

#### 决策追踪 / `get_decision_trace`

```http
GET /api/v1/runtime/control-decisions/{decisionId}/trace
GET /api/v1/agent/tools/get_decision_trace/{decisionId}
GET /api/v1/agent/tools/get_decision_trace/{decisionId}/enhanced
```

`decisionId` 必须是 `control_decision.id`。基础接口返回决策摘要和 `control_decision_trace` 阶段记录；增强接口返回 `AgentToolResult`，额外聚合 Traffic-R 推理结果、安全层事件、fallback 事件、trace 时间线和 CityFlow 下发 metadata，适合 Agent 回答“为什么模型选了 A，最后执行 B”。

#### 系统健康 / `get_system_health`

```http
GET /api/v1/runtime/system-health?limit=20
GET /api/v1/agent/tools/get_system_health?limit=20
GET /api/v1/agent/tools/get_system_health/enhanced?limit=20
```

基础接口返回数据库可访问状态、关键运行表行数、仿真会话状态分布和最近 `service_health_snapshot`。增强接口返回 `AgentToolResult`，会主动探测 Spring Boot、Python CityFlow、Traffic-R、WebSocket、数据库、本地 Traffic-R 隧道和实时状态缓存；排查 Traffic-R 无法调用、隧道断开或 CityFlow 不可达时优先使用增强接口。

#### Traffic-R 推理日志 / `get_model_inference_log`

```http
GET /api/v1/runtime/model-inferences?sid={sid}&intersectionId={intersectionId}&limit=20
GET /api/v1/agent/tools/get_model_inference_log?sid={sid}&intersectionId={intersectionId}&limit=20
```

返回 `traffic_r_inference_log` 和每条日志下的 `traffic_r_inference_result`。当前只覆盖已落库的推理记录；Traffic-R 请求失败但尚未进入控制决策 metadata 的场景仍见 `RISK-031`。

#### fallback / 安全 / 告警 / 应急事件查询

```http
GET /api/v1/runtime/fallback-events?sid={sid}&intersectionId={intersectionId}&limit=20
GET /api/v1/agent/tools/get_fallback_events?sid={sid}&intersectionId={intersectionId}&limit=20&messageId={messageId}

GET /api/v1/runtime/safety-events?sid={sid}&intersectionId={intersectionId}&decisionId={decisionId}&limit=20
GET /api/v1/agent/tools/get_safety_events?sid={sid}&intersectionId={intersectionId}&decisionId={decisionId}&limit=20&messageId={messageId}

GET /api/v1/runtime/alerts?sid={sid}&level={level}&status={status}&limit=20
GET /api/v1/agent/tools/get_alert_events?sid={sid}&level={level}&status={status}&limit=20&messageId={messageId}

GET /api/v1/runtime/emergency-events?sid={sid}&status={status}&limit=20
GET /api/v1/agent/tools/get_emergency_events?sid={sid}&status={status}&limit=20&messageId={messageId}
GET /api/v1/agent/tools/get_emergency_vehicle_status?sid={sid}&vehicleId={vehicleId}&limit=20
GET /api/v1/agent/tools/draft_emergency_dispatch?sid={sid}&startIntersection={from}&endIntersection={to}&evId={evId}&evType=ambulance&priority=1
```

用途：

- `get_fallback_events`：查询 Traffic-R、MaxPressure 等策略 fallback 事件。
- `get_safety_events`：查询安全约束修改、拒绝或回退决策的事件。
- `get_alert_events`：查询系统告警。
- `get_emergency_events`：查询应急车辆/绿波任务主事件。
- `get_emergency_vehicle_status`：查询实时应急车辆、路线进度、ETA 和绿波状态；优先读内存最新帧，数据库应急事件只作为补充。
- `draft_emergency_dispatch`：生成起终点应急路线与绿波建议草案，只提供人工确认前的方案，不执行 CityFlow 控制。

#### 知识库与配置一致性调试

```http
GET /api/v1/agent/tools/search_knowledge_base?query={query}&topK=5&scope={scope}
GET /api/v1/agent/tools/audit_configuration_consistency?sid={sid}&sceneCode={sceneCode}
```

- `search_knowledge_base`：本地项目文档检索始终启用；百炼知识库需要配置 `bailian.knowledge.enabled=true`、`endpoint`、`access-key-id`、`access-key-secret`、`workspace-id` 和 `index-id` 后才会通过官方 SDK 调用 `Retrieve`。当前项目只使用一个百炼知识库，其他知识库内容应先合并到该知识库。未配置或调用失败时返回 warning 并继续提供本地文档结果。
- `audit_configuration_consistency`：检查 CityFlow roadnet、Traffic-R phaseCode、数据库 `signal_phase`、实时 signal frame 和 roadnet 相位映射是否一致，优先用于排查安全层频繁阻断 Traffic-R 相位、相位 code 不匹配和 roadnet/数据库不同步问题。

#### Agent 内部 LangChain4j 工具层

除 HTTP 查询入口外，后端已新增 `com.traffic.agent.tool` 包，把第一批工具封装为 LangChain4j `@Tool` 方法，供 `/api/v1/agent/chat` 编排流程内部调用。

当前工具类：

| 工具类 | 工具 |
|---|---|
| `TrafficRuntimeAgentTools` | `get_current_simulation_state`、`get_intersection_detail`、`get_road_detail` |
| `TrafficDecisionAgentTools` | `get_latest_control_decisions`、`get_decision_trace`、`get_model_inference_log` |
| `TrafficHealthAgentTools` | `get_system_health`、`audit_configuration_consistency` |
| `TrafficKnowledgeAgentTools` | `search_knowledge_base` |
| `TrafficDiagnosisAgentTools` | `diagnose_congestion`、`detect_signal_anomaly`、`detect_spillback_risk`、`get_safety_constraint_log`、`get_fallback_log`、`get_region_metrics`、`compare_strategy_metrics`、`get_fallback_events`、`get_safety_events`、`get_alert_events` |
| `EmergencyAgentTools` | `get_emergency_events`、`get_emergency_vehicle_status`、`draft_emergency_dispatch` |

工具实现规则：

- `@Tool` 方法只能调用后端 Service，不能调用 Controller，也不能用 `RestTemplate` 自调用本后端 HTTP 接口。
- 实时状态类工具调用 `LiveSimulationStateService`，读取内存最近帧；历史复盘类工具调用 `RuntimeQueryService`，读取数据库记录。
- 工具统一返回 `AgentToolResult`：`success`、`toolName`、`data`、`evidence`、`warnings`、`timestamp`。
- 工具异常会被包装为 `success=false` 的结构化结果，并记录为 `agent_tool_call.status=FAILED`，不应导致整个 Agent 对话崩溃。
- 当前工具全部只读或草案生成，不推进仿真、不下发相位、不切换策略、不执行应急绿波。
- `draft_emergency_dispatch` 只能输出路线和绿波建议草案；真正执行必须走应急业务接口、统一仲裁层和安全层。
- `search_knowledge_base` 是“本地文档 + 百炼 Retrieve 语义切片”的混合检索。百炼配置缺失或调用失败时必须明确返回 warning，不能伪造远端检索结果。百炼返回的语义切片只作为工具证据交给 LLM，总结后的自然语言回答由 `/api/v1/agent/chat` 返回给前端，前端不应直接展示工具 JSON。

诊断类工具返回的 `data` 不是自然语言散文，而是结构化诊断报告，至少包含：

```json
{
  "conclusion": "intersection_3 存在拥堵风险",
  "evidence": ["movement E_0 queue=18, avg_wait=94.2s"],
  "impactScope": ["主要积压 movement=E_0"],
  "possibleCauses": ["等待时间偏高，可能存在放行不足或下游排空能力不足"],
  "recommendations": ["建议检查下游溢出风险；相位调整必须经过安全层和仲裁层"],
  "confidence": 0.81,
  "humanConfirmationRequired": ["任何控制策略变化都需要人工确认"],
  "data": {}
}
```

当前诊断工具边界：

- `diagnose_congestion`：基于内存实时路口 movement、道路状态和当前仿真帧做规则诊断。
- `detect_signal_anomaly`：基于数据库最近控制决策/安全约束事件，加上内存实时 movement 状态检测异常风险。
- `detect_spillback_risk`：基于内存实时道路或 roadLink 下游道路状态检测溢出风险。
- `get_region_metrics`：历史复盘工具，基于显式采样或低频摘要产生的 `intersection_state_snapshot`、`road_state_snapshot` 聚合区域指标；默认实时状态不再依赖这些快照表。
- `compare_strategy_metrics`：历史复盘/实验工具，基于显式实验或低频摘要产生的 `simulation_frame` 聚合不同 session / controller 的策略指标。正式策略结论要求同 roadnet、flow、随机种子和仿真时长。
- `get_safety_constraint_log`、`get_fallback_log`：分别是安全事件和 fallback 事件的语义化日志工具。

### 3.6 Agent 会话、消息与工具调用审计

这些接口用于保存和查询 Agent 自身交互数据。`/api/v1/agent/chat` 是前端 Agent 的唯一聊天入口：前端不再直连百炼平台 Agent API，而是调用 Spring Boot 后端自建 Agent 编排层；后端通过 LangChain4j + OpenAI-compatible LLM API Key 调用模型，生成工具规划、执行只读工具并组装回答。`/api/v1/agent/chat` 会自动创建/读取会话、写入用户消息、记录 LLM 工具规划、执行工具并写入工具调用审计；外部手动调用 `/api/v1/agent/tools/**` 时，仍可传入 `messageId` 形成可复盘链路。

调试期后端会通过 `AGENT_DEBUG` logger 记录 Agent 运行过程，包括 `agent.chat.start/end/error`、`agent.llm.request/response/error`、`agent.tool.start/result/error`。日志用于分析工具调用、参数、模型返回和异常；联调结束后应降低日志级别，避免长期保存过多用户问题和模型输出。

#### Agent 聊天编排入口

```http
POST /api/v1/agent/chat
Content-Type: application/json
```

请求：

```json
{
  "message": "当前仿真状态怎么样？",
  "sid": "run_001",
  "conversationId": "可选，继续已有 Agent 会话",
  "sessionId": "可选，外部客户端会话标识",
  "context": {
    "currentPage": "dashboard",
    "intersectionId": "intersection_1_1"
  }
}
```

字段说明：

| 字段 | 必需 | 含义 |
|---|---:|---|
| `message` | 是 | 用户问题，最大 4000 字符 |
| `sid` | 否 | 本项目仿真会话 ID，用于关联 `simulation_session` 和实时工具查询 |
| `conversationId` | 否 | 已有 `agent_conversation.id`；不传时后端自动创建新会话 |
| `sessionId` | 否 | 外部客户端会话 ID，不等同于仿真 `sid`；当前不再表示百炼平台 Agent 会话 |
| `context` | 否 | 前端页面上下文，如当前页面、路口 ID、道路 ID 等 |

响应：

```json
{
  "reply": "当前仿真运行正常……",
  "sessionId": null,
  "source": "llm-api | config",
  "fallback": false,
  "conversationId": "agent_conversation UUID",
  "messageId": "assistant agent_message UUID",
  "toolCalls": [
    {
      "id": "agent_tool_call UUID",
      "toolName": "get_current_simulation_state",
      "arguments": {"sid": "run_001"},
      "status": "SUCCESS",
      "latencyMs": 12,
      "errorMessage": null
    }
  ],
  "evidence": [
    {
      "source": "tool",
      "name": "get_current_simulation_state",
      "summary": "工具 get_current_simulation_state 返回真实后端数据",
      "value": {}
    }
  ],
  "planTrace": {
    "intent": "current_state",
    "rationale": "需要查询真实仿真状态",
    "needsTools": true,
    "rawPlan": "{...LLM 输出的 JSON 规划...}",
    "plannerSource": "llm-api | config"
  }
}
```

编排规则：

- `AgentController` 只调用 `AgentOrchestratorService`，不直接调用模型。
- 工具选择由 LLM 输出格式化 JSON 规划，后端只做 JSON 解析、工具白名单过滤和参数校验，并调用 `com.traffic.agent.tool` 下的 `@Tool` 封装类执行。
- 当前白名单工具均为只读工具，不下发信号控制动作。
- LLM 规划轨迹会以 `llm_tool_plan` 写入 `agent_tool_call`；每个真实工具调用也会写入 `agent_tool_call`。
- 涉及实时状态的问题必须基于工具结果回答；如果工具失败或没有真实数据，回答必须说明无法获取，不能编造。

#### 创建/查询 Agent 会话

```http
POST /api/v1/agent/conversations
Content-Type: application/json
```

请求：

```json
{
  "userId": "可选 UUID",
  "sid": "可选仿真会话 sid",
  "externalSessionId": "可选外部客户端会话 ID",
  "title": "本轮诊断会话"
}
```

查询：

```http
GET /api/v1/agent/conversations?sid={sid}&externalSessionId={externalSessionId}&userId={userId}&limit=20
GET /api/v1/agent/conversations/{conversationId}
```

#### 创建/查询 Agent 消息

```http
POST /api/v1/agent/conversations/{conversationId}/messages
Content-Type: application/json
```

请求：

```json
{
  "role": "user | assistant | tool",
  "content": "消息内容"
}
```

查询：

```http
GET /api/v1/agent/conversations/{conversationId}/messages?limit=20
```

#### 工具调用审计

显式写入：

```http
POST /api/v1/agent/messages/{messageId}/tool-calls
Content-Type: application/json
```

请求：

```json
{
  "toolName": "get_current_simulation_state",
  "arguments": {"sid": "run_001"},
  "result": {"summary": "工具结果摘要或完整结果"},
  "status": "SUCCESS",
  "latencyMs": 12,
  "errorMessage": null
}
```

查询：

```http
GET /api/v1/agent/messages/{messageId}/tool-calls?limit=20
GET /api/v1/agent/tool-calls?toolName={toolName}&status={status}&limit=20
```

注意：

- `agent_tool_call.result_payload` 会由后端截断到约 12,000 字符，避免 Agent 一次工具调用把过大的历史数据写入数据库。
- 工具审计不得保存 API Key、鉴权头或百炼平台密钥。

### 3.7 路口数据读写

读取全部路口：

```http
GET /api/v1/intersections
```

按路口编码读取：

```http
GET /api/v1/intersections/{code}
```

更新路口状态：

```http
PATCH /api/v1/intersections/{code}/status
Content-Type: application/json
```

请求：

```json
{
  "status": "online"
}
```

`status` 允许 `online`、`maintenance`、`offline`。

## WebSocket 接口

前端连接：

```text
ws://localhost:8080/ws/v1/simulations/{sid}
```

当前阶段只要求处理：

- `sim.frame`
- `sim.status`
- `sim.error`

实时帧示例：

```json
{
  "v": "1.0",
  "type": "sim.frame",
  "sid": "run_001",
  "seq": 1,
  "simTime": 1.0,
  "sentAt": "2026-07-08T20:00:00+08:00",
  "data": {
    "status": "running",
    "vehicles": [],
    "roads": [],
    "laneStates": {
      "intersection_1_1": {
        "lanes": {
          "WT": {"queue_len": 0, "avg_wait_time": 0.0, "cells": [0, 0, 0, 0]},
          "WL": {"queue_len": 0, "avg_wait_time": 0.0, "cells": [0, 0, 0, 0]}
        }
      }
    },
    "intersections": [],
    "signals": [],
    "metrics": {
      "vehicleCount": 0,
      "activeVehicleCount": 0,
      "scheduledDepartureCount": 0,
      "queueCount": 0,
      "avgSpeed": 0,
      "avgWait": 0,
      "throughput": 0
    }
  }
}
```

## Python CityFlow 服务接口

Python 服务只对 Spring Boot 开放，不直接对前端开放。

当前云端 CityFlow 服务默认部署在阿里云 `http://39.105.75.87:9000`，本地 Spring Boot 通过 `application.yml` 中的 `cityflow.base-url` 访问。除 `/health` 外，`/cityflow/**` 接口如果配置了 `CITYFLOW_API_TOKEN`，必须携带：

```http
X-CityFlow-Token: <team-token>
X-CityFlow-Client: <client-id>
```

说明：

- `X-CityFlow-Token` 用于公网访问保护，必须与 Python 服务环境变量 `CITYFLOW_API_TOKEN` 一致。
- `X-CityFlow-Client` 仅作为兼容保留字段，不再参与会话归属判断。所有操作都以不可预测的 `sid` 定位会话，创建新会话不会清理其他会话。
- 当前 Roadnet / Frame DTO 只保留 CityFlow 原始 `id`，没有 `cityflowId` 字段；前端应直接使用 `id` 与 CityFlow 路网对象对应。

`sim.frame.data.status` 当前取值：

| 值 | 含义 |
|---|---|
| `running` | 会话仍在运行，可继续读取快照和下发策略 |
| `finished` | 场景已自然结束；这是最后一帧，Python 已释放 CityFlow Engine |

### 健康检查

```http
GET /health
```

用途：

- 确认 Python 服务是否启动。
- 确认当前引擎模式是 `mock` 还是 `cityflow`。
- 查看可用场景列表。

### 获取路网

```http
GET /cityflow/scenes/{sceneId}/roadnet
```

返回结构应与 `RoadnetResponse` 对齐。

### 创建仿真

```http
POST /cityflow/simulations
Content-Type: application/json
```

请求：

```json
{
  "sceneId": "jinan_3x4",
  "speed": 1.0,
  "warmupSeconds": 0.0
}
```

响应：

```json
{
  "sid": "run_001",
  "sceneId": "jinan_3x4",
  "status": "created",
  "engineMode": "mock"
}
```

### 获取下一帧

```http
GET /cityflow/simulations/{sid}/frame
```

用途：

- 返回 Python CityFlow 当前缓存快照。
- 真实 `cityflow` 模式下，仿真推进由 `/start` 后的 Python 后台 worker 连续执行；`/frame` 不再同步阻塞执行 `next_step()`。
- 返回当前车辆、道路、lane-level 状态、路口、信号和全局指标。

Python 当前返回裸 `SimFrameData` 兼容字段，同时可附带 `sid`、`sceneId`、`seq`、`simTime`、`engineMode` 等额外字段。Spring Boot 对前端推送时仍以 `WsMessage` 外层字段为准。

### 控制仿真生命周期

```http
POST /cityflow/simulations/{sid}/start
POST /cityflow/simulations/{sid}/pause
POST /cityflow/simulations/{sid}/stop
```

这三个接口由 Spring Boot 的仿真控制接口转发调用。前端不得直接调用 Python CityFlow。

### 下发控制决策

```http
POST /cityflow/simulations/{sid}/actions
Content-Type: application/json
```

用途：

- Spring Boot 将统一 `ControlDecision` 下发给 Python CityFlow。
- Python 将项目协议中的 `phaseIndex` 转为 CityFlow 的 `phaseIndex - 1`。
- Python 调用 `set_tl_phase` 并更新 session 中记录的当前相位。

请求：

```json
{
  "source": "traffic-r",
  "simTime": 120.0,
  "decisions": [
    {
      "intersectionId": "intersection_1_1",
      "controllerType": "traffic-r",
      "phaseIndex": 2,
      "phaseCode": "NTST",
      "durationSec": 10,
      "confidence": 0.82,
      "reason": "north-south queue is larger than east-west queue",
      "metadata": {
        "source": "traffic-r"
      }
    }
  ]
}
```

响应：

```json
{
  "sid": "run_001",
  "applied": [
    {
      "intersectionId": "intersection_1_1",
      "phaseIndex": 2,
      "cityflowPhaseId": 1,
      "phaseCode": "NTST",
      "status": "applied"
    }
  ]
}
```

## Spring Boot 策略接口

信号控制策略统一通过 `TrafficSignalController` 进入，Fixed-Time、Max-Pressure、Traffic-R / RL 必须返回同一种 `ControlDecision`。策略实现不得直接调用 Python CityFlow 服务，也不得直接推送 WebSocket。

### Traffic-R 云端模型接口

`controllerType=traffic-r` 时，Spring Boot 的 `RlController` / `TrafficRBatchController` 会通过 `CloudTrafficRClient` 调用云端 Traffic-R 服务。Traffic-R 只对 Spring Boot 开放，前端和 Python CityFlow 均不得直接调用。

Traffic-R 输入必须优先使用 CityFlow frame 中的 `laneStates`，而不是 road-level 汇总值。`laneStates` 按 LLMTSCS 官方 prompt 需要的 `WT/WL/ST/SL/ET/EL/NT/NL` movement lane 组织，每个 lane 包含 `queue_len`、`avg_wait_time` 和 4 个 cell。云端服务再按官方 `state2table()` 将 cell 0、cell 1、cell 2 + cell 3 格式化为 Segment 1/2/3。

```http
POST {traffic-r.base-url}{traffic-r.predict-path}
Content-Type: application/json
```

当前本地联调默认地址：

```text
http://127.0.0.1:16008/predict
```

同机部署时应通过环境变量改为：

```text
TRAFFIC_R_BASE_URL=http://127.0.0.1:6008
```

请求：

```json
{
  "sceneId": "jinan_3x4",
  "intersectionId": "intersection_1_1",
  "simTime": 120.0,
  "currentPhaseIndex": 1,
  "currentPhaseCode": "ETWT",
  "phaseCandidates": [
    {"phaseIndex": 1, "phaseCode": "ETWT"},
    {"phaseIndex": 2, "phaseCode": "NTST"},
    {"phaseIndex": 3, "phaseCode": "ELWL"},
    {"phaseIndex": 4, "phaseCode": "NLSL"}
  ],
  "observation": {
    "laneStates": {
      "intersection_1_1": {
        "lanes": {
          "WT": {"queue_len": 8, "avg_wait_time": 24.0, "cells": [3, 2, 1, 0]},
          "WL": {"queue_len": 1, "avg_wait_time": 3.0, "cells": [0, 1, 0, 0]},
          "ST": {"queue_len": 0, "avg_wait_time": 0.0, "cells": [0, 0, 0, 0]},
          "SL": {"queue_len": 0, "avg_wait_time": 0.0, "cells": [0, 0, 0, 0]},
          "ET": {"queue_len": 0, "avg_wait_time": 0.0, "cells": [0, 0, 0, 0]},
          "EL": {"queue_len": 0, "avg_wait_time": 0.0, "cells": [0, 0, 0, 0]},
          "NT": {"queue_len": 0, "avg_wait_time": 0.0, "cells": [0, 0, 0, 0]},
          "NL": {"queue_len": 0, "avg_wait_time": 0.0, "cells": [0, 0, 0, 0]}
        }
      }
    },
    "metrics": {
      "vehicleCount": 16,
      "queueCount": 10,
      "avgSpeed": 8.5,
      "avgWait": 12.0,
      "throughput": 3
    }
  }
}
```

响应：

```json
{
  "intersectionId": "intersection_1_1",
  "phaseIndex": 2,
  "phaseCode": "NTST",
  "durationSec": 10,
  "confidence": 0.8,
  "reason": "Traffic-R selected this phase from current traffic state",
  "parsedFromModel": true,
  "rawOutput": "Step 1: ... <signal>NTST</signal>",
  "inferenceTimeSec": 7.2
}
```

Spring Boot 会将 Traffic-R 响应转换为统一 `ControlDecision`，并校验 `phaseCode` 必须属于传入的 `phaseCandidates`。Traffic-R Jinan 在线服务只接收 `ETWT`、`NTST`、`ELWL`、`NLSL` 四个相位候选；如果 `parsedFromModel` 不是 `true` 或 `rawOutput` 为空，该响应必须视为无效，不能作为 RL 决策下发。

#### Traffic-R 批量决策接口

正式仿真调度必须使用批量接口，而不是对每个路口分别调用单路口 `/predict`。这与 LLMTSCS 的评测机制保持一致：一个决策周期内为所有路口构造 prompt，在云端一次 batch generate 后返回所有路口动作。

```http
POST {traffic-r.base-url}{traffic-r.batch-predict-path}
Content-Type: application/json
```

默认本地联调地址：

```text
http://127.0.0.1:16008/predict-batch
```

请求：

```json
{
  "sceneId": "jinan_3x4",
  "simTime": 120.0,
  "intersections": [
    {
      "intersectionId": "intersection_1_1",
      "currentPhaseIndex": 1,
      "currentPhaseCode": "ETWT",
      "phaseCandidates": [
        {"phaseIndex": 1, "phaseCode": "ETWT"},
        {"phaseIndex": 2, "phaseCode": "NTST"},
        {"phaseIndex": 3, "phaseCode": "ELWL"},
        {"phaseIndex": 4, "phaseCode": "NLSL"}
      ]
    }
  ],
  "observation": {
    "laneStates": {
      "intersection_1_1": {
        "lanes": {
          "WT": {"queue_len": 8, "avg_wait_time": 24.0, "cells": [3, 2, 1, 0]},
          "WL": {"queue_len": 1, "avg_wait_time": 3.0, "cells": [0, 1, 0, 0]}
        }
      }
    },
    "metrics": {
      "vehicleCount": 16,
      "queueCount": 10,
      "avgSpeed": 8.5,
      "avgWait": 12.0,
      "throughput": 3
    }
  }
}
```

响应：

```json
{
  "sceneId": "jinan_3x4",
  "simTime": 120.0,
  "decisions": [
    {
      "intersectionId": "intersection_1_1",
      "phaseIndex": 2,
      "phaseCode": "NTST",
      "durationSec": 10,
      "confidence": 0.85,
      "reason": "Traffic-R1 batch selected NTST",
      "parsedFromModel": true,
      "rawOutput": "Step 1: ... <signal>NTST</signal>",
      "inferenceTimeSec": 7.2
    }
  ]
}
```

批量调度默认每 10 秒仿真时间触发一次。若 Traffic-R 连续 3 次返回无效响应、超时或请求失败，Spring Boot 启用 `max-pressure` 作为整帧 fallback；fallback 期间仍继续请求 Traffic-R，直到连续 3 次模型输出有效后恢复应用 RL 决策。前端不得根据 Traffic-R 响应直接修改信号灯，信号灯和车辆动画必须只根据 Python CityFlow 返回的 `sim.frame.data.signals` 与车辆状态渲染。

`/predict` 仅保留为单路口冒烟测试兼容接口，不用于 Spring Boot 的正式仿真调度。

### ControlRequest

```json
{
  "sid": "run_001",
  "sceneId": "jinan_3x4",
  "controllerType": "traffic-r",
  "intersectionId": "intersection_1_1",
  "simTime": 120.0,
  "currentPhaseIndex": 1,
  "currentPhaseCode": "ETWT",
  "phaseCandidates": [
    {
      "phaseIndex": 1,
      "phaseCode": "ETWT",
      "roadLinkIndexes": []
    }
  ],
  "frame": {}
}
```

### ControlDecision

```json
{
  "intersectionId": "intersection_1_1",
  "controllerType": "traffic-r",
  "phaseIndex": 2,
  "phaseCode": "NTST",
  "durationSec": 10,
  "confidence": 0.82,
  "reason": "north-south queue is larger than east-west queue",
  "metadata": {
    "source": "traffic-r"
  }
}
```

说明：

- `phaseIndex` 使用本项目协议编号，从 `1` 开始。
- 下发到 CityFlow 时，由 Python 转为 `phaseIndex - 1`。
- `metadata` 只能放调试、模型来源、fallback 状态等补充信息，前端不能依赖其中字段完成核心渲染。
- 当前 Spring Boot 在获取一帧后计算策略决策，并将 `/cityflow/simulations/{sid}/actions` 异步提交给 Python CityFlow；`control.decision` 只表示后端已生成并提交动作，控制效果必须以后续 `sim.frame.data.signals` 为准。

### Python 错误响应

Python 服务错误统一返回：

```json
{
  "success": false,
  "code": "SESSION_NOT_FOUND",
  "message": "simulation session not found: run_xxx",
  "retryable": false
}
```

常见错误码：

| code | 含义 |
|---|---|
| `SCENE_NOT_FOUND` | 场景不存在 |
| `SESSION_NOT_FOUND` | 仿真会话不存在 |
| `INVALID_REQUEST` | 请求参数不合法 |
| `INVALID_JSON` | 请求体不是合法 JSON |
| `CITYFLOW_ENGINE_NOT_CONFIGURED` | 配置为真实 CityFlow 但尚未完成适配 |
| `INTERNAL_ERROR` | 未预期服务错误 |

## 字段命名规则

- JSON 字段使用 `camelCase`。
- Java 类名使用 `PascalCase`。
- Java 字段和方法使用 `camelCase`。
- 数据库表名和字段名使用 `snake_case`。
- 场景 ID、道路 ID、路口 ID 保持 CityFlow 原始命名。

## 变更规则

允许：

- 新增可选字段。
- 新增消息类型。
- 新增 topic。
- 新增接口，但必须补文档。

禁止：

- 删除已有字段。
- 改变已有字段含义。
- 前端直接访问 Python 服务。
- Controller 直接调用数据库或编写复杂业务逻辑。

### 应急车辆调度

```http
POST /api/v1/simulations/{sid}/dispatch
Content-Type: application/json
```

路径参数：

| 参数 | 类型 | 说明 |
|---|---|---|
| sid | String | 仿真任务唯一标识 |

请求：

```json
{
  "startCoord": { "x": 113.5, "y": 22.3 },
  "endCoord":   { "x": 113.6, "y": 22.4 },
  "evId":       "ambulance_001",
  "evType":     "fire_truck",
  "priority":   1,
  "maxSpeed":   20.0
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| startCoord | CoordDTO {x,y} | 是 | 起点坐标（米） |
| endCoord | CoordDTO {x,y} | 是 | 终点坐标（米） |
| evId | String | 是 | 应急车唯一标识 |
| evType | String | 否 | fire_truck / ambulance / police / convoy，默认 fire_truck |
| priority | Integer | 否 | 越小越高，默认 1 |
| maxSpeed | Double | 否 | 最高速度 m/s，默认 20.0 |

响应：

```json
{
  "success": true,
  "message": "ok",
  "data": {
    "sid": "run_001",
    "evId": "ev_default",
    "evType": "fire_truck",
    "priority": 1,
    "route": ["intersection_1_1", "intersection_2_1", "intersection_3_3"],
    "routeRoads": ["road_1_1_0", "road_2_1_0"],
    "estimatedTravelTime": 120.0
  }
}
```

内部流程：EmergencyController → EmergencyService → HttpCityFlowClient.dispatchEV() → Python POST /cityflow/simulations/{sid}/dispatch。

### 内部车辆注入

```http
POST /cityflow/simulations/{sid}/dispatch
Content-Type: application/json
```

请求由 Spring Boot EmergencyService 自动构造，字段同上方的请求表。
Python 直接返回 data 字段内容（同上方响应 data），Spring Boot 包装为 ApiResponse 后返回前端。
