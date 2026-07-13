# CityFlow 数据契约与处理链路基线

## 1. 文档用途

本文档冻结 2026-07-13 当前项目中 CityFlow 仿真服务对外提供的数据契约、指标口径、控制语义以及 Spring Boot、WebSocket、数据库、Traffic-R 和前端的消费链路。SUMO 迁移必须以本基线为兼容目标，任何有意改变语义的字段都必须先经过单独评审，不能在仿真器替换过程中静默改变。

权威迁移顺序见 `docs/SUMO_MIGRATION_PLAN.md`。本基线对应其中阶段 0。

## 2. 基线来源与验证边界

- 静态路网结构来自 `sim-python/data/jinan_3x4/roadnet_3_4.json`，经 `RoadnetParser` 实际解析。
- Python HTTP、生命周期、actions 和 mock frame 样本由当前 `CityFlowAdapter` 本地执行生成。
- 真实 CityFlow 动态字段和指标口径依据 `RealCityFlowEngine` 当前实现冻结。
- Spring Boot 解析链路依据 `CityFlowClient`、`HttpCityFlowClient`、Java DTO 和 `SimulationService` 当前实现冻结。
- Traffic-R golden 依据 `traffic_r_service.py` 的 `LANE_ORDER`、`FOUR_PHASE_LIST` 和 `state_to_official_commonsense_table()` 冻结。
- 当前环境未连接云端真实 CityFlow Engine，因此 fixtures 中的动态数值是契约样本，不是性能评测数据；切换 SUMO 前仍需补一次真实 CityFlow frame 采样核验。

固定样本目录：

```text
sim-python/tests/fixtures/cityflow-contract/
```

## 3. 服务边界与 HTTP 契约

Python 服务只对 Spring Boot 开放。前端不得直接访问 Python 服务。

| 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|
| GET | `/health` | 无 | 服务状态、版本、引擎模式、场景和会话配置 |
| GET | `/cityflow/scenes/{sceneId}/roadnet` | Path `sceneId` | `RoadnetResponse` 裸 JSON |
| POST | `/cityflow/simulations` | `sceneId`、`speed`、`warmupSeconds` | `sid`、`sceneId`、`status`、`engineMode` |
| GET | `/cityflow/simulations/{sid}/frame` | Path `sid` | 最新缓存 frame 裸 JSON |
| POST | `/cityflow/simulations/{sid}/start` | `{}` | `sid`、`status=running` |
| POST | `/cityflow/simulations/{sid}/pause` | `{}` | `sid`、`status=paused` |
| POST | `/cityflow/simulations/{sid}/stop` | `{}` | `sid`、`status=stopped` |
| POST | `/cityflow/simulations/{sid}/actions` | source、simTime、decisions | `sid`、applied[] |
| POST | `/cityflow/simulations/{sid}/dispatch` | EV 参数 | EV 路线与任务结果 |

配置了 API token 时，`/cityflow/**` 必须携带 `X-CityFlow-Token`。`X-CityFlow-Client` 当前只作兼容字段，不负责 session 所有权隔离。

错误响应固定为：

```json
{
  "success": false,
  "code": "SESSION_NOT_FOUND",
  "message": "simulation session not found: run_xxx",
  "retryable": false
}
```

## 4. 健康检查契约

`GET /health` 当前字段：

| 字段 | 类型 | 语义 |
|---|---|---|
| `status` | string | 当前固定为 `UP` |
| `service` | string | 当前为 `sim-python` |
| `version` | string | Python 服务版本 |
| `engineMode` | string | `mock` 或 `cityflow`，SUMO 后续增加 `sumo` |
| `sceneIds` | string[] | 已注册场景 ID |
| `activeSessions` | integer | 当前活动 session 数 |
| `maxActiveSessions` | integer | 软配置，0 表示不按数量拒绝 |
| `maxSpeed` | number | 允许的最大仿真倍率 |
| `autoSignalCycle` | boolean | 是否启用 Python 自动相位循环 |
| `sessionIdleTtlSeconds` | number | 未运行 session 空闲回收阈值 |
| `sessionAbandonedTtlSeconds` | number | running 但无访问的回收阈值 |
| `sessionMaxLifetimeSeconds` | number | session 最大生命周期 |
| `sessionCleanupIntervalSeconds` | number | 后台清理间隔 |
| `sessionDrainTimeoutSeconds` | number | flow 结束后的最大排空时间 |

