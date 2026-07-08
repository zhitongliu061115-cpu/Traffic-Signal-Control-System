# CityFlow 实时仿真前后端通信协议 CFRP 1.0

## 1. 协议目标

本协议用于 Spring Boot 后端向 TypeScript + Vue 前端提供 CityFlow 实时仿真数据，使前端能够完成：

- 渲染静态路网：路口、道路、车道数量、路口转向关系、信号相位。
- 渲染动态车流：车辆位置、速度、朝向、所在道路和车道。
- 渲染实时交通状态：道路拥堵、路口排队、信号灯当前相位、全局指标。
- 保留后续扩展能力：RL 模型调度、应急绿波、告警事件、Agent 建议等模块可以在不破坏旧协议的情况下接入。

---

## 2. 通信方式

采用 **REST + WebSocket**。

| 数据类型 | 通信方式 | 说明 |
|---|---|---|
| 静态路网 | REST | 前端进入页面时加载一次，后续复用。 |
| 仿真会话创建/控制 | REST 或 WebSocket command | 低频控制可用 REST；页面内实时控制可用 WebSocket。 |
| 实时仿真帧 | WebSocket | 持续推送车辆、道路、路口、信号灯和指标。 |
| 扩展事件 | WebSocket | 后续 RL 决策、绿波、告警均通过新增 topic/messageType 扩展。 |

### 2.1 标准参考

| 标准 | 在本协议中的用途 |
|---|---|
| RFC 6455 WebSocket | 实时双向通信通道 |
| RFC 8259 JSON | 消息序列化格式 |
| RFC 3339 Timestamp | `sentAt` 时间格式 |
| Semantic Versioning | 协议版本管理 |

---

## 3. 设计原则

1. **静态和动态分离**：路网结构通过 REST 返回，实时状态通过 WebSocket 推送。
2. **统一消息信封**：所有 WebSocket 消息使用统一顶层结构。
3. **按 topic 扩展**：前端订阅需要的数据主题，后端按 topic 发送内容。
4. **模块命名隔离**：消息类型采用 `模块.事件`，例如 `sim.frame`、`rl.decision`、`greenwave.plan`。
5. **向后兼容**：新增字段只允许加到 `data` 内；前端必须忽略不认识的字段和消息类型。

---

## 4. REST 静态路网接口

### 4.1 获取静态路网

```http
GET /api/v1/scenes/{sceneId}/roadnet
```

示例：

```http
GET /api/v1/scenes/jinan_3x4/roadnet
```

### 4.2 接口作用

该接口由后端从 CityFlow `roadnet_3_4.json` 中解析生成。前端只需加载一次，用于初始化地图。

CityFlow 原始字段和协议字段对应关系：

| CityFlow 来源 | 协议字段 | 说明 |
|---|---|---|
| `intersections[].id` | `intersections[].id` | 路口 ID |
| `intersections[].point.x` | `intersections[].x` | 路口 x 坐标 |
| `intersections[].point.y` | `intersections[].y` | 路口 y 坐标 |
| `intersections[].virtual` | `intersections[].virtual` | 是否虚拟路口 |
| `roads[].id` | `roads[].id` | 道路 ID |
| `roads[].startIntersection` | `roads[].from` | 起点路口 |
| `roads[].endIntersection` | `roads[].to` | 终点路口 |
| `roads[].points` | `roads[].points` | 道路折线坐标 |
| `roads[].lanes.length` | `roads[].laneCount` | 车道数量 |
| `intersections[].roadLinks` | `roadLinks` | 路口转向连接 |
| `trafficLight.lightphases` | `phases` | 信号灯相位 |

### 4.3 RoadnetResponse 完整示例

