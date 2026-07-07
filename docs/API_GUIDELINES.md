# 接口规范

> 适用项目:**AI 自适应信号控制与应急绿波数字孪生系统**
> 本文是**前后端唯一真相来源**:字段命名、消息格式以此为准,任何一端不得私改。改接口 → 先改本文 + 群里同步。
> 约定:REST 用于查询/操作,**WebSocket 用于实时推送**(车流、信号、应急、指标),这是大屏实时性的核心。

---

## 1. 通用约定

- 基础路径:`/api/v1`,所有接口带版本号。
- 编码:UTF-8;时间统一 **ISO 8601 UTC**(如 `2026-07-06T10:20:00Z`)或毫秒时间戳,二选一并注明。
- 字段命名:**统一 `camelCase`**(前后端一致,避免转换)。
- 单位固定:距离米、速度 m/s、时间秒、坐标经纬度(`lng`/`lat`)、相位从 `1` 开始编号。

### REST 统一响应信封

```json
{
  "code": 0,
  "msg": "ok",
  "data": { }
}
```

- `code`:`0` 成功;非 0 见错误码表。
- 失败时 `data` 为 `null`,`msg` 给可读原因。

### 错误码表

| code | 含义 |
|---|---|
| 0 | 成功 |
| 1001 | 参数错误 |
| 1002 | 资源不存在(路口/路段/事件 id 无效) |
| 2001 | 信号控制内部错误 |
| 2002 | 应急调度冲突(已有更高优先级事件) |
| 3001 | 智能体/RAG 服务不可用 |
| 5000 | 服务器内部错误 |

---

## 2. 核心数据结构(全项目共用)

### Junction 路口

```json
{
  "id": "J1",
  "name": "人民路-建设路",
  "lng": 116.397,
  "lat": 39.908,
  "phaseId": 2,             // 当前放行相位编号
  "phaseRemaining": 15,     // 当前相位剩余秒数
  "mode": "adaptive",       // fixed | adaptive | emergency
  "congestion": 0.72        // 0~1
}
```

### Link 路段

```json
{
  "id": "R1",
  "from": "J1",             // 起点路口 id
  "to": "J2",               // 终点路口 id
  "length": 320,            // 米
  "vehicleCount": 24,
  "avgSpeed": 8.5,          // m/s
  "congestion": 0.65,       // 0~1,大屏据此渐变红
  "level": "heavy"          // free | slow | heavy | jam
}
```

### Vehicle 车辆

```json
{
  "id": "V1023",
  "type": "car",            // car | bus | truck | ambulance | fire | police
  "linkId": "R1",
  "position": 0.42,         // 0~1,在路段上的相对位置
  "lng": 116.398,
  "lat": 39.907,
  "speed": 12.3,            // m/s
  "isEmergency": false
}
```

### EmergencyEvent 应急事件

```json
{
  "eventId": "E-20260706-001",
  "vehicleId": "V-AMB-01",
  "vehicleType": "ambulance",
  "status": "active",                 // active | cleared
  "route": ["J1", "J3", "J7"],        // 应急车辆行经路口序列
  "greenWaveJunctions": ["J1","J3","J7"], // 已切绿波的路口
  "startedAt": "2026-07-06T10:20:00Z"
}
```

### Metrics 指标(AI 前后对比)

```json
{
  "timestamp": "2026-07-06T10:20:00Z",
  "mode": "adaptive",
  "avgDelay": 28.4,        // 平均延误(秒)
  "avgQueueLength": 6.2,   // 平均排队(车)
  "throughput": 1820,      // 通行量(veh/h)
  "avgStops": 1.3,         // 平均停车次数
  "baseline": {            // 固定配时对照组
    "avgDelay": 41.2,
    "throughput": 1520
  }
}
```

---

## 3. REST 接口

### 3.1 路网与状态查询

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/network` | 获取路网静态结构(路口 + 路段拓扑),前端初始化用 |
| GET | `/api/v1/junctions` | 所有路口当前状态 |
| GET | `/api/v1/junctions/{id}` | 单个路口详情 |
| GET | `/api/v1/links` | 所有路段当前状态 |
| GET | `/api/v1/metrics` | 当前 AI 前后对比指标 |

`GET /api/v1/network` 返回示例:

```json
{
  "code": 0, "msg": "ok",
  "data": {
    "junctions": [ { "id": "J1", "name": "...", "lng": 116.397, "lat": 39.908 } ],
    "links": [ { "id": "R1", "from": "J1", "to": "J2", "length": 320 } ]
  }
}
```

### 3.2 信号控制(signal)

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/signal/mode` | 切换全局/单路口控制模式 |
| POST | `/api/v1/signal/{id}/phase` | 手动干预某路口相位(演示/调试用) |