## 5. 静态路网契约

### 5.1 RoadnetResponse

```text
sceneId: string
intersections: IntersectionDto[]
roads: RoadDto[]
roadLinks: RoadLinkDto[]
phases: PhaseDto[]
```

### 5.2 IntersectionDto

| 字段 | 类型 | 单位/语义 | 当前生产位置 | 主要消费者 |
|---|---|---|---|---|
| `id` | string | CityFlow 原始 intersection ID | `RoadnetParser._build_response` | Spring、数据库、策略、前端、Agent |
| `x/y` | number | CityFlow 平面坐标 | `intersection.point` | 前端路网与车辆位置映射 |
| `virtual` | boolean | 是否为边界虚拟路口 | `intersection.virtual` | 路网解析、策略过滤 |

### 5.3 RoadDto

| 字段 | 类型 | 单位/语义 |
|---|---|---|
| `id` | string | CityFlow 原始 road ID |
| `from/to` | string | 起终点 intersection ID |
| `points` | `{x,y}[]` | 道路中心折线，CityFlow 平面坐标 |
| `laneCount` | integer | `lanes` 数组长度 |

### 5.4 RoadLinkDto

| 字段 | 类型 | 语义 |
|---|---|---|
| `intersectionId` | string | roadLink 所在路口 |
| `index` | integer | roadLink 在该路口原始数组中的 0-based 索引 |
| `fromRoadId/toRoadId` | string | 进入、离开 road ID |
| `type` | string | `go_straight`、`turn_left`、`turn_right` 等 |

`index` 会被 phase 的 `roadLinkIndexes` 引用，SUMO 适配后必须保持同一场景内稳定。

### 5.5 PhaseDto 与双层相位编号

| 层级 | ETWT | NTST | ELWL | NLSL |
|---|---:|---:|---:|---:|
| Traffic-R business index | 1 | 2 | 3 | 4 |
| 项目/CityFlow `phaseIndex` | 2 | 3 | 4 | 5 |
| CityFlow Engine phase id | 1 | 2 | 3 | 4 |

当前 `RoadnetParser` 将 CityFlow `lightphases` 数组按 1-based 生成 `phaseIndex`。只有 2 至 5 映射业务 `phaseCode`；其他 phase 的 `phaseCode` 为 null。`JinanPhaseMapper` 负责 Traffic-R business index 与 CityFlow phaseIndex 的转换。

## 6. 创建和生命周期契约

### 6.1 创建请求

```json
{
  "sceneId": "jinan_3x4",
  "speed": 1.0,
  "warmupSeconds": 0.0
}
```

- `speed` 必须大于 0 且不超过服务配置上限。
- `warmupSeconds` 为非负仿真秒。
- 真实 CityFlow config 固定 `interval=0.2`、`seed=0`、`rlTrafficLight=true`、`laneChange=false`。
- warmup step 数为 `round(warmupSeconds / 0.2)`。

### 6.2 状态语义

| 状态 | 出现位置 | 含义 |
|---|---|---|
| `created` | 创建响应 | 引擎与 session 已创建，尚未运行 |
| `running` | start 响应/frame | 后台 worker 推进并缓存最新帧 |
| `paused` | pause 响应 | worker 保留但不推进 |
| `stopped` | stop 响应 | Python session 已释放 |
| `finished` | 最后一帧 | 自然结束，Python 已释放 session |

真实模式 `/frame` 返回缓存快照，不负责同步推进。后台 worker 的实际睡眠间隔为 `max(minTick, realtimeTick / speed)`；每次推进一个 CityFlow step，即 0.2 仿真秒。

自然结束条件：`simTime >= flowEndTime` 且 `activeVehicleCount == 0`，或者达到 `flowEndTime + drainTimeout`。

## 7. SimFrameData 实时帧契约

Python 裸 frame 还会附带 `sid`、`sceneId`、`seq` 和 `engineMode`。Spring Boot 的 `SimFrameData` 只解析下表业务字段，WebSocket 外层重新生成 sid、seq 和 sentAt。

