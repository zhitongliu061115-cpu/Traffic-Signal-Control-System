# 技术设计说明

## 1. 项目定位

本项目是交通信号控制与 CityFlow 仿真可视化系统。短期目标是完成可运行的仿真演示系统，长期目标是支持 RL / Max-Pressure / FixedTime 等多种控制策略评估，并扩展应急车辆优先通行、智能体辅助调度和历史数据复盘。

## 2. 技术选型

| 子系统 | 技术 | 说明 |
|---|---|---|
| 前端 | Vue + TypeScript | 实时大屏、路网渲染、车辆动画 |
| 主后端 | Spring Boot 3 | REST、WebSocket、数据库、业务编排 |
| 仿真服务 | Python + CityFlow | 路网加载、CityFlow 后台推进、缓存快照和信号相位应用 |
| 云端策略服务 | Traffic-R / Traffic-R1 | AutoDL 上按 `/predict-batch` 输出 RL 相位决策 |
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
| `/cityflow/simulations/{sid}/frame` | GET | 返回 Python 后台推进后的最新缓存快照 |
| `/cityflow/simulations/{sid}/actions` | POST | 下发统一 `ControlDecision` 并设置 CityFlow 信号相位 |
| `/cityflow/simulations/{sid}/start` | POST | 启动 Python 后台推进 |
| `/cityflow/simulations/{sid}/pause` | POST | 暂停 Python 后台推进 |
| `/cityflow/simulations/{sid}/stop` | POST | 停止并销毁 Python 会话 |

除 `/health` 外，云端 Python CityFlow 的 `/cityflow/**` 接口需要 `X-CityFlow-Token` 认证；Spring Boot 还会发送 `X-CityFlow-Client` 做团队成员会话隔离。当前默认 CityFlow 地址为阿里云 `http://39.105.75.87:9000`，本地 WSL 启动脚本只作为备用开发方案。

### 5.3 Spring Boot 访问云端 Traffic-R

Traffic-R 模型服务作为独立外部策略服务接入，只由 Spring Boot 调用，前端和 Python CityFlow 均不直接访问 Traffic-R。

```text
Spring Boot StrategyDispatchService
  -> CloudTrafficRClient
  -> Traffic-R /predict-batch
  -> List<ControlDecision>
  -> Python CityFlow /actions
```

本地联调时通过 SSH 隧道访问 AutoDL 云端服务：

```yaml
traffic-r:
  base-url: http://127.0.0.1:16008
  predict-path: /predict
  batch-predict-path: /predict-batch
  decision-interval-sec: 10
  timeout-sec: 30
  fallback-controller: max-pressure
```

后续如果 Spring Boot、Python CityFlow、Traffic-R 全部部署在同一台 Linux 服务器上，Spring Boot 应直接访问服务器内部 Traffic-R 端口：

```yaml
traffic-r:
  base-url: http://127.0.0.1:6008
```

因此 `16008` 只代表本地 SSH 隧道端口，不应写入同机部署环境；同机部署环境应通过 `TRAFFIC_R_BASE_URL=http://127.0.0.1:6008` 覆盖。

当前 Traffic-R 调度链路采用与 LLMTSCS 更一致的批量机制：Spring Boot 每个决策周期将当前帧的全部 `signals`、`laneStates` 和 `metrics` 发送到云端 `/predict-batch`，云端为每个路口构造单路口 prompt，并在同一次 batch generate 中返回所有路口的决策列表。`laneStates` 使用 `WT/WL/ST/SL/ET/EL/NT/NL` movement lane、`queue_len`、`avg_wait_time` 和 4-cell 结构，避免用 road-level 汇总值替代官方输入。

当前云端 Traffic-R 批量推理测试平均约 7 秒，不能跟随高频仿真帧轮询调用。策略调度按 `decision-interval-sec=10` 触发；推理未返回期间继续推送 `sim.frame`，上一轮推理完成后再下发整组路口决策。Spring Boot 轮询 CityFlow frame 可保持更高频率，例如 `cityflow.frame-poll-interval-ms=100`，用于保证前端动画流畅。

Traffic-R 响应必须满足 `parsedFromModel=true` 且 `rawOutput` 非空；否则视为无效决策。若连续 3 次无效、超时或请求失败，`TrafficRAsyncDecisionService` 启用 `MaxPressureController` 生成同结构 `ControlDecision` 作为 fallback。fallback 期间仍继续请求 Traffic-R，连续 3 次有效后恢复应用 RL 决策。

真实 CityFlow 服务默认由 Spring Boot 策略接管信号灯相位，不再由 Python 按固定 10 秒周期自动换灯。仅在需要脱离后端策略做纯 CityFlow 周期演示时，才通过 `SIM_AUTO_SIGNAL_CYCLE=true` 恢复 Python 侧自动相位轮换。