```json
{
  "sceneId": "jinan_3x4",
  "intersections": [
    {
      "id": "intersection_1_1",
      "x": 300.0,
      "y": 600.0,
      "virtual": false
    },
    {
      "id": "intersection_1_0",
      "x": 0.0,
      "y": 600.0,
      "virtual": true
    }
  ],
  "roads": [
    {
      "id": "road_1_1_0",
      "from": "intersection_1_0",
      "to": "intersection_1_1",
      "points": [
        { "x": 0.0, "y": 600.0 },
        { "x": 300.0, "y": 600.0 }
      ],
      "laneCount": 3
    },
    {
      "id": "road_1_1_2",
      "from": "intersection_1_1",
      "to": "intersection_1_2",
      "points": [
        { "x": 300.0, "y": 600.0 },
        { "x": 600.0, "y": 600.0 }
      ],
      "laneCount": 3
    }
  ],
  "roadLinks": [
    {
      "intersectionId": "intersection_1_1",
      "index": 0,
      "fromRoadId": "road_1_1_0",
      "toRoadId": "road_1_1_2",
      "type": "go_straight"
    },
    {
      "intersectionId": "intersection_1_1",
      "index": 1,
      "fromRoadId": "road_1_1_0",
      "toRoadId": "road_0_1_1",
      "type": "turn_left"
    }
  ],
  "phases": [
    {
      "intersectionId": "intersection_1_1",
      "phaseIndex": 1,
      "phaseCode": "ETWT",
      "roadLinkIndexes": [0, 3]
    },
    {
      "intersectionId": "intersection_1_1",
      "phaseIndex": 2,
      "phaseCode": "NTST",
      "roadLinkIndexes": [1, 4]
    }
  ]
}
```

### 4.4 RoadnetResponse 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `sceneId` | `string` | 是 | 场景 ID，标识当前路网。 | `"jinan_3x4"` |
| `intersections` | `Intersection[]` | 是 | 路口节点列表，前端用它绘制节点和真实控制路口。 | `[{ "id": "intersection_1_1", "x": 300, "y": 600, "virtual": false }]` |
| `roads` | `Road[]` | 是 | 道路列表，前端用它绘制道路折线。 | `[{ "id": "road_1_1_0", "from": "...", "to": "...", "points": [], "laneCount": 3 }]` |
| `roadLinks` | `RoadLink[]` | 是 | 路口内转向连接，用于展示相位放行方向。 | `[{ "intersectionId": "...", "index": 0, "type": "go_straight" }]` |
| `phases` | `Phase[]` | 是 | 信号相位配置，用于将实时 `phaseIndex` 映射到放行 roadLink。 | `[{ "phaseIndex": 1, "roadLinkIndexes": [0, 3] }]` |

### 4.5 Intersection 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `id` | `string` | 是 | CityFlow 路口 ID。 | `"intersection_1_1"` |
| `x` | `number` | 是 | 路口 x 坐标，来自 CityFlow `point.x`。 | `300.0` |
| `y` | `number` | 是 | 路口 y 坐标，来自 CityFlow `point.y`。 | `600.0` |
| `virtual` | `boolean` | 是 | 是否虚拟路口。`true` 是边界节点，`false` 是真实路口。 | `false` |

前端用法：

```text
virtual=false：绘制为真实信号控制路口，可显示信号灯。
virtual=true：绘制为边界节点，可弱化显示或不显示。
```

### 4.6 Road 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `id` | `string` | 是 | CityFlow 道路 ID。 | `"road_1_1_0"` |
| `from` | `string` | 是 | 起点路口 ID。 | `"intersection_1_0"` |
| `to` | `string` | 是 | 终点路口 ID。 | `"intersection_1_1"` |
| `points` | `Point[]` | 是 | 道路折线点。前端按顺序连线绘制道路。 | `[{ "x": 0, "y": 600 }, { "x": 300, "y": 600 }]` |
| `laneCount` | `number` | 是 | 车道数量，来自 CityFlow `lanes.length`。 | `3` |

前端用法：

```text
points：绘制道路中心线。
laneCount：决定道路显示宽度或车道线数量。
from/to：用于调试道路方向，也可用于构建拓扑关系。
```

### 4.7 RoadLink 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `intersectionId` | `string` | 是 | roadLink 所属路口。 | `"intersection_1_1"` |
| `index` | `number` | 是 | 该连接在 CityFlow `roadLinks` 数组中的索引。 | `0` |
| `fromRoadId` | `string` | 是 | 进入路口的道路。 | `"road_1_1_0"` |
| `toRoadId` | `string` | 是 | 驶出路口的道路。 | `"road_1_1_2"` |
| `type` | `string` | 是 | 转向类型。可选：`go_straight`、`turn_left`、`turn_right`。 | `"go_straight"` |

前端用法：

```text
当 signals[].phaseIndex = 1 时，前端查 phases 中 phaseIndex=1 的 roadLinkIndexes。
再用 roadLinkIndexes 找到 roadLinks 中对应的 fromRoadId/toRoadId。
最后高亮这些 roadLink 对应的放行方向。
```

