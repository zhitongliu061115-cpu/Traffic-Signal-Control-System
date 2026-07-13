# SUMO 仿真迁移计划

## 1. 文档定位

本文档是项目从 CityFlow 迁移到 SUMO 的权威实施计划。迁移目标不是重写现有系统，而是在保持 Spring Boot、WebSocket、前端、数据库、Agent 和 Traffic-R 数据契约稳定的前提下，用本地 SUMO + TraCI 替换 Python 服务内部的 CityFlow Engine。

后续迁移必须遵守以下规则：

1. 严格按本文档的阶段顺序实施，不得跳过阶段门禁。
2. 在阶段 0 的 CityFlow 基线记录完成并验收前，不得开始 SUMO 业务替换。
3. 每个阶段开始前在 `docs/WORK_TRACKER.md` 更新任务状态、负责人、影响文件和验证方式。
4. 任何接口、DTO、WebSocket、数据库、部署或服务拓扑变化必须同步对应权威文档。
5. 如果实施中需要改变本文档的范围、顺序或验收标准，必须先更新本文档并说明原因，再修改代码。
6. CityFlow 在 SUMO 完成全部契约、算法和回归验收前保留为回滚实现。

## 2. 迁移目标与非目标

### 2.1 目标

- 本地安装并运行 SUMO，通过 TraCI 提供仿真会话、推进、状态读取和信号控制。
- SUMO 继续返回现有 `RoadnetResponse`、`SimFrameData`、生命周期响应、actions 响应和应急状态字段。
- Spring Boot 和前端在第一轮切换中不感知底层仿真器变化，只通过配置切换 Python 服务的引擎模式。
- Traffic-R 继续接收 `WT/WL/ST/SL/ET/EL/NT/NL` movement lane 状态，并继续输出 `ETWT/NTST/ELWL/NLSL`。
- 支持直接切换 SUMO 场景，并能够导入、裁剪和校验基于真实地图生成的路网。
- 地图道路、路口、车辆和信号状态来自同一份 SUMO 路网及其地理投影，不再依赖 Jinan 路网到上海道路的人工拓扑替换。
- Fixed-Time、MaxPressure、Traffic-R、fallback、安全层、应急调度、数据库审计和 Agent 查询在切换后继续工作。

### 2.2 非目标

- 第一阶段不删除 CityFlow 代码、历史数据或回滚配置。
- 第一阶段不同时重写前端页面、策略框架或数据库查询层。
- 不承诺 OSM 自动生成的车道连接和信号灯程序完全符合现实；所有真实地图场景必须经过 NETEDIT 人工校验。
- 不允许将不规则路口强行交给只支持标准四进口四相位的 Traffic-R。

## 3. 固定迁移边界

迁移期间调用链保持：

```text
Vue 前端
  -> Spring Boot REST / WebSocket
  -> Python Simulation Service
       -> mock / CityFlow / SUMO

Spring Boot
  -> Traffic-R /predict-batch
```

第一轮切换保留现有 Python HTTP 路径作为兼容协议：

```text
GET  /health
GET  /cityflow/scenes/{sceneId}/roadnet
POST /cityflow/simulations
GET  /cityflow/simulations/{sid}/frame
POST /cityflow/simulations/{sid}/start
POST /cityflow/simulations/{sid}/pause
POST /cityflow/simulations/{sid}/stop
POST /cityflow/simulations/{sid}/actions
POST /cityflow/simulations/{sid}/dispatch
```

当 `SIM_ENGINE_MODE=sumo` 时，上述路径内部委托给 SUMO Adapter。这样可以先直接替换 Python 仿真后端，而不同时修改 Spring Boot 和前端。通用 `/simulations/**` 路径及 Java 包重命名只能在兼容切换稳定后另立任务处理。

## 4. 迁移阶段与强制门禁

## 阶段 0：冻结并记录 CityFlow 当前契约

### 目标

完整记录 CityFlow 当前实际传递的数据、生成逻辑、HTTP 协议、Spring Boot 解析与上传逻辑、WebSocket 推送、前端消费、数据库持久化和 Traffic-R 输入构造。该阶段只读现有行为并建立基线，不修改业务语义。

### 必须记录的静态路网数据

