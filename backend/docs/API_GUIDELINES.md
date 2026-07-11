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

### 3.5 运行时数据库查询接口

这些接口只查询 Spring Boot 已落库的真实业务数据，不直接推进 CityFlow，也不下发控制动作。前端、运维页面和后续 MCP 工具应优先复用这一层，避免 Agent 直接拼 SQL。

当前提供两组等价入口：

- 前端/通用查询：`/api/v1/runtime/**`
- MCP 工具名兼容入口：`/api/v1/agent/tools/**`

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

返回内容包括会话摘要、最新 `simulation_frame`、已持久化帧数和最新帧信号状态。

#### 路口详情 / `get_intersection_detail`

```http
GET /api/v1/runtime/intersections/{intersectionId}?sid={sid}&sceneCode={sceneCode}
GET /api/v1/agent/tools/get_intersection_detail/{intersectionId}?sid={sid}&sceneCode={sceneCode}
```

`intersectionId` 可传标准表 `intersection.id`、CityFlow 路口 ID 或 `map_intersection_id`。返回路口基础信息、最新相位/排队状态、movement-level 快照、相位列表和 roadLink 列表。

#### 道路详情 / `get_road_detail`

```http
GET /api/v1/runtime/roads/{roadId}?sid={sid}&sceneCode={sceneCode}
GET /api/v1/agent/tools/get_road_detail/{roadId}?sid={sid}&sceneCode={sceneCode}
```

`roadId` 可传标准表 `road.id` 或 CityFlow 道路 ID。返回道路基础信息、最新道路快照和 lane 列表。

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
```

`decisionId` 必须是 `control_decision.id`。返回决策摘要和 `control_decision_trace` 阶段记录。

#### 系统健康 / `get_system_health`

```http
GET /api/v1/runtime/system-health?limit=20
GET /api/v1/agent/tools/get_system_health?limit=20
```

返回数据库可访问状态、关键运行表行数、仿真会话状态分布和最近 `service_health_snapshot`。当前是数据库视角的健康摘要，不会主动探测 Python CityFlow 或 Traffic-R。

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
```

用途：

- `get_fallback_events`：查询 Traffic-R、MaxPressure 等策略 fallback 事件。
- `get_safety_events`：查询安全约束修改、拒绝或回退决策的事件。
- `get_alert_events`：查询系统告警。
- `get_emergency_events`：查询应急车辆/绿波任务主事件。

#### Agent 内部 LangChain4j 工具层

除 HTTP 查询入口外，后端已新增 `com.traffic.agent.tool` 包，把第一批工具封装为 LangChain4j `@Tool` 方法，供 `/api/v1/agent/chat` 编排流程内部调用。

当前工具类：

| 工具类 | 工具 |
|---|---|
| `TrafficRuntimeAgentTools` | `get_current_simulation_state`、`get_intersection_detail`、`get_road_detail` |
| `TrafficDecisionAgentTools` | `get_latest_control_decisions`、`get_decision_trace`、`get_model_inference_log` |
| `TrafficHealthAgentTools` | `get_system_health` |
| `TrafficKnowledgeAgentTools` | `search_knowledge_base` |
| `TrafficDiagnosisAgentTools` | `diagnose_congestion`、`detect_signal_anomaly`、`detect_spillback_risk`、`get_safety_constraint_log`、`get_fallback_log`、`get_region_metrics`、`compare_strategy_metrics`、`get_fallback_events`、`get_safety_events`、`get_alert_events` |
| `EmergencyAgentTools` | `get_emergency_events` |

工具实现规则：

- `@Tool` 方法只能调用后端 Service，例如 `RuntimeQueryService`，不能调用 Controller，也不能用 `RestTemplate` 自调用本后端 HTTP 接口。
- 工具统一返回 `AgentToolResult`：`success`、`toolName`、`data`、`evidence`、`warnings`、`timestamp`。
- 工具异常会被包装为 `success=false` 的结构化结果，并记录为 `agent_tool_call.status=FAILED`，不应导致整个 Agent 对话崩溃。
- 当前工具全部只读，不推进仿真、不下发相位、不切换策略、不执行应急绿波。
- `search_knowledge_base` 当前是本地项目文档检索基础版，检索 `.md/.txt` 文件；尚不是百炼知识库 API。

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

- `diagnose_congestion`：基于路口 movement、道路快照和当前仿真帧做规则诊断。
- `detect_signal_anomaly`：基于最近控制决策、安全约束事件和 movement 快照检测异常风险。
- `detect_spillback_risk`：基于道路或 roadLink 下游道路快照检测溢出风险。
- `get_region_metrics`：基于 `intersection_state_snapshot`、`road_state_snapshot` 聚合区域指标。
- `compare_strategy_metrics`：基于 `simulation_frame` 聚合不同 session / controller 的策略指标。正式策略结论要求同 roadnet、flow、随机种子和仿真时长。
- `get_safety_constraint_log`、`get_fallback_log`：分别是安全事件和 fallback 事件的语义化日志工具。

### 3.6 Agent 会话、消息与工具调用审计

这些接口用于保存和查询 Agent 自身交互数据。`/api/v1/agent/chat` 会自动创建/读取会话、写入用户消息、记录 LLM 工具规划、执行工具并写入工具调用审计；外部手动调用 `/api/v1/agent/tools/**` 时，仍可传入 `messageId` 形成可复盘链路。

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
  "sessionId": "可选，兼容百炼外部 session_id",
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
| `sessionId` | 否 | 兼容百炼外部会话 ID，不等同于仿真 `sid` |
| `context` | 否 | 前端页面上下文，如当前页面、路口 ID、道路 ID 等 |

响应：

```json
{
  "reply": "当前仿真运行正常……",
  "sessionId": null,
  "source": "langchain4j | bailian | config",
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
    "plannerSource": "langchain4j | bailian | config"
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
  "externalSessionId": "可选百炼 session_id",
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
