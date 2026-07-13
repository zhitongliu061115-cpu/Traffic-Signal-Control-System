# 后端架构说明

## 1. 架构目标

后端架构需要满足三个目标：

1. **可运行**：当前阶段能打通 CityFlow 路网加载、仿真会话创建和 WebSocket 实时推送。
2. **低耦合**：Spring Boot 主后端、Python 仿真服务、前端渲染、控制策略和数据库职责清晰分离。
3. **可扩展**：后续接入 RL、Max-Pressure、应急绿波、智能体和权限审计时，不破坏当前可视化链路。

## 2. 系统上下文

```text
Vue 前端
  | REST / WebSocket
  v
Spring Boot 主后端
  | HTTP
  v
Python CityFlow 仿真服务
  |
  v
CityFlow Engine
```

Spring Boot 是唯一对前端开放的后端入口。Python 服务只作为内部仿真和算法服务存在。

## 3. 分层设计

后端采用典型的 Controller / Service / Client / Repository 分层。

| 层级 | 职责 | 示例 |
|---|---|---|
| Controller | 接收前端请求，完成参数校验，返回统一响应 | `SceneController`、`SimulationController` |
| Service | 组织业务流程，不关心 HTTP 细节 | `RoadnetService`、`SimulationService` |
| Client | 调用外部服务，隔离外部协议变化 | `CityFlowClient`、`HttpCityFlowClient` |
| Repository | 访问数据库，后续保存场景和历史数据 | `scene.repository`、`simulation.repository` |
| DTO | 表达接口数据结构，不直接暴露实体对象 | `RoadnetResponse`、`SimFrameData` |

## 4. 包职责说明

### `common`

公共基础能力。允许被所有模块依赖，但不能反向依赖业务模块。

包含：

- `ApiResponse`
- `BusinessException`
- `GlobalExceptionHandler`
- `TimeUtils`

### `config`

Spring 框架配置。

包含：

- `CorsConfig`
- `WebSocketConfig`

规则：只放配置类，不写业务逻辑。

### `cityflow`

Python CityFlow 服务访问边界。

包含：

- `CityFlowClient`：接口，定义 Spring Boot 需要的仿真能力。
- `HttpCityFlowClient`：HTTP 实现，负责调用 Python 服务。
- `dto`：当 Python 原始返回结构和前端协议不一致时，放原始 DTO。
- `mapper`：当需要格式转换时，放转换逻辑。

规则：`cityflow` 不负责会话管理，不负责 WebSocket 推送，不负责数据库保存。

### `roadnet`

静态路网业务模块。

包含：

- 路口、道路、转向连接、相位等 DTO。
- `RoadnetService`：获取和组织路网数据。

规则：前端需要的路网结构以 CFRP 协议为准。

### `scene`

场景管理模块。

当前包含：

- `SceneController`：暴露 `GET /api/v1/scenes/{sceneId}/roadnet`。

后续扩展：

- 场景列表
- 场景导入
- 路网入库
- flow 文件管理

### `simulation`

仿真运行模块，是当前可视化主链路的核心。

包含：

- `SimulationController`：创建、启动、暂停、停止仿真会话。
- `SimulationService`：组织仿真会话流程。
- `SimulationSessionRegistry`：暂存运行中会话。
- `SimulationFrameScheduler`：定时从 Python 拉取 frame。
- `SimulationWebSocketHandler`：向前端推送 WebSocket 消息。
- `SimFrameData`、`WsMessage` 等实时帧 DTO。

规则：`simulation` 只负责仿真帧流转，不直接写具体控制策略。

### `strategy`

信号控制策略扩展点。

当前包含：

- `TrafficSignalController`
- `FixedTimeController`
- `MaxPressureController`
- `RlController`

当前不实现具体策略，只保留扩展边界。后续策略输出统一使用 `ControlDecision`，避免不同算法直接侵入仿真会话模块。

### `metrics`

指标统计和历史快照模块。

后续负责：

- 平均速度
- 平均等待时间
- 排队车辆数
- 通行量
- 控制前后效果对比

规则：不要逐帧保存全部车辆位置，只保存指标快照。

### `emergency`

应急车辆优先通行模块。

后续负责：

- 应急任务创建
- 应急车辆路径
- ETA 预测
- 沿线绿波计划
- 任务复盘

当前阶段不进入仿真主链路。

### `agent`

智能体模块。

后续负责：

- 自然语言查询实时状态
- 拥堵原因解释
- 调度建议生成
- 报告生成
- RAG 知识库问答

边界：智能体可以生成建议和待确认方案，但不能绕过安全校验直接下发信号控制指令。

### `audit`

审计模块。

后续负责：

- 用户操作日志
- 控制指令日志
- 人工接管记录
- 策略切换记录

## 5. 依赖方向规则

允许：

```text
controller -> service -> client / repository
service -> common
service -> dto
```

禁止：

```text
cityflow -> simulation
strategy -> controller
agent -> repository 直接跨模块写数据
frontend -> Python CityFlow
controller 中直接写复杂业务逻辑
```

跨模块调用应优先依赖接口，而不是依赖具体实现类。

## 6. 当前主流程

### 6.1 获取静态路网

```text
前端
  -> GET /api/v1/scenes/{sceneId}/roadnet
  -> SceneController
  -> RoadnetService
  -> CityFlowClient
  -> Python CityFlow
```

### 6.2 创建仿真会话

```text
前端
  -> POST /api/v1/simulations
  -> SimulationController
  -> SimulationService
  -> CityFlowClient
  -> Python CityFlow 创建 sid
  -> SimulationSessionRegistry 注册会话
```

### 6.3 实时推送仿真帧

```text
SimulationFrameScheduler
  -> SimulationService.publishNextFrame
  -> CityFlowClient.nextFrame
  -> Python CityFlow 返回最新缓存快照
  -> StrategyDispatchService 低频生成策略决策
  -> SafetyLayerService 校验相位合法性、持续时间和最小保持时间
  -> 仅安全通过的 ControlDecision 进入 CityFlowClient.applyControlActions 异步下发 actions
  -> SimulationWebSocketHandler
  -> 前端 WebSocket 接收 sim.frame
```

## 7. 扩展策略

新增功能时按以下规则放置：

- 新增 Python 仿真接口：先改 `CityFlowClient` 接口，再改实现类。
- 新增前端 REST 接口：先确定 DTO，再写 Controller 和 Service。
- 新增控制策略：实现 `TrafficSignalController`，不要改动 `SimulationController`。
- 新增数据库表：新增 Flyway 迁移脚本，不直接修改旧迁移。
- 新增智能体能力：放入 `agent`，通过 Service 调用业务查询接口。

## 8. 当前验收边界

当前阶段验收至少看：

1. Spring Boot 能编译启动。
2. 能从 Python CityFlow 获取静态路网。
3. 能创建仿真会话。
4. 能定时获取仿真帧。
5. 能通过 WebSocket 推送 `sim.frame`。
6. FixedTime、MaxPressure、Traffic-R 都能生成统一 `ControlDecision`。
7. `ControlDecision` 必须先经过 `SafetyLayerService`；安全通过后才能通过 Python `/cityflow/simulations/{sid}/actions` 下发到 CityFlow，被安全层拦截的决策只能进入审计和 Agent 查询。
8. 前端车辆和信号灯只能根据 `sim.frame` 渲染，不能根据模型响应伪造真实状态。
9. 前端能渲染路网、车辆动画、道路状态和信号灯。

应急绿波、智能体和正式权限体系仍不在当前验收边界内。