| 对象 | 当前字段 |
|---|---|
| `RoadnetResponse` | `sceneId`、`intersections`、`roads`、`roadLinks`、`phases` |
| `IntersectionDto` | `id`、`x`、`y`、`virtual` |
| `RoadDto` | `id`、`from`、`to`、`points`、`laneCount` |
| `RoadLinkDto` | `intersectionId`、`index`、`fromRoadId`、`toRoadId`、`type` |
| `PhaseDto` | `intersectionId`、`phaseIndex`、`phaseCode`、`roadLinkIndexes` |

### 必须记录的实时帧数据

| 对象 | 当前字段 |
|---|---|
| `SimFrameData` | `simTime`、`status`、`vehicles`、`roads`、`laneStates`、`intersections`、`signals`、`metrics`、`evEvents`、`evStatus` |
| `VehicleStateDto` | `id`、`roadId`、`lane`、`x`、`y`、`angle`、`speed` |
| `RoadStateDto` | `id`、`vehicleCount`、`queueCount`、`avgSpeed`、`level` |
| `IntersectionLaneStateDto` | `lanes`，固定包含 `WT/WL/ST/SL/ET/EL/NT/NL` |
| `LaneMovementStateDto` | `queue_len`、`avg_wait_time`、`cells[4]` |
| `IntersectionStateDto` | `id`、`queueCount`、`avgWait`、`level` |
| `SignalStateDto` | `intersectionId`、`phaseIndex`、`phaseCode` |
| `SimulationMetricsDto` | `vehicleCount`、`activeVehicleCount`、`scheduledDepartureCount`、`queueCount`、`avgSpeed`、`avgWait`、`throughput` |

### 必须记录的控制与生命周期数据

- 创建、启动、暂停、停止和自然结束的请求、响应及状态变化。
- `ApplyControlActionsRequest`、`ControlDecision` 和 `ApplyControlActionsResponse` 的完整字段。
- `phaseIndex`、`phaseCode`、CityFlow phase id 和真实灯色之间的转换规则。
- actions 异步下发、Safety Layer、fallback、WebSocket `control.decision` 和后续 `sim.frame.signals` 的时序。
- 应急车辆 dispatch、路径、EV override、`evEvents` 和 `evStatus` 的生成与释放逻辑。

### 必须记录的端到端链路

```text
CityFlow Engine
  -> sim-python/app/engine.py
  -> sim-python/app/cityflow_adapter.py
  -> Python HTTP JSON
  -> HttpCityFlowClient
  -> Java DTO
  -> SimulationService
  -> StrategyDispatchService / RuntimePersistenceService
  -> WsMessage<SimFrameData>
  -> Pinia traffic store
  -> 地图、车辆、信号、指标和 Agent
```

### 基线交付物

- `docs/CITYFLOW_CONTRACT_BASELINE.md`：字段语义、单位、取值范围、生成位置和消费者。
- `sim-python/tests/fixtures/cityflow-contract/`：health、roadnet、create、frame、actions、lifecycle、dispatch 的脱敏 JSON 样本。
- CityFlow 契约测试：固定样本能够反序列化为当前 Java DTO 和前端 TypeScript 类型。
- Traffic-R golden fixture：固定 `laneStates` 生成的 `/predict-batch` 请求与 prompt 摘要。
- 指标口径表：排队阈值、等待时间、cells 划分、throughput、active、scheduled 的定义。

### 阶段门禁

只有上述交付物全部完成、样本可重复生成、字段无未解释项，才能进入阶段 1。

## 阶段 1：配置本地 SUMO 基础环境

### 目标

在不接入现有业务的情况下，完成本地 SUMO、SUMO GUI、NETEDIT、TraCI 和 Python 环境验证。

### 工作项

- 固定 SUMO 版本、Python 版本、TraCI/sumolib 版本和安装路径。
- 增加 `SUMO_HOME`、`SUMO_BINARY`、`SUMO_GUI_BINARY`、`SIM_ENGINE_MODE=sumo` 等环境变量。
- 建立最小场景：一个标准四进口路口、双向道路、三车道、四个 Traffic-R 业务相位。
- 验证 `sumo` 和 `sumo-gui` 均能加载 `.sumocfg`。
- 验证 Python 能启动独立 SUMO 进程、建立 TraCI 连接、推进、暂停并释放。
- 每个 session 使用独立 SUMO 进程和 TraCI connection，禁止共享全局连接造成会话串扰。