`POST /api/v1/signal/mode` 请求:

```json
{ "junctionId": "J1", "mode": "adaptive" }   // junctionId 省略表示全局
```

### 3.3 应急绿波(emergency)

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/emergency/trigger` | 触发一辆应急车辆(仿真/演示) |
| POST | `/api/v1/emergency/{eventId}/clear` | 结束应急事件,恢复常态控制 |
| GET | `/api/v1/emergency/active` | 当前进行中的应急事件列表 |

`POST /api/v1/emergency/trigger` 请求:

```json
{
  "vehicleType": "ambulance",
  "route": ["J1", "J3", "J7"]
}
```

返回创建的 `EmergencyEvent`。

### 3.4 智能体 + RAG 问答(agent)

```
POST /api/v1/agent/chat
```

请求:

```json
{
  "sessionId": "s-abc123",
  "message": "现在东西向为什么这么堵?",
  "context": { "junctionId": "J3" }     // 可选,携带当前关注对象
}
```

响应:

```json
{
  "code": 0, "msg": "ok",
  "data": {
    "answer": "J3 东西向拥堵度 0.82,主因是……",
    "actions": [
      { "type": "adjust_phase", "junctionId": "J3", "phaseId": 1, "greenSec": 30 }
    ],
    "citations": [
      { "docId": "kb-042", "title": "自适应配时策略说明", "score": 0.87 }
    ]
  }
}
```

- `answer`:自然语言回答。
- `actions`:智能体建议的调度动作(前端可展示/一键执行),无则为空数组。
- `citations`:RAG 命中的知识库来源,用于可解释性。

> 若需流式返回,`/agent/chat` 可用 SSE,事件名 `token`(增量文本)、`done`(结束,附完整 `actions`/`citations`)。是否流式在本文注明后统一实现。

---

## 4. WebSocket 实时推送(大屏核心)

- 连接:`ws://<host>/api/v1/ws`
- 统一消息信封:

```json
{
  "type": "traffic.snapshot",   // 主题
  "ts": 1720000000000,          // 毫秒时间戳
  "data": { }
}
```

### 主题(type)一览

| type | 频率(建议) | data 内容 | 前端用途 |
|---|---|---|---|
| `traffic.snapshot` | 1 次/秒 | `{ junctions: [...], links: [...] }` | 刷新路口状态、路段拥堵渐变红 |
| `vehicle.update` | 5~10 次/秒 | `{ vehicles: [Vehicle] }`(增量) | 车辆沿路流动 |
| `signal.update` | 相位变化时 | `{ junctionId, phaseId, phaseRemaining, mode }` | 信号灯变色 |
| `emergency.event` | 事件发生时 | `EmergencyEvent` | 应急路径高亮、沿途路口依次变绿 |
| `metrics.update` | 1 次/2~5 秒 | `Metrics` | AI 前后指标动态对比 |

**`vehicle.update` 增量约定**(避免整网全量推送):只推位置有变化的车辆;新出现车辆带完整字段,消失车辆用 `{ "id": "V1023", "removed": true }`。

**`emergency.event` 示例:**

```json
{
  "type": "emergency.event",
  "ts": 1720000005000,
  "data": {
    "eventId": "E-20260706-001",
    "vehicleType": "ambulance",
    "status": "active",
    "route": ["J1", "J3", "J7"],
    "greenWaveJunctions": ["J1", "J3"]
  }
}
```

### 连接约定

- 客户端连接后,服务端先推一帧 `traffic.snapshot` 作为初始全量。
- 心跳:客户端每 30 秒发 `{"type":"ping"}`,服务端回 `{"type":"pong"}`。
- 断线:客户端自动重连(退避重试),重连后重新拉全量。

---

## 5. 接口变更流程(五人协作)

1. 需要改字段/新增接口 → **先改本文**。
2. PR 里勾选"含接口变动",@ 受影响的前端/后端成员。
3. 群里同步一句话:改了什么、谁受影响。
4. 前端 `types/` 与后端模型同步更新,避免"字段对不上"。

> 记住:接口不是谁写谁说了算,是**本文说了算**。
