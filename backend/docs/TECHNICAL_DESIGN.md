# 技术设计说明

## 1. 项目定位

本项目是交通信号控制与 CityFlow 仿真可视化系统。短期目标是完成可运行的仿真演示系统，长期目标是支持 RL / Max-Pressure / FixedTime 等多种控制策略评估，并扩展应急车辆优先通行、智能体辅助调度和历史数据复盘。

## 2. 技术选型

| 子系统 | 技术 | 说明 |
|---|---|---|
| 前端 | Vue + TypeScript | 实时大屏、路网渲染、车辆动画 |
| 主后端 | Spring Boot 3 | REST、WebSocket、数据库、业务编排 |
| 仿真算法服务 | Python + CityFlow | 路网加载、车辆仿真、RL / Max-Pressure 计算 |
| 数据库 | PostgreSQL | 保存场景、路网、会话、指标和历史记录 |
| 本地验证 | H2 | Spring Boot 本地编译和轻量启动验证 |
| 数据库迁移 | Flyway | 版本化管理数据库结构 |

## 3. 系统设计原则

1. **前端只连主后端**：前端不得直接连接 Python CityFlow 服务。
2. **静态和动态分离**：路网结构通过 REST 获取，实时帧通过 WebSocket 推送。
3. **算法和业务解耦**：RL、Max-Pressure、FixedTime 都通过策略接口接入。
4. **高频数据不全量入库**：车辆逐帧位置用于实时展示，不逐帧保存。
5. **数据库必须可追溯**：场景、路网、相位、会话、指标快照和后续控制记录必须保存。
6. **安全边界清晰**：智能体和算法可以生成建议，但控制指令必须经过后端校验和人工确认。

## 4. 当前数据流

阶段 2 的强制调用链详见 `CALL_CHAIN.md`。如果本文档和调用链文档存在差异，以 `CALL_CHAIN.md` 为当前阶段开发依据。

### 4.1 静态路网

```text
CityFlow roadnet_3_4.json
  -> Python CityFlow 服务解析
  -> Spring Boot /api/v1/scenes/{sceneId}/roadnet
  -> 前端绘制路口、道路、车道和相位
```

### 4.2 实时仿真帧

```text
CityFlow Engine step
  -> Python 计算车辆位置、道路状态、路口状态、信号相位和指标
  -> Spring Boot 定时轮询 frame
  -> Spring Boot StrategyDispatchService 生成统一 ControlDecision
  -> Spring Boot CityFlowClient 下发 actions 到 Python CityFlow
  -> Spring Boot 组装 CFRP sim.frame
  -> WebSocket 推送前端
  -> 前端根据车辆 id 做动画插值
```

## 5. 接口分工

### 5.1 前端访问 Spring Boot

| 接口 | 方法 | 作用 |
|---|---|---|
| `/api/v1/scenes/{sceneId}/roadnet` | GET | 获取静态路网 |
| `/api/v1/simulations` | POST | 创建仿真会话 |
| `/api/v1/simulations/{sid}/start` | POST | 启动仿真 |
| `/api/v1/simulations/{sid}/pause` | POST | 暂停仿真 |
| `/api/v1/simulations/{sid}/stop` | POST | 停止仿真 |
| `/ws/v1/simulations/{sid}` | WebSocket | 接收实时仿真帧 |

### 5.2 Spring Boot 访问 Python CityFlow

| 接口 | 方法 | 作用 |
|---|---|---|
| `/cityflow/scenes/{sceneId}/roadnet` | GET | 获取 Python 解析后的路网 |
| `/cityflow/simulations` | POST | 创建 CityFlow 仿真会话 |
| `/cityflow/simulations/{sid}/frame` | GET | 推进一步并返回当前帧 |
| `/cityflow/simulations/{sid}/actions` | POST | 下发统一 `ControlDecision` 并设置 CityFlow 信号相位 |

## 6. 数据库设计原则

当前初始化表：

本地连接已经存在的 PostgreSQL `traffic_signal` 数据库时，使用 `postgres` profile，连接方式见 `DATABASE_CONNECTION.md`。该 profile 暂时关闭 Flyway 和 Hibernate 自动建表，避免改动数据处理侧已经建好的表结构。

| 表名 | 作用 |
|---|---|
| `cityflow_scene` | 保存仿真场景 |
| `cityflow_intersection` | 保存路口 |
| `cityflow_road` | 保存道路 |
| `cityflow_road_link` | 保存路口内部转向连接 |
| `cityflow_phase` | 保存信号相位 |
| `simulation_session` | 保存仿真会话 |
| `simulation_metric_snapshot` | 保存指标快照 |

车辆位置数据处理原则：

- WebSocket：实时推送当前帧车辆位置。
- 数据库：只保存关键指标快照，不逐帧保存所有车辆。
- 后续如需复盘动画，可增加低频采样表，但必须设置采样间隔和保留策略。

## 7. 控制策略设计

控制策略统一通过 `TrafficSignalController` 接口扩展。

```text
TrafficSignalController
|-- FixedTimeController
|-- MaxPressureController
`-- RlController
```

当前统一入口：

```text
SimulationService
  -> StrategyDispatchService
  -> TrafficSignalControllerRegistry
  -> TrafficSignalController.decide(ControlRequest)
  -> ControlDecision
  -> CityFlowClient.applyControlActions
  -> Python set_tl_phase
```

创建仿真会话时可传入 `controllerType`，默认 `fixed-time`。当前允许 `fixed-time`、`max-pressure`、`traffic-r`，其中 `rl` 会作为兼容别名归一化为 `traffic-r`。

`ControlDecision` 是所有策略的统一输出结构，必须至少包含：

| 字段 | 含义 |
|---|---|
| `intersectionId` | 决策作用的路口 |
| `controllerType` | 产生决策的策略类型 |
| `phaseIndex` | 本项目协议中的相位编号，从 1 开始 |
| `phaseCode` | 业务相位编码，如 `ETWT`、`NTST` |
| `durationSec` | 建议保持时长 |
| `confidence` | 模型或策略置信度，无置信度时可为 0 |
| `reason` | 决策说明 |
| `metadata` | fallback 状态、模型来源、调试信息 |

当前 Spring Boot 已能通过统一入口生成策略决策，并通过 Python `/cityflow/simulations/{sid}/actions` 下发 `ControlDecision`。由于调度链路是先拉取当前 frame 再下发控制动作，前端通常会在下一帧看到新的信号相位效果。

后续如果 RL 效果不稳定，可以将 Max-Pressure 作为工程 fallback，同时保留 RL / Traffic-R 作为主实验策略。

## 8. 风险和约束

| 风险 | 处理方式 |
|---|---|
| CityFlow Python API 字段不足 | Python 服务自行根据 roadnet 和车辆状态补齐渲染坐标 |
| WebSocket 推送频率过高 | Spring Boot 配置 `cityflow.frame-poll-interval-ms` |
| 数据库写入压力过大 | 高频车辆帧不入库，只保存指标快照 |
| RL 效果不稳定 | 策略模块保留 RL 接口，工程演示可切 Max-Pressure 或 FixedTime |
| 前端渲染卡顿 | 前端按车辆 id 复用对象，并做插值动画 |

## 9. 当前开发顺序

1. 完成 Spring Boot 后端架构。
2. 完成 Python CityFlow 最小 HTTP 服务。
3. 打通 Spring Boot 获取 roadnet。
4. 前端渲染静态路网。
5. Spring Boot 创建仿真会话。
6. Spring Boot 定时获取 frame。
7. 前端 WebSocket 接收并渲染车辆动画。
8. 保存仿真会话和指标快照。