### 阶段门禁

本地最小场景连续运行、暂停、恢复、结束和异常清理均通过，才进入阶段 2。

## 阶段 2：实现 SUMO 静态路网适配

### 目标

将 SUMO `.net.xml` 转换为与当前 `RoadnetResponse` 完全兼容的结构。

### 映射规则

- SUMO junction -> `IntersectionDto`。
- SUMO 普通 edge -> `RoadDto`，排除 `:` 开头的 internal edge。
- SUMO connection 按 `from edge + to edge + movement` 聚合为 `RoadLinkDto`。
- SUMO TLS controlled links 和 program phases -> `PhaseDto`。
- roadLink `index` 必须在同一场景内稳定，不能因 XML 读取顺序或进程重启变化。
- `phaseCode` 不从 SUMO phase index 猜测，必须来自场景级 `phase-map.json`。

### 兼容扩展

为了真实地图渲染，可以在保持旧字段不变的情况下增加可选地理字段：`coordinateSystem`、路口 `lng/lat`、道路 `geoPoints`。SUMO/OSM 默认 WGS84；高德地图渲染前统一转换为 GCJ-02。

### 阶段门禁

同一 SUMO 场景重复解析得到稳定 ID、roadLink index 和 phase mapping；前端能够只使用返回 roadnet 绘制静态路网。

## 阶段 3：实现 SUMO 会话与实时帧适配

### 目标

SUMO Adapter 输出与阶段 0 CityFlow 基线结构、类型、单位和语义一致的 `SimFrameData`。

### 字段来源

| 现有字段 | SUMO/TraCI 来源或计算方式 |
|---|---|
| `simTime` | `simulation.getTime()` |
| 车辆 `id` | SUMO vehicle ID |
| `roadId` | 当前普通 edge；处于 internal edge 时映射到最近的外部 edge |
| `lane` | lane index |
| `x/y` | `vehicle.getPosition()` |
| `angle` | SUMO 角度转换为项目统一角度定义 |
| `speed` | `vehicle.getSpeed()`，单位 m/s |
| 道路车辆数 | edge 上活动车辆数量 |
| 道路排队数 | 使用阶段 0 固定的速度阈值计算 |
| `avgWait` | 使用阶段 0 固定的等待时间定义 |
| `throughput` | 累计 arrived 数量 |
| `scheduledDepartureCount` | 累计应出发/已装载口径按阶段 0 定义实现 |

### 强制要求

- 不允许为了通过 DTO 测试而填充无业务含义的固定值。
- SUMO internal edge、车辆离开、teleport、路由失败和碰撞必须有确定处理规则。
- frame 构建失败不得返回伪造成功帧。
- `status=finished` 的条件必须同时考虑未来待出发车辆和当前活动车辆。

### 阶段门禁

SUMO frame 通过 Python 契约测试、Java 反序列化测试、WebSocket 测试和前端类型检查，才进入阶段 4。

## 阶段 4：对齐 Traffic-R movement、观测和相位

### 目标

SUMO 生成的 Traffic-R 输入与模型训练/官方评测语义一致，Traffic-R 输出能够安全映射到 SUMO TLS。

### movement 对齐

每个可由 Traffic-R 控制的路口必须提供 `movement-map.json`：

```text
SUMO incoming lane
  -> 进口方向 W/S/E/N
  -> movement T/L
  -> WT/WL/ST/SL/ET/EL/NT/NL
```

- 进口方向依据道路进入路口时的几何方向计算，并由场景校验工具输出供人工确认。
- 直行、左转依据 SUMO connection 分类；右转单独保留，但不能错误写入左转 movement。
- `queue_len`、`avg_wait_time` 和 `cells[4]` 必须严格复刻 Traffic-R 训练代码口径。
- 未完成八 movement 映射或存在方向歧义的路口不得标记为 `trafficRCompatible`。

### 相位对齐

每个 Traffic-R 路口必须提供 `phase-map.json`：

```text
ETWT -> SUMO 稳定绿灯 phase
NTST -> SUMO 稳定绿灯 phase
ELWL -> SUMO 稳定绿灯 phase
NLSL -> SUMO 稳定绿灯 phase
```