前端信号灯不得由模型响应或 `control.decision` 直接驱动；真实显示只能来自 Python CityFlow frame 中的 `signals`。这样可保证可视化看到的是 CityFlow 已应用后的真实相位，而不是尚未下发或下发失败的模型意图。

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
  -> fixed-time/max-pressure: TrafficSignalController.decide(ControlRequest)
  -> traffic-r: TrafficRAsyncDecisionService -> TrafficRBatchController -> /predict-batch
  -> ControlDecision 或 List<ControlDecision>
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
| RL 效果不稳定 | 连续 3 次无效后自动切 Max-Pressure，连续 3 次有效后恢复 RL；FixedTime 保留为 baseline |
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

## Frame Cadence Note

`cityflow.frame-poll-interval-ms` 是 Spring Boot 帧轮询调度器的固定延迟，不是前端真实帧率承诺。旧版 Python CityFlow 服务采用请求驱动模式：每次 `GET /cityflow/simulations/{sid}/frame` 都会推进仿真并组装车辆、道路、lane-level 状态、信号灯和指标，导致高倍速和高车流下前端取帧被 CityFlow step 阻塞。因此 100ms 配置只能表示“上一轮完成后尽快再请求”，实际 WebSocket 帧间隔需要以 `backend/logs/simulation-frame-timing.jsonl` 为准。

策略动作下发与前端渲染解耦：Spring Boot 只在决策周期到达或 Traffic-R 异步结果就绪时调用 Python `/actions`；下发后不再立即额外拉取一帧，新的信号状态由后续真实 CityFlow frame 返回。前端信号灯和车辆动画必须只依据 `sim.frame.data.signals` 与 `sim.frame.data.vehicles` 渲染，不能直接使用 `control.decision` 伪造视觉状态。

当前真实 CityFlow 已改为快照缓存模式：Spring Boot 调用 `/cityflow/simulations/{sid}/start` 后，Python 为该 session 启动后台线程，按 `SIM_REALTIME_TICK_SECONDS` 间隔连续执行 CityFlow step 并缓存最新 frame。`GET /cityflow/simulations/{sid}/frame` 不再同步推进仿真，只返回最新缓存快照，因此前端渲染读取不会直接阻塞在 CityFlow step 和 lane-level 统计上。`pause` / `stop` 会同步转发到 Python，暂停或停止后台推进。

`/cityflow/simulations/{sid}/actions` 也从 Spring 帧循环中解耦：`StrategyDispatchService` 将 actions 放入后台执行器异步 POST 到 Python，帧循环继续读取缓存并推送 WebSocket。同一 session、同一路口、同一相位在上一次下发完成前会被去重，避免缓存快照尚未更新时重复提交相同控制动作。Spring Boot 推送的 `control.decision` 表示动作已生成并提交，不能作为真实信号灯状态；前端信号灯只能以随后 `sim.frame.data.signals` 中 CityFlow 已应用的相位为准。

当前联调模式限制为单活跃仿真 session：Spring Boot 创建新仿真前会停止并清空 `SimulationSessionRegistry` 中已有 session；Python CityFlow 创建新 session 前也会停止后台 worker 并清空旧 `RealCityFlowEngine.sessions`。这样可以避免多个高车流 CityFlow Engine 同时后台 step，导致新仿真帧率被旧 session 拖慢。后续如需多用户并发实验，需要改为用户级隔离、资源配额和显式 session 回收机制。

云端多人开发时，Python CityFlow 按 `X-CityFlow-Client` 进行会话归属校验；同一 client 新建会话会清理该 client 旧会话。Spring Boot 本地仍默认 `CITYFLOW_CLIENT_ID=hcj`，多人同时联调时应为每个成员配置不同 client id，避免互相清理会话。

为了避免高倍速下一次后台循环推进过多 step 导致快照长时间不更新，真实 CityFlow 后台 worker 每次只执行一个 `engine.next_step()` 并生成缓存快照；`speed` 用于缩短后台循环间隔，而不是在单次快照中连续推进大量 step。前端等待状态以 WebSocket 消息接收时间为准，重复缓存帧不会被误判为断流，但车辆目标位置只在 `simTime` 变化时更新。

临时 Three.js 前端的道路显示是可视化抽象，不直接改变 CityFlow roadnet：每条有向 road 显示为 3 条同向车道，反向 road 根据行驶方向偏移到道路另一侧，中间留隔离带，形成 3 来向 + 3 去向的六车道视觉效果。信号灯按进口方向显示直行箭头灯和左转箭头灯，红绿状态仍然只来自 `sim.frame.data.signals` 对应 phase 的 roadLink 类型。
