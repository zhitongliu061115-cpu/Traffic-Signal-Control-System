# 阶段 2 调用链说明

本文档固定当前阶段的系统调用链。所有成员开发接口、前端联调、Python CityFlow 服务时，都必须按本文档执行。

## 1. 总原则

当前阶段只做 **CityFlow 可视化仿真效果**，不接入 RL、Max-Pressure 或应急绿波控制。

核心约束：

1. 前端只连接 Spring Boot 主后端。
2. 前端不得直接连接 Python CityFlow 服务。
3. Spring Boot 通过 HTTP 调用 Python CityFlow 服务。
4. Python 服务负责读取 `roadnet` / `flow` 并推进 CityFlow Engine。
5. 实时帧必须由 Spring Boot 通过 WebSocket 推送给前端。
6. `sim.frame` 是前端实时渲染车辆动画的唯一实时数据来源。

## 2. 静态路网调用链

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

前端职责：

- 调用 Spring Boot 的 roadnet 接口。
- 根据 `intersections` 绘制路口。
- 根据 `roads.points` 绘制道路。
- 根据 `laneCount` 调整道路宽度。
- 根据 `phases` 和 `roadLinks` 建立相位到放行方向的映射。

Spring Boot 职责：

- 暴露前端 REST 接口。
- 调用 Python CityFlow 服务。
- 必要时做协议转换和统一响应封装。
- 后续负责将静态路网入库。

Python CityFlow 职责：

- 读取 CityFlow 原始路网文件。
- 将 CityFlow 原始结构转换成 CFRP `RoadnetResponse`。
- 不负责前端协议入口，不负责数据库保存。

## 3. 实时仿真帧调用链

```text
Python CityFlow 服务
  |
  | 推进 CityFlow Engine 一步
  | 计算车辆状态 / 道路状态 / 路口状态 / 信号状态 / 全局指标
  v
Spring Boot 主后端
  |
  | SimulationFrameScheduler 定时拉取 frame
  | SimulationService 组装 WsMessage<SimFrameData>
  | SimulationWebSocketHandler 推送 sim.frame
  v
前端 Vue
  |
  | 根据 vehicles[].id 复用车辆对象
  | 根据 x / y / angle / speed 更新动画
  v
Canvas / SVG / 地图渲染层
```

前端职责：

- 连接 `ws://localhost:8080/ws/v1/simulations/{sid}`。
- 只处理 Spring Boot 推送的 `sim.frame`。
- 用车辆 `id` 匹配上一帧和当前帧。
- 用坐标插值实现平滑动画。
- 根据道路 `level` 更新道路颜色。
- 根据 `signals[].phaseIndex` 更新信号灯或放行方向。

Spring Boot 职责：

- 管理仿真会话 `sid`。
- 定时调用 Python 的 frame 接口。
- 将 Python 返回数据封装为 CFRP WebSocket 消息。
- 统一推送给所有订阅该 `sid` 的前端连接。
- 后续保存仿真会话和指标快照。

Python CityFlow 职责：

- 保存 CityFlow Engine 运行态。
- 根据 `sid` 推进对应仿真会话。
- 返回当前帧 `SimFrameData`。
- 不主动推送 WebSocket。

## 4. 当前接口清单

### 4.1 前端访问 Spring Boot

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/v1/scenes/{sceneId}/roadnet` | 获取静态路网 |
| POST | `/api/v1/simulations` | 创建仿真会话 |
| POST | `/api/v1/simulations/{sid}/start` | 启动仿真 |
| POST | `/api/v1/simulations/{sid}/pause` | 暂停仿真 |
| POST | `/api/v1/simulations/{sid}/stop` | 停止仿真 |
| WebSocket | `/ws/v1/simulations/{sid}` | 接收 `sim.frame` |

### 4.2 Spring Boot 访问 Python CityFlow

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/cityflow/scenes/{sceneId}/roadnet` | 获取静态路网 |
| POST | `/cityflow/simulations` | 创建 Python 仿真会话 |
| GET | `/cityflow/simulations/{sid}/frame` | 推进一步并获取当前帧 |

## 5. 禁止调用方式

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

## 6. 对应代码位置

| 职责 | 代码位置 |
|---|---|
| Spring Boot 路网接口 | `backend/src/main/java/com/traffic/scene/controller/SceneController.java` |
| 路网业务编排 | `backend/src/main/java/com/traffic/roadnet/service/RoadnetService.java` |
| Python 服务调用边界 | `backend/src/main/java/com/traffic/cityflow/client/CityFlowClient.java` |
| HTTP 调用实现 | `backend/src/main/java/com/traffic/cityflow/client/HttpCityFlowClient.java` |
| 仿真会话接口 | `backend/src/main/java/com/traffic/simulation/controller/SimulationController.java` |
| 仿真会话业务 | `backend/src/main/java/com/traffic/simulation/service/SimulationService.java` |
| 定时拉取 frame | `backend/src/main/java/com/traffic/simulation/service/SimulationFrameScheduler.java` |
| WebSocket 推送 | `backend/src/main/java/com/traffic/simulation/websocket/SimulationWebSocketHandler.java` |
| Python 服务地址配置 | `backend/src/main/resources/application.yml` |
| Python CityFlow 服务 | `sim-python/app/server.py` |

## 7. 阶段验收标准

阶段 2 完成时必须满足：

1. 前端能通过 Spring Boot 获取静态路网。
2. Spring Boot 能通过 `CityFlowClient` 获取 Python 返回的 roadnet。
3. Spring Boot 能创建仿真会话并获得 `sid`。
4. Spring Boot 能定时调用 Python frame 接口。
5. Spring Boot 能通过 WebSocket 推送 `sim.frame`。
6. 前端能根据 `sim.frame` 实时更新车辆动画。

只要这 6 条没有完成，就不要进入 RL、Max-Pressure、应急绿波或智能体开发。