### 4.8 Phase 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `intersectionId` | `string` | 是 | 相位所属路口。 | `"intersection_1_1"` |
| `phaseIndex` | `number` | 是 | CityFlow 相位编号。 | `1` |
| `phaseCode` | `string` | 否 | 业务相位编码。四相位控制建议使用 `ETWT`、`NTST`、`ELWL`、`NLSL`。 | `"ETWT"` |
| `roadLinkIndexes` | `number[]` | 是 | 该相位放行的 roadLink 索引。 | `[0, 3]` |

---

## 5. WebSocket 连接

### 5.1 连接地址

```text
ws://{host}:{port}/ws/v1/simulations/{sid}
```

示例：

```text
ws://localhost:8080/ws/v1/simulations/run_001
```

生产环境：

```text
wss://api.example.com/ws/v1/simulations/run_001
```

---

## 6. WebSocket 统一消息信封

所有 WebSocket 消息必须使用统一信封。

```json
{
  "v": "1.0",
  "type": "sim.frame",
  "sid": "run_001",
  "seq": 120,
  "simTime": 120.0,
  "sentAt": "2026-07-08T10:32:00+08:00",
  "data": {}
}
```

### 6.1 顶层字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `v` | `string` | 是 | 协议版本。当前固定为 `"1.0"`。 | `"1.0"` |
| `type` | `string` | 是 | 消息类型，格式为 `模块.事件`。 | `"sim.frame"` |
| `sid` | `string` | 是 | 仿真会话 ID。一个 sid 对应一次 CityFlow 仿真运行。 | `"run_001"` |
| `seq` | `number` | 是 | 会话内递增序号。前端用它判断丢帧和乱序。 | `120` |
| `simTime` | `number` | 是 | CityFlow 仿真时间，单位秒。 | `120.0` |
| `sentAt` | `string` | 是 | 后端发送时间，使用 RFC 3339 格式。 | `"2026-07-08T10:32:00+08:00"` |
| `data` | `object` | 是 | 业务数据。结构由 `type` 决定。 | `{}` |

### 6.2 为什么不在顶层加入更多字段

顶层字段只表达协议元信息，业务信息全部进入 `data`。这样后续扩展 RL、绿波、告警时，只需要增加新的 `type` 和 `data` 结构，不会破坏已有前端对 `sim.frame` 的解析。

---

## 7. 消息类型命名空间

| 命名空间 | 说明 | 当前是否启用 | 示例 |
|---|---|---:|---|
| `sim.*` | CityFlow 仿真状态和实时帧 | 是 | `sim.frame` |
| `client.*` | 前端向后端发送的订阅和控制命令 | 是 | `client.subscribe` |
| `rl.*` | RL/LLM 模型调度和决策事件 | 预留 | `rl.decision` |
| `greenwave.*` | 应急绿波和特殊车辆优先通行 | 预留 | `greenwave.plan` |
| `alert.*` | 拥堵、异常、系统告警 | 预留 | `alert.created` |
| `agent.*` | 智能问答、解释建议、运维 Agent | 预留 | `agent.message` |

---

## 8. client.subscribe 订阅消息

前端连接 WebSocket 后，先发送订阅消息，声明需要哪些 topic。

### 8.1 示例

```json
{
  "v": "1.0",
  "type": "client.subscribe",
  "sid": "run_001",
  "seq": 1,
  "simTime": 0,
  "sentAt": "2026-07-08T10:30:01+08:00",
  "data": {
    "topics": ["vehicles", "roads", "intersections", "signals", "metrics"],
    "intervalMs": 1000
  }
}
```

### 8.2 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `topics` | `string[]` | 是 | 前端希望接收的数据主题。 | `["vehicles", "signals"]` |
| `intervalMs` | `number` | 是 | 期望推送间隔，单位毫秒。后端可按负载调整。 | `1000` |

### 8.3 当前可订阅 topic

| topic | 作用 |
|---|---|
| `vehicles` | 车辆位置、速度、朝向。 |
| `roads` | 道路车辆数、排队数、拥堵等级。 |
| `intersections` | 路口排队、平均等待、拥堵等级。 |
| `signals` | 当前信号相位。 |
| `metrics` | 全局指标。 |

### 8.4 预留扩展 topic