| 字段 | 类型 | 语义 |
|---|---|---|
| `simTime` | number | 当前仿真秒 |
| `status` | string | `running` 或 `finished` |
| `vehicles` | array | 可视车辆状态，受 visible limit 限制 |
| `roads` | array | 全部 road 聚合状态 |
| `laneStates` | object | 路口八 movement 状态，Traffic-R 主要输入 |
| `intersections` | array | 路口排队/等待/拥堵级别 |
| `signals` | array | 当前记录的业务信号相位 |
| `metrics` | object | 全局聚合指标 |
| `evEvents` | array | 当前 step 新产生的 EV 事件 |
| `evStatus` | array | 当前 EV 任务状态 |

### 7.1 VehicleStateDto

| 字段 | 单位/语义 | 当前计算方式 |
|---|---|---|
| `id` | CityFlow vehicle ID | `engine.get_vehicles()` |
| `roadId` | 当前 lane 拆出的 road ID | 遍历 `get_lane_vehicles()` 建立 vehicle -> lane |
| `lane` | 0-based lane index | lane ID 最后一个 `_` 后整数 |
| `x/y` | CityFlow 平面坐标 | `vehicleDistance` 沿 `road.points` 折线插值 |
| `angle` | 度，0 为 +x，逆时针 | `atan2(dy, dx)` |
| `speed` | m/s | `engine.get_vehicle_speed()` |

限制：CityFlow 0.1 不直接返回车辆世界坐标，当前位置是 road points 与 distance 的近似；路口内部 laneLink 轨迹没有进入 frame。

### 7.2 RoadStateDto

| 字段 | 当前口径 |
|---|---|
| `vehicleCount` | 所有 lane 的 `get_lane_vehicle_count()` 求和 |
| `queueCount` | 所有 lane 的 `get_lane_waiting_vehicle_count()` 求和 |
| `avgSpeed` | 当前返回的可视车辆中属于该 road 的速度平均值 |
| `level=jammed` | `vehicleCount >= 12` 或 `queueCount >= 5` |
| `level=slow` | `vehicleCount >= 5` 或有车且 `avgSpeed < 5 m/s` |
| `level=free` | 其他情况 |

注意：`avgSpeed` 只使用 visible vehicles，而 `vehicleCount` 使用引擎全部车辆。车辆超过 visible limit 时，两者统计范围不同，SUMO 迁移时必须先兼容，再单独评审是否统一。

### 7.3 laneStates 与 movement 口径

每个真实路口固定返回八个 key：

```text
WT WL ST SL ET EL NT NL
```

其中第一位表示从哪个方向进入路口，`T` 表示直行，`L` 表示左转。movement 来源是 CityFlow roadLink 的 `startRoad`、`type` 和 laneLink `startLaneIndex`。

方向判断依据进入 road 起点相对路口中心的几何位置：

- x 绝对值占主导：负 x 为 W，正 x 为 E。
- y 绝对值占主导：负 y 为 S，正 y 为 N。
- 当前只将 `go_straight` 和 `turn_left` 映射到 Traffic-R movement；右转不会形成独立 Traffic-R lane code。

`LaneMovementStateDto`：

| 字段 | 当前口径 |
|---|---|
| `queue_len` | movement 对应物理 lane 的 waiting vehicle count 累加 |
| `avg_wait_time` | 当前不是引擎真实平均等待；固定为 `queue_len * 3.0` 秒 |
| `cells[4]` | 非排队车辆按距停止线剩余距离划分四等分后的数量 |

cells 计算：

```text
remaining = roadLength - distanceFromStart
segmentLength = roadLength / 4
cellIndex = floor(remaining / segmentLength)，限制到 0..3
```

因此 cell 0 最接近停止线，cell 3 最远。速度 `< 0.1 m/s` 的车辆不进入 cells；它们由 queue_len 表达。

### 7.4 IntersectionStateDto

| 字段 | 当前口径 |
|---|---|
| `queueCount` | 所有以该路口为终点的 road.queueCount 求和 |
| `avgWait` | 当前近似为 `queueCount * 3.0` 秒 |
| `level=jammed` | `queueCount >= 8` |
| `level=slow` | 未 jammed 且 incoming vehicleCount 总和 `>= 8` |
| `level=free` | 其他情况 |