- Spring Boot 使用业务 `phaseCode`，不能继续依赖全局 `JinanPhaseMapper`。
- SUMO Adapter 负责业务 phaseCode 到当前路口 TLS phase 的转换。
- 相位切换必须经过黄灯和全红，Traffic-R 在过渡阶段不得重复触发新决策。
- 不规则、T 型、五岔、movement 不完整或不支持四业务相位的路口使用 MaxPressure/Fixed-Time。

### golden 验收

- 相同标准化交通状态经 CityFlow 基线适配和 SUMO 适配后产生相同 `laneStates` JSON。
- 相同 `laneStates` 产生相同 Traffic-R 请求结构和 prompt 表格。
- Traffic-R 输出四种 phaseCode 均能映射到正确 SUMO TLS 绿灯组合。
- 先运行 shadow mode，只记录模型建议；通过后才能允许真实下发。

## 阶段 5：接入 actions、安全层、fallback 和应急能力

### 目标

完整恢复现有策略与应急业务，而不仅是展示 SUMO 车辆动画。

### 工作项

- actions 返回继续兼容 `intersectionId`、`phaseIndex`、`phaseCode`、`status`。
- 增加通用 `enginePhaseId` 时保留旧 `cityflowPhaseId` 兼容字段，数据库和调用方迁移完成后再废弃旧字段。
- Fixed-Time、MaxPressure、Traffic-R 全部经过现有 `StrategyDispatchService`、Safety Layer 和 fallback。
- SUMO phase 映射非法、TLS 不存在、连接不完整时必须拒绝动作并记录结构化原因。
- 应急车辆通过 SUMO route 与 vehicle API 创建，继续输出当前 `evEvents` 和 `evStatus`。
- 应急信号优先不得绕过 Spring Boot 仲裁和安全层。

### 阶段门禁

三种策略、fallback、相位过渡、应急调度和会话释放均通过自动化与人工联调，才进入阶段 6。

## 阶段 6：切换 Spring Boot 默认仿真后端

### 目标

通过配置将现有 Spring Boot 调用的 Python 服务切换到 SUMO 实现，不改变前端访问入口和主要 DTO。

### 切换步骤

1. 部署或启动本地 Python SUMO 服务。
2. 验证 `/health.engineMode=sumo` 和场景列表。
3. 运行完整 roadnet、create、start、frame、actions、pause、stop 契约测试。
4. 将后端仿真服务地址指向 SUMO 服务。
5. Traffic-R 先以 shadow mode 联调，再按场景启用真实控制。
6. 保留 CityFlow 服务地址和启动方式作为回滚配置。

### 回滚条件

出现契约字段缺失、laneStates 不一致、相位错误、车辆状态无法渲染、Traffic-R 输出无法映射、会话泄漏或指标异常时，立即切回 CityFlow，不允许使用 mock 数据掩盖失败。

## 阶段 7：真实地图路网与场景切换

### 目标

允许直接更换 SUMO 场景，并以真实地图路网为仿真和可视化的共同数据源。

### 场景生成流程

```text
OSM 小范围地图
  -> osmWebWizard / netconvert
  -> NETEDIT 裁剪目标路口和连接道路
  -> 校验 lanes / connections / traffic lights
  -> 生成 routes、additional 和 sumocfg
  -> 生成 movement-map.json 和 phase-map.json
  -> 场景验证
  -> 注册到 scenes.json
```

### 强制校验

- 所有目标进口车道均存在合法出口 connection。
- 每个 roadLink 至少属于一个合法信号相位，或明确标记为非信号控制 movement。
- 路网存在有效入口、出口和可达 OD 路径。
- 地图几何与 SUMO 路网使用同一地理投影来源。
- 高德地图使用 GCJ-02 时完成并验证坐标转换。
- Traffic-R 只控制通过标准四进口兼容检查的路口。

## 阶段 8：评测、默认切换与 CityFlow 退场

### 同源评测

在相同 SUMO 路网、交通需求、随机种子、仿真时长和决策周期下分别运行：

- Fixed-Time
- MaxPressure
- Traffic-R
- 后续 Hybrid

至少使用 5 个随机种子，比较平均等待时间、排队长度、平均速度、通行量、旅行时间、模型无效输出、fallback 次数和安全拒绝次数。