| topic | 所属模块 | 用途 |
|---|---|---|
| `rlDecisions` | `rl.*` | 推送 RL/LLM 控制模型的决策结果。 |
| `greenwave` | `greenwave.*` | 推送应急车辆绿波计划和当前执行状态。 |
| `alerts` | `alert.*` | 推送异常拥堵、仿真错误、模型异常等事件。 |
| `agentMessages` | `agent.*` | 推送 Agent 解释、建议、问答消息。 |

说明：旧前端只订阅 `vehicles`、`roads` 等基础 topic，不会收到未来扩展模块的数据。

---

## 9. sim.frame 实时帧消息

### 9.1 作用

`sim.frame` 是前端渲染实时仿真效果的核心消息。后端从 CityFlow 当前状态中采集车辆、道路、路口、信号和指标，组装成一帧推送给前端。

### 9.2 CityFlow 数据来源说明

| 协议字段 | CityFlow 常用来源 | 说明 |
|---|---|---|
| `vehicles[].id` | `engine.get_vehicles()` | 当前仍在路网内的车辆 ID。 |
| `vehicles[].speed` | `engine.get_vehicle_speed()` | 车辆当前速度。 |
| `vehicles[].roadId` | 车辆位置信息或后端维护映射 | 当前所在道路。 |
| `vehicles[].lane` | 车辆位置信息或后端维护映射 | 当前所在车道索引。 |
| `vehicles[].x/y` | 由 roadnet points + distance/lane 计算 | 前端渲染坐标。 |
| `vehicles[].angle` | 根据道路方向或车辆连续位置计算 | 车辆图标旋转角度。 |
| `roads[].vehicleCount` | 按车辆 roadId 聚合 | 当前道路车辆数。 |
| `roads[].queueCount` | 按低速车辆或等待车辆聚合 | 当前道路排队数。 |
| `signals[].phaseIndex` | 后端当前控制相位记录 | CityFlow 当前信号相位。 |
| `metrics` | 后端聚合统计 | 全局车辆、排队、速度、吞吐量。 |

如果 CityFlow Python API 无法直接返回某些字段，后端可以根据 roadnet、车辆速度、车辆所在 road/lane、历史位置自行计算。

### 9.3 完整示例

```json
{
  "v": "1.0",
  "type": "sim.frame",
  "sid": "run_001",
  "seq": 120,
  "simTime": 120.0,
  "sentAt": "2026-07-08T10:32:00+08:00",
  "data": {
    "vehicles": [
      {
        "id": "vehicle_001",
        "roadId": "road_1_1_0",
        "lane": 0,
        "x": 120.4,
        "y": 300.8,
        "angle": 90.0,
        "speed": 8.2
      },
      {
        "id": "vehicle_002",
        "roadId": "road_1_1_0",
        "lane": 1,
        "x": 155.2,
        "y": 304.0,
        "angle": 90.0,
        "speed": 0.0
      }
    ],
    "roads": [
      {
        "id": "road_1_1_0",
        "vehicleCount": 18,
        "queueCount": 6,
        "avgSpeed": 4.8,
        "level": "slow"
      },
      {
        "id": "road_1_1_2",
        "vehicleCount": 7,
        "queueCount": 0,
        "avgSpeed": 11.5,
        "level": "free"
      }
    ],
    "intersections": [
      {
        "id": "intersection_1_1",
        "queueCount": 14,
        "avgWait": 28.5,
        "level": "jammed"
      }
    ],
    "signals": [
      {
        "intersectionId": "intersection_1_1",
        "phaseIndex": 1,
        "phaseCode": "ETWT"
      }
    ],
    "metrics": {
      "vehicleCount": 582,
      "queueCount": 96,
      "avgSpeed": 7.4,
      "avgWait": 35.2,
      "throughput": 128
    }
  }
}
```

### 9.4 sim.frame.data 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `vehicles` | `VehicleState[]` | 是 | 当前帧车辆状态。前端用它更新车辆动画。 | `[{ "id": "vehicle_001", "x": 120.4, "y": 300.8 }]` |
| `roads` | `RoadState[]` | 是 | 当前道路状态。前端用它更新道路颜色和 tooltip。 | `[{ "id": "road_1_1_0", "level": "slow" }]` |
| `intersections` | `IntersectionState[]` | 是 | 当前路口状态。前端用它显示路口拥堵和排队。 | `[{ "id": "intersection_1_1", "queueCount": 14 }]` |
| `signals` | `SignalState[]` | 是 | 当前信号灯状态。前端用它高亮放行方向。 | `[{ "intersectionId": "intersection_1_1", "phaseIndex": 1 }]` |
| `metrics` | `SimulationMetrics` | 是 | 全局指标。前端用它更新大屏指标卡。 | `{ "vehicleCount": 582, "queueCount": 96 }` |

