# 后端调用链说明

本文档固定当前阶段的系统调用链。所有成员开发接口、联调前端或调整 Python CityFlow 服务时，都必须遵守本文档。

## 当前阶段目标

当前阶段已经从单纯可视化联调推进到“可视化仿真 + 策略调度验证”。系统需要支持 `fixed-time`、`max-pressure`、`traffic-r` 三类控制器，并且前端动画必须只根据 CityFlow 返回的真实帧渲染。

核心约束：

1. 前端只连接 Spring Boot 主后端。
2. 前端不得直接连接 Python CityFlow 服务。
3. Spring Boot 通过 HTTP 调用 Python CityFlow 服务。
4. Python 服务读取 `roadnet` / `flow`，在真实 CityFlow 模式下后台连续推进仿真并缓存快照。
5. Spring Boot 负责策略调度，并将统一 `ControlDecision` 通过 `/actions` 下发给 Python CityFlow。
6. 实时帧由 Spring Boot 通过 WebSocket 推送给前端。
7. `sim.frame` 是前端实时渲染车辆、信号灯和指标的唯一可信数据来源。

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
  | 返回后台推进后的最新缓存快照
  v
Spring Boot SimulationService
  |
  | StrategyDispatchService 低频生成策略决策
  | 异步 POST /cityflow/simulations/{sid}/actions
  v
Python CityFlow set_tl_phase
  |
  | 后续 frame 中体现真实信号相位
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

`sim-python` 支持 `mock` 和 `cityflow` 两种模式。当前正式联调以 `SIM_ENGINE_MODE=cityflow` 为准，CityFlow 已部署到阿里云并由 Spring Boot 默认访问。真实模式下：

- `/start` 后 Python 后台 worker 连续执行 CityFlow step 并缓存 latest frame。
- `/frame` 返回缓存快照，不再同步推进一步。
- `/actions` 接收 Spring Boot 下发的统一 `ControlDecision`，转换为 CityFlow phase id 后调用 `set_tl_phase`。
- `/cityflow/**` 接口在公网部署时必须携带 `X-CityFlow-Token`，并使用 `X-CityFlow-Client` 做团队成员会话隔离。

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
| GET | `/cityflow/simulations/{sid}/frame` | 获取最新缓存帧 |
| POST | `/cityflow/simulations/{sid}/actions` | 下发策略相位决策 |
| POST | `/cityflow/simulations/{sid}/start` | 启动 Python 后台推进 |
| POST | `/cityflow/simulations/{sid}/pause` | 暂停 Python 后台推进 |
| POST | `/cityflow/simulations/{sid}/stop` | 停止并销毁 Python 会话 |

### Spring Boot 访问 Traffic-R

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/health` | 模型服务健康检查 |
| POST | `/predict-batch` | 一个决策周期内批量输出所有路口相位 |

Traffic-R 只由 Spring Boot 调用。正式仿真调度使用 `/predict-batch`，不再逐路口调用 `/predict`。

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
frontend -> 根据 Traffic-R 响应直接伪造信号灯状态
```

## 阶段验收标准

当前阶段验收至少满足：

1. 前端能通过 Spring Boot 获取静态路网。
2. Spring Boot 能通过 `CityFlowClient` 获取 Python 返回的 roadnet。
3. Spring Boot 能创建仿真会话并获得 `sid`。
4. Spring Boot 能定时调用 Python frame 接口。
5. Spring Boot 能通过 WebSocket 推送 `sim.frame`。
6. 前端能根据 `sim.frame` 实时更新车辆动画。
7. 前端信号灯只使用 `sim.frame.data.signals`，不使用模型输出直接改灯。
8. `fixed-time`、`max-pressure`、`traffic-r` 都通过统一 `ControlDecision` 下发到 Python `/actions`。
9. Traffic-R 调度使用 `/predict-batch`，并在无效/超时后按规则 fallback 到 Max-Pressure。