### 7.5 SignalStateDto

`phaseIndex` 来源于 session 中记录的当前相位，而不是 CityFlow `get_tl_phase`，因为当前 CityFlow 版本没有该 API。`phaseCode` 使用 2..5 到 ETWT/NTST/ELWL/NLSL 的映射。

初始化优先使用 ETWT，即 `phaseIndex=2`。配置启用自动循环时，每 10 仿真秒切换一个业务相位。外部 actions 成功下发后，session 更新其记录相位。

### 7.6 SimulationMetricsDto

| 字段 | 当前口径 |
|---|---|
| `vehicleCount` | `engine.get_vehicle_count()`，全部活动车辆 |
| `activeVehicleCount` | 与 vehicleCount 相同 |
| `scheduledDepartureCount` | `min(totalFlowVehicles, finished + active)`；实际更接近累计已进入系统车辆数 |
| `queueCount` | 所有 road.queueCount 求和 |
| `avgSpeed` | visible vehicles 平均速度 |
| `avgWait` | 当前近似为 `queueCount * 3.0` 秒 |
| `throughput` | `engine.get_finished_vehicle_count()`，累计完成车辆数 |

这些字段是当前兼容口径，不等同于严格交通工程指标。SUMO 可提供更准确数据，但改进必须在完成兼容切换后单独版本化。

## 8. actions 控制契约

请求：

```text
source: fixed-time | max-pressure | traffic-r
simTime: number
decisions: ControlDecision[]
```

`ControlDecision` 字段为 `intersectionId`、`controllerType`、`phaseIndex`、`phaseCode`、`durationSec`、`confidence`、`reason`、`metadata`。

Python 归一化顺序：

1. 如果 phaseCode 是 ETWT/NTST/ELWL/NLSL，优先映射为 CityFlow phaseIndex 2/3/4/5。
2. 否则接受已经是 2..5 的 phaseIndex。
3. 兼容旧调用方传入 business index 1..4，并映射到 2..5。
4. 实际调用 `set_tl_phase(intersectionId, phaseIndex - 1)`。

响应 applied 项当前字段：`intersectionId`、归一化后的 `phaseIndex`、`cityflowPhaseId`、`phaseCode`、`status=applied`。

## 9. 应急数据契约

### 9.1 EvEventDto

`evId`、`evType`、`priority`、`intersectionId`、`decision`、`phaseIndex`、`phaseIndexBefore`、`timestamp`、`status`、`blockedBy`。

### 9.2 EvStatusDto

`evId`、`evType`、`priority`、`route`、`passedCount`、`totalCount`、`completed`、`elapsedTime`。

真实 CityFlow 每次 frame 构建前执行 EV priority step；EV override 会尝试更新信号相位。session stop、自然结束和资源回收都会释放 EV 状态。

## 10. Spring Boot 解析、策略、上传和持久化链路

### 10.1 静态路网

```text
GET /api/v1/scenes/{sceneId}/roadnet
  -> SceneController
  -> RoadnetService
  -> CityFlowClient.getRoadnet
  -> GET /cityflow/scenes/{sceneId}/roadnet
  -> RoadnetResponse
```

创建 session 时，`SimulationService.createSimulation()` 会再次获取 roadnet：

```text
CityFlow create response
  -> getRoadnet(sceneId)
  -> RuntimePersistenceService.ensureRoadnet
  -> LiveSimulationStateService.registerSession(..., roadnet)
  -> SimulationSessionRegistry.register
```

### 10.2 实时帧与策略

```text
SimulationFrameScheduler
  -> SimulationService.publishNextFrame
  -> CityFlowClient.nextFrame
  -> SimFrameData
  -> StrategyDispatchService.decideAndApply
  -> SafetyLayerService
  -> async CityFlowClient.applyControlActions
```

如果有决策，Spring Boot 先推送 `control.decision`，再推送 `sim.frame`。actions 为异步提交，因此当前 `control.decision` 只表示已生成并提交，不表示 CityFlow 已执行；真实灯色必须以后续 `sim.frame.signals` 为准。

