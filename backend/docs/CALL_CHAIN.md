# 后端调用链说明

本文档固定当前阶段的系统调用链。所有成员开发接口、联调前端或调整 Python CityFlow 服务时，都必须遵守本文档。

## 当前阶段目标

今天只做 CityFlow 可视化仿真链路，不接入 RL、Max-Pressure 或应急绿波控制。

核心约束：

1. 前端只连接 Spring Boot 主后端。
2. 前端不得直接连接 Python CityFlow 服务。
3. Spring Boot 通过 HTTP 调用 Python CityFlow 服务。
4. Python 服务读取 `roadnet` / `flow` 并返回仿真帧。
5. 实时帧由 Spring Boot 通过 WebSocket 推送给前端。
6. `sim.frame` 是前端实时渲染车辆动画的唯一实时数据来源。

## 静态路网调用链

```text
前端 Vue
  |
  | REST: GET /api/v1/scenes/{sceneId}/roadnet
  v
Spring Boot 主后端
  |
  | RoadnetService -> CityFlowClient
  | HTTP: GET /cityflow/scenes/{sceneId}/roadnet
  v
Python CityFlow 服务
  |
  | 读取 roadnet_3_4.json
  | 解析 intersections / roads / roadLinks / phases
  v
返回 RoadnetResponse
  |
  v
前端绘制路口、道路、车道和相位
```

## 实时仿真帧调用链

```text
Spring Boot SimulationFrameScheduler
  |
  | 定时轮询
  v
Python CityFlow 服务
  |
  | GET /cityflow/simulations/{sid}/frame
  | 推进一步仿真并返回当前帧
  v
Spring Boot SimulationService
  |
  | 封装 WsMessage<SimFrameData>
  v
SimulationWebSocketHandler
  |
  | 推送 sim.frame
  v
前端 Vue Canvas / SVG / 地图渲染层
```

## Python 服务当前状态

`sim-python` 当前处于 `mock` 引擎模式：它会读取真实 roadnet/flow 文件，但车辆位置、信号相位、道路状态和指标是用于可视化联调的确定性模拟结果，不等价于真实 CityFlow Engine 输出。

真实 CityFlow Engine 接入点已经预留。接入前必须确认本机 CityFlow 包版本、Engine 初始化 config、推进 API、车辆位置 API、信号相位 API。

## 接口清单

### 前端访问 Spring Boot

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/v1/scenes/{sceneId}/roadnet` | 获取静态路网 |
| POST | `/api/v1/simulations` | 创建仿真会话 |
| POST | `/api/v1/simulations/{sid}/start` | 启动仿真 |
| POST | `/api/v1/simulations/{sid}/pause` | 暂停仿真 |
| POST | `/api/v1/simulations/{sid}/stop` | 停止仿真 |
| WebSocket | `/ws/v1/simulations/{sid}` | 接收 `sim.frame` |

### Spring Boot 访问 Python CityFlow

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/health` | 健康检查、引擎模式、场景列表 |
| GET | `/cityflow/scenes/{sceneId}/roadnet` | 获取静态路网 |
| POST | `/cityflow/simulations` | 创建 Python 仿真会话 |
| GET | `/cityflow/simulations/{sid}/frame` | 推进一步并获取当前帧 |

## 禁止调用方式

以下做法一律禁止：

```text
前端 Vue -> Python CityFlow 服务
前端 Vue -> CityFlow Engine
Python CityFlow 服务 -> 前端 WebSocket
Controller -> 直接写复杂业务逻辑
Controller -> 直接调用 Python HTTP
strategy -> 直接推送 WebSocket
agent -> 绕过后端安全校验下发控制指令
```

## 阶段验收标准

进入 RL、Max-Pressure 或应急控制前，必须先满足：

1. 前端能通过 Spring Boot 获取静态路网。
2. Spring Boot 能通过 `CityFlowClient` 获取 Python 返回的 roadnet。
3. Spring Boot 能创建仿真会话并获得 `sid`。
4. Spring Boot 能定时调用 Python frame 接口。
5. Spring Boot 能通过 WebSocket 推送 `sim.frame`。
6. 前端能根据 `sim.frame` 实时更新车辆动画。