### 9.5 VehicleState 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `id` | `string` | 是 | 车辆 ID。同一车辆在不同帧中保持相同 ID，前端据此做动画插值。 | `"vehicle_001"` |
| `roadId` | `string` | 是 | 当前所在道路 ID，对应静态路网 `roads[].id`。 | `"road_1_1_0"` |
| `lane` | `number` | 是 | 当前车道索引，从 `0` 开始。 | `0` |
| `x` | `number` | 是 | 当前车辆渲染坐标 x。 | `120.4` |
| `y` | `number` | 是 | 当前车辆渲染坐标 y。 | `300.8` |
| `angle` | `number` | 是 | 车辆朝向角，单位度。前端用于旋转车辆图标。 | `90.0` |
| `speed` | `number` | 是 | 当前速度。可用于 tooltip、颜色或判断是否排队。 | `8.2` |

前端使用指南：

```text
1. 用 id 匹配上一帧车辆。
2. 用上一帧 x/y 到当前 x/y 做动画插值。
3. 用 angle 旋转车辆图标。
4. speed 接近 0 时可显示为停车或排队状态。
```

### 9.6 RoadState 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `id` | `string` | 是 | 道路 ID，对应静态路网 `roads[].id`。 | `"road_1_1_0"` |
| `vehicleCount` | `number` | 是 | 当前道路车辆数。 | `18` |
| `queueCount` | `number` | 是 | 当前道路排队车辆数。 | `6` |
| `avgSpeed` | `number` | 是 | 当前道路平均速度。 | `4.8` |
| `level` | `string` | 是 | 拥堵等级。可选：`free`、`slow`、`jammed`。 | `"slow"` |

建议前端颜色：

| `level` | 展示含义 | 建议颜色 |
|---|---|---|
| `free` | 通畅 | 绿色 |
| `slow` | 缓行 | 黄色 |
| `jammed` | 拥堵 | 红色 |

### 9.7 IntersectionState 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `id` | `string` | 是 | 路口 ID，对应静态路网 `intersections[].id`。 | `"intersection_1_1"` |
| `queueCount` | `number` | 是 | 路口附近总排队车辆数。 | `14` |
| `avgWait` | `number` | 是 | 路口平均等待时间，单位秒。 | `28.5` |
| `level` | `string` | 是 | 路口拥堵等级。可选：`free`、`slow`、`jammed`。 | `"jammed"` |

### 9.8 SignalState 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `intersectionId` | `string` | 是 | 信号灯所属路口 ID。 | `"intersection_1_1"` |
| `phaseIndex` | `number` | 是 | CityFlow 当前相位编号。 | `1` |
| `phaseCode` | `string` | 否 | 业务相位编码。四相位控制建议使用 `ETWT`、`NTST`、`ELWL`、`NLSL`。 | `"ETWT"` |

前端使用指南：

```text
1. 找到 signals 中某个 intersectionId 的当前 phaseIndex。
2. 在静态 phases 中查找同 intersectionId 且 phaseIndex 相同的相位。
3. 读取 roadLinkIndexes。
4. 根据 roadLinkIndexes 找到 roadLinks。
5. 高亮 roadLinks 对应的 fromRoadId -> toRoadId 放行方向。
```

### 9.9 SimulationMetrics 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `vehicleCount` | `number` | 是 | 当前路网内车辆总数。 | `582` |
| `queueCount` | `number` | 是 | 当前路网排队车辆总数。 | `96` |
| `avgSpeed` | `number` | 是 | 当前全局平均速度。 | `7.4` |
| `avgWait` | `number` | 是 | 当前全局平均等待时间，单位秒。 | `35.2` |
| `throughput` | `number` | 是 | 已完成行程车辆数。 | `128` |

---

## 10. sim.status 状态消息

### 10.1 示例