### CityFlow 退场条件

- SUMO 已连续完成全部契约、策略、应急、前端、数据库和 Agent 验收。
- 至少完成一个标准测试场景和一个真实地图场景。
- SUMO 部署、启动、排障和回滚文档完整。
- CityFlow 与 SUMO 的历史数据可以通过 `engineType` 区分。
- 团队确认不再需要 CityFlow 回归后，另立清理任务，禁止在迁移任务中直接删除。

## 5. 计划代码结构

```text
sim-python/app/
|-- engine.py                    通用 SimulationEngine 契约
|-- engine_factory.py            mock/cityflow/sumo 引擎选择
|-- cityflow_engine.py           现有 CityFlow 实现
|-- sumo_engine.py               SUMO 会话、进程和 TraCI
|-- sumo_roadnet_parser.py       net.xml -> RoadnetResponse
|-- sumo_observation.py          frame、laneStates、metrics
|-- movement_mapping.py          movement 方向和 lane 映射
|-- phase_mapping.py             业务 phaseCode 与引擎 phase
`-- server.py                    保持兼容 HTTP 协议
```

第一轮不强制重命名 `sim-python` 目录，避免在仿真切换时混入大范围路径重构。

## 6. 配置规划

```text
SIM_ENGINE_MODE=sumo
SUMO_HOME=<sumo-installation>
SUMO_BINARY=<sumo-binary>
SUMO_GUI_BINARY=<sumo-gui-binary>
SUMO_USE_GUI=false
SUMO_STEP_LENGTH_SECONDS=1.0
SUMO_QUEUE_SPEED_THRESHOLD_MPS=<training-aligned-value>
SUMO_MAX_SESSIONS=<local-limit>
SUMO_SESSION_TEMP_DIR=<ascii-path>
```

场景配置至少包含：`engineType`、`sumoConfigFile`、`networkFile`、`routeFile`、`phaseMapFile`、`movementMapFile`、`trafficRCompatible`、`coordinateSystem`。

## 7. 测试矩阵

| 测试层级 | 最低要求 |
|---|---|
| Python 单元测试 | SUMO roadnet、movement、phase、frame、metrics、生命周期 |
| Python 契约测试 | 与阶段 0 CityFlow fixture 字段和类型一致 |
| Java 测试 | DTO 反序列化、SimulationService、策略与 actions |
| Traffic-R 测试 | laneStates golden、prompt 对齐、四相位映射、fallback |
| 前端测试 | roadnet、车辆、信号、指标和断线状态 |
| 端到端测试 | create -> start -> frame -> decision -> actions -> frame -> stop |
| 资源测试 | 多 session、异常退出、端口释放、SUMO 子进程清理 |
| 真实地图测试 | 坐标、道路曲线、车辆位置、路口连接和 TLS 一致 |

## 8. 文档同步清单

实施过程中必须同步：

- `docs/WORK_TRACKER.md`
- `README.md`、`docs/项目简介.md`
- `docs/PROJECT_STRUCTURE.md`
- `docs/CFRP-1.0-前后端通信协议.md`
- `backend/docs/API_GUIDELINES.md`
- `backend/docs/CALL_CHAIN.md`
- `backend/docs/BACKEND_ARCHITECTURE.md`
- `backend/docs/DEPLOYMENT.md`
- `backend/docs/DATABASE_STRUCTURE.md`
- `backend/docs/TECHNICAL_DESIGN.md`
- `backend/docs/RISK_TODO.md`

## 9. 总体验收定义

SUMO 切换只有同时满足以下条件才算完成：

- SUMO 返回全部当前 CityFlow 契约数据，不使用无语义占位值补字段。
- Spring Boot、前端、WebSocket、数据库和 Agent 能使用 SUMO 数据正常工作。
- Traffic-R movement、cells、等待、排队和四相位均通过 golden 对齐。
- 实际执行信号以 SUMO 后续 frame 为准，模型建议不能直接伪造前端灯色。
- Fixed-Time、MaxPressure、Traffic-R 和 fallback 均通过同源实验。
- 至少一个真实地图场景可加载、运行、控制和正确渲染。
- 会话停止、自然结束、异常退出都能释放 SUMO 进程、TraCI 连接和临时文件。
- 接口、部署、数据库、架构和风险文档全部同步。

