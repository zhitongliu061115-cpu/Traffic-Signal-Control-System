# 接口协作规范

## 基本原则

1. 前端只调用 Spring Boot 主后端。
2. Spring Boot 内部调用 Python CityFlow 服务。
3. 静态路网使用 REST 获取。
4. 实时仿真帧使用 WebSocket 推送。
5. WebSocket 消息遵循 CFRP 协议。
6. 新增字段只能向后兼容，不能删除或改变已有字段含义。

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
  "speed": 1.0
}
```

响应：

```json
{
  "success": true,
  "message": "ok",
  "data": {
    "sid": "run_001",
    "sceneId": "jinan_3x4",
    "status": "created"
  }
}
```

### 控制仿真会话

```http
POST /api/v1/simulations/{sid}/start
POST /api/v1/simulations/{sid}/pause
POST /api/v1/simulations/{sid}/stop
```

当前阶段至少需要 `start` 可用，`pause` 和 `stop` 可以先保留基础状态切换。

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
    "intersections": [],
    "signals": [],
    "metrics": {
      "vehicleCount": 0,
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
  "speed": 1.0
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

- Python 推进仿真一步。
- 返回当前车辆、道路、路口、信号和全局指标。

Python 当前返回裸 `SimFrameData` 兼容字段，同时可附带 `sid`、`sceneId`、`seq`、`simTime`、`engineMode` 等额外字段。Spring Boot 对前端推送时仍以 `WsMessage` 外层字段为准。

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