```json
{
  "v": "1.0",
  "type": "sim.status",
  "sid": "run_001",
  "seq": 1,
  "simTime": 0,
  "sentAt": "2026-07-08T10:30:00+08:00",
  "data": {
    "status": "running",
    "message": "simulation started"
  }
}
```

### 10.2 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `status` | `string` | 是 | 仿真状态。可选：`created`、`running`、`paused`、`finished`、`failed`。 | `"running"` |
| `message` | `string` | 否 | 状态说明。 | `"simulation started"` |

---

## 11. sim.error 错误消息

### 11.1 示例

```json
{
  "v": "1.0",
  "type": "sim.error",
  "sid": "run_001",
  "seq": 130,
  "simTime": 130.0,
  "sentAt": "2026-07-08T10:33:00+08:00",
  "data": {
    "code": "SIM_ENGINE_ERROR",
    "message": "CityFlow engine failed to step",
    "retryable": false
  }
}
```

### 11.2 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `code` | `string` | 是 | 错误码。 | `"SIM_ENGINE_ERROR"` |
| `message` | `string` | 是 | 错误说明。 | `"CityFlow engine failed to step"` |
| `retryable` | `boolean` | 是 | 是否可重试。 | `false` |

错误码：

| code | 含义 |
|---|---|
| `INVALID_SESSION` | 仿真会话不存在或已失效。 |
| `SIM_ENGINE_ERROR` | CityFlow 引擎运行失败。 |
| `INVALID_COMMAND` | 前端命令非法。 |
| `UNAUTHORIZED` | 无权限访问该会话。 |
| `INTERNAL_ERROR` | 后端内部错误。 |

---

## 12. client.command 控制消息

### 12.1 暂停示例

```json
{
  "v": "1.0",
  "type": "client.command",
  "sid": "run_001",
  "seq": 2,
  "simTime": 60.0,
  "sentAt": "2026-07-08T10:31:00+08:00",
  "data": {
    "command": "pause"
  }
}
```

### 12.2 设置倍速示例

```json
{
  "v": "1.0",
  "type": "client.command",
  "sid": "run_001",
  "seq": 3,
  "simTime": 60.0,
  "sentAt": "2026-07-08T10:31:05+08:00",
  "data": {
    "command": "setSpeed",
    "value": 2.0
  }
}
```

### 12.3 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `command` | `string` | 是 | 控制命令。可选：`start`、`pause`、`resume`、`stop`、`setSpeed`。 | `"pause"` |
| `value` | `number` | 否 | 命令参数。`setSpeed` 时表示播放倍速。 | `2.0` |

---

## 13. 扩展协议：RL 模型调度

本协议预留 `rl.*` 命名空间。后续接入 RL 或 LLM 控制模型时，不修改 `sim.frame`，而是新增 `rl.decision` 消息。

### 13.1 rl.decision 示例

```json
{
  "v": "1.1",
  "type": "rl.decision",
  "sid": "run_001",
  "seq": 121,
  "simTime": 120.0,
  "sentAt": "2026-07-08T10:32:01+08:00",
  "data": {
    "intersectionId": "intersection_1_1",
    "model": "LightGPT-0.5B-Qwen2",
    "action": {
      "phaseIndex": 1,
      "phaseCode": "ETWT"
    },
    "confidence": 0.82,
    "reason": "east-west through lanes have the largest queue"
  }
}
```

### 13.2 rl.decision 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `intersectionId` | `string` | 是 | 模型控制的路口。 | `"intersection_1_1"` |
| `model` | `string` | 是 | 模型名称或控制器名称。 | `"LightGPT-0.5B-Qwen2"` |
| `action` | `object` | 是 | 模型输出的控制动作。 | `{ "phaseIndex": 1, "phaseCode": "ETWT" }` |
| `action.phaseIndex` | `number` | 是 | 模型选择的 CityFlow 相位编号。 | `1` |
| `action.phaseCode` | `string` | 否 | 业务相位编码。 | `"ETWT"` |
| `confidence` | `number` | 否 | 模型置信度或策略评分。没有则不传。 | `0.82` |
| `reason` | `string` | 否 | 模型解释文本。没有解释能力可不传。 | `"east-west through lanes have the largest queue"` |

前端兼容说明：

```text
旧前端不订阅 rlDecisions，则不会收到 rl.decision。
新前端订阅 rlDecisions 后，可在页面侧栏展示模型每次决策。
信号灯实际显示仍以 sim.frame.data.signals 为准。
```