### 10.3 WebSocket 信封

```text
v: "1.0"
type: "sim.frame" | "control.decision"
sid: string
seq: Spring Boot session sequence
simTime: number
sentAt: RFC3339 string
data: SimFrameData | ControlDecision[]
```

WebSocket 路径为 `/ws/v1/simulations/{sid}`。当前客户端订阅消息不会改变服务端 topic 过滤行为，服务端按 sid 向全部连接广播。

### 10.4 内存和数据库

- `LiveSimulationStateService.recordFrame` 保存最近实时帧和决策，供 Agent 实时查询。
- `RuntimePersistenceService.persistRuntimeEvents` 当前保存控制决策、Traffic-R 推理、fallback、安全等事件，不再默认逐帧全量持久化车辆和道路快照。
- session 创建和生命周期状态会写入数据库。
- 数据库现有字段大量使用 `cityflow_id` 命名，SUMO 迁移第一轮需要兼容，后续通过增量 migration 引入通用 engine ID。

## 11. Traffic-R golden 契约

### 11.1 固定输入顺序

```text
LANE_ORDER = WT, WL, ST, SL, ET, EL, NT, NL
FOUR_PHASE_LIST = ETWT, NTST, ELWL, NLSL
```

Traffic-R 请求中的 phaseCandidate 使用 business index 1..4；Spring Boot 收到模型输出后再通过 `JinanPhaseMapper` 转为 CityFlow phaseIndex 2..5。

### 11.2 prompt 分段规则

每个 phase 拆成两条允许 movement。例如 ETWT -> ET、WT。对每条 movement：

- Early queued 使用 `queue_len`。
- Segment 1 使用 `cells[0]`。
- Segment 2 使用 `cells[1]`。
- Segment 3 使用 `cells[2] + cells[3]`。

`avg_wait_time` 当前会进入 Traffic-R JSON，但官方 commonsense prompt 表格并未输出该字段。

固定 request 与期望表格见 `traffic-r-golden.json`。该 fixture 是 SUMO movement 和 cells 对齐的首要回归基线。

## 12. 前端消费链路与已知契约差异

```text
useSimulationWebSocket
  -> lastFrameData
  -> Dashboard watch
  -> trafficStore.handleSimulationFrame
  -> applySimFrameToTrafficData
  -> 地图、车辆、信号、道路颜色、指标
```

当前前端按 `intersection_R_C` 与业务路口 `col_row` 建立映射，并按 road 两端无序端点对聚合双向道路。这是 Jinan 专属行为，真实 SUMO 场景必须改为使用 roadnet/geoPoints 的稳定 ID 和地理几何。

本次基线检查发现 TypeScript 的 `laneStates` 和 metrics 类型曾少于 Java 实际结构；阶段 0 已将类型定义修正为与后端 JSON 一致，但不改变运行时业务逻辑。

## 13. SUMO 兼容的不可变要求

- 旧字段名称、JSON 类型和单位在第一轮切换中保持不变。
- `roadId`、`intersectionId`、roadLink index 和 phaseCode 必须可在同一场景内稳定关联。
- `laneStates` 必须始终提供八个 movement key；不适用 movement 返回零状态，不能缺失。
- Traffic-R 控制路口必须有完整 movement-map 和 phase-map。
- actions 仍以业务 phaseCode 为主，由仿真 Adapter 映射到引擎原生相位。
- 前端信号状态只信任后续 frame，不信任模型意图。
- 指标口径如需从近似值升级为 SUMO 真实值，必须新增版本说明和同源回归结果。
- 任何字段无法提供时必须阻止场景进入可控状态，不能使用 mock 或固定成功值伪装兼容。

## 14. 未完成的阶段 0 验证

- 在真实 CityFlow 云端服务上重新采集一组脱敏 frame、actions 和 EV 样本，与本地 fixtures 比较字段及单位。
- 用 Traffic-R 原训练/评测环境核对 queue 阈值、等待时间和四个 cells 的原始定义；当前项目 CityFlow 实现中 `avg_wait_time=queue_len*3` 属于工程近似。
- 核对前端对新增准确 laneStates 类型的 type-check 和相关测试。

