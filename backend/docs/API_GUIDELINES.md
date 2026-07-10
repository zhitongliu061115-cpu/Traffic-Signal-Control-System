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
- `stop`：Python 停止并销毁该会话，Spring Boot 将会话置为结束状态。

### 3.4 数据库连接状态

```http
GET /api/v1/database/status
```

用途：

- 验证 Spring Boot 是否能连接 PostgreSQL。
- 返回核心业务表是否存在以及行数统计。

### 3.5 路口数据读写

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
- `X-CityFlow-Client` 用于团队成员会话隔离。同一个 client 创建新仿真时会清理自己旧会话，不影响其他 client。
- 当前 Roadnet / Frame DTO 只保留 CityFlow 原始 `id`，没有 `cityflowId` 字段；前端应直接使用 `id` 与 CityFlow 路网对象对应。

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