---

## 14. 扩展协议：应急绿波

本协议预留 `greenwave.*` 命名空间。后续接入救护车、消防车等应急车辆优先通行时，新增绿波消息，不修改基础仿真帧。

### 14.1 greenwave.plan 示例

```json
{
  "v": "1.1",
  "type": "greenwave.plan",
  "sid": "run_001",
  "seq": 200,
  "simTime": 300.0,
  "sentAt": "2026-07-08T10:35:00+08:00",
  "data": {
    "taskId": "emergency_001",
    "vehicleId": "ambulance_001",
    "route": ["road_1_1_0", "road_1_1_2", "road_1_1_3"],
    "affectedIntersections": ["intersection_1_1", "intersection_1_2"],
    "priority": "high",
    "status": "active"
  }
}
```

### 14.2 greenwave.plan 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `taskId` | `string` | 是 | 绿波任务 ID。 | `"emergency_001"` |
| `vehicleId` | `string` | 是 | 应急车辆 ID。 | `"ambulance_001"` |
| `route` | `string[]` | 是 | 应急车辆计划经过的道路 ID 数组。 | `["road_1_1_0", "road_1_1_2"]` |
| `affectedIntersections` | `string[]` | 是 | 需要配合调整信号的路口 ID 数组。 | `["intersection_1_1"]` |
| `priority` | `string` | 是 | 优先级。可选：`low`、`medium`、`high`。 | `"high"` |
| `status` | `string` | 是 | 绿波任务状态。可选：`planned`、`active`、`finished`、`cancelled`。 | `"active"` |

前端兼容说明：

```text
基础仿真页面无需处理 greenwave.plan。
应急模块页面订阅 greenwave 后，可高亮 route 和 affectedIntersections。
实际车辆位置仍由 sim.frame.data.vehicles 提供。
实际信号灯状态仍由 sim.frame.data.signals 提供。
```

---

## 15. 扩展协议：告警事件

本协议预留 `alert.*` 命名空间。

### 15.1 alert.created 示例

```json
{
  "v": "1.1",
  "type": "alert.created",
  "sid": "run_001",
  "seq": 260,
  "simTime": 420.0,
  "sentAt": "2026-07-08T10:37:00+08:00",
  "data": {
    "alertId": "alert_001",
    "level": "warning",
    "targetType": "road",
    "targetId": "road_1_1_0",
    "message": "Road queue count exceeds threshold"
  }
}
```

### 15.2 alert.created 字段说明

| 字段 | 类型 | 必需 | 含义 | 示例 |
|---|---|---:|---|---|
| `alertId` | `string` | 是 | 告警 ID。 | `"alert_001"` |
| `level` | `string` | 是 | 告警等级。可选：`info`、`warning`、`critical`。 | `"warning"` |
| `targetType` | `string` | 是 | 告警对象类型。可选：`road`、`intersection`、`vehicle`、`system`。 | `"road"` |
| `targetId` | `string` | 是 | 告警对象 ID。 | `"road_1_1_0"` |
| `message` | `string` | 是 | 告警说明。 | `"Road queue count exceeds threshold"` |

---

## 16. 版本与兼容规则

| 变更类型 | 是否兼容 | 版本处理 |
|---|---:|---|
| 新增 `data` 内字段 | 是 | 小版本升级，例如 `1.1` |
| 新增消息类型 | 是 | 小版本升级，例如 `1.1` |
| 新增 topic | 是 | 小版本升级，例如 `1.1` |
| 删除已有字段 | 否 | 大版本升级，例如 `2.0` |
| 修改已有字段含义 | 否 | 大版本升级，例如 `2.0` |

前端处理要求：

```text
不认识的字段：忽略。
不认识的 type：记录日志，不报错。
不认识的 topic：不订阅。
同一字段含义不得随版本变化。
```

---

## 17. TypeScript 类型定义

```ts
export type MessageType =
  | 'sim.status'
  | 'sim.frame'
  | 'sim.error'
  | 'client.subscribe'
  | 'client.command'
  | 'rl.decision'
  | 'greenwave.plan'
  | 'alert.created';

export interface WsMessage<T = unknown> {
  v: string;
  type: MessageType | string;
  sid: string;
  seq: number;
  simTime: number;
  sentAt: string;
  data: T;
}

export interface RoadnetResponse {
  sceneId: string;
  intersections: Intersection[];
  roads: Road[];
  roadLinks: RoadLink[];
  phases: Phase[];
}

export interface Intersection {
  id: string;
  x: number;
  y: number;
  virtual: boolean;
}

export interface Road {
  id: string;
  from: string;
  to: string;
  points: Point[];
  laneCount: number;
}

export interface Point {
  x: number;
  y: number;
}

export interface RoadLink {
  intersectionId: string;
  index: number;
  fromRoadId: string;
  toRoadId: string;
  type: 'go_straight' | 'turn_left' | 'turn_right' | string;
}

export interface Phase {
  intersectionId: string;
  phaseIndex: number;
  phaseCode?: 'ETWT' | 'NTST' | 'ELWL' | 'NLSL' | string;
  roadLinkIndexes: number[];
}

export interface SimFrameData {
  vehicles: VehicleState[];
  roads: RoadState[];
  intersections: IntersectionState[];
  signals: SignalState[];
  metrics: SimulationMetrics;
}

export interface VehicleState {
  id: string;
  roadId: string;
  lane: number;
  x: number;
  y: number;
  angle: number;
  speed: number;
}

export interface RoadState {
  id: string;
  vehicleCount: number;
  queueCount: number;
  avgSpeed: number;
  level: CongestionLevel;
}

export interface IntersectionState {
  id: string;
  queueCount: number;
  avgWait: number;
  level: CongestionLevel;
}

export interface SignalState {
  intersectionId: string;
  phaseIndex: number;
  phaseCode?: string;
}

export interface SimulationMetrics {
  vehicleCount: number;
  queueCount: number;
  avgSpeed: number;
  avgWait: number;
  throughput: number;
}

export type CongestionLevel = 'free' | 'slow' | 'jammed';

export interface SubscribeData {
  topics: string[];
  intervalMs: number;
}

export interface CommandData {
  command: 'start' | 'pause' | 'resume' | 'stop' | 'setSpeed';
  value?: number;
}

export interface RlDecisionData {
  intersectionId: string;
  model: string;
  action: {
    phaseIndex: number;
    phaseCode?: string;
  };
  confidence?: number;
  reason?: string;
}

export interface GreenWavePlanData {
  taskId: string;
  vehicleId: string;
  route: string[];
  affectedIntersections: string[];
  priority: 'low' | 'medium' | 'high';
  status: 'planned' | 'active' | 'finished' | 'cancelled';
}

export interface AlertCreatedData {
  alertId: string;
  level: 'info' | 'warning' | 'critical';
  targetType: 'road' | 'intersection' | 'vehicle' | 'system';
  targetId: string;
  message: string;
}
```

---

## 18. 前端接入流程

```text
1. 调用 GET /api/v1/scenes/{sceneId}/roadnet 获取静态路网。
2. 根据 intersections 绘制路口。
3. 根据 roads.points 绘制道路。
4. 根据 roadLinks 和 phases 建立“相位 -> 放行方向”的映射。
5. 建立 WebSocket 连接。
6. 发送 client.subscribe。
7. 收到 sim.frame 后：
   - vehicles 更新车辆动画；
   - roads 更新道路颜色；
   - intersections 更新路口状态；
   - signals 高亮信号灯放行方向；
   - metrics 更新指标卡。
8. 如果后续启用 RL、绿波、告警：
   - 订阅对应 topic；
   - 增加对应 type 的处理器；
   - 不修改已有 sim.frame 渲染逻辑。
```

---

## 19. 后端组装 sim.frame 的建议

```text
1. CityFlow engine 推进一步。
2. 获取当前车辆列表。
3. 为每辆车计算 roadId、lane、x、y、angle、speed。
4. 按 roadId 聚合 vehicleCount、queueCount、avgSpeed。
5. 按 intersectionId 聚合 queueCount、avgWait、level。
6. 读取当前每个路口的 phaseIndex。
7. 聚合全局 metrics。
8. 按前端订阅 topics 裁剪 data 内容。
9. 发送 sim.frame。
```

---

## 20. 协议边界

本协议只规定前后端通信数据格式，不规定：

- CityFlow 引擎内部实现。
- RL 模型训练方法。
- 前端地图渲染框架选择。
- 数据库表结构。
- 用户权限系统实现。

这些内容可以在其他设计文档中单独定义。
