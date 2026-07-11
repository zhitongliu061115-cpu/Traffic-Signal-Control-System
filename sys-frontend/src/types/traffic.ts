// ================================================================
// AI 自适应信号控制与应急绿波数字孪生系统 — 类型定义
// ================================================================

// ---- 字面量联合类型 ----

/** 系统运行模式 */
export type SystemMode = 'normal' | 'emergency'

/** 信号机设备状态 */
export type DeviceStatus = 'online' | 'offline' | 'fault'

/** 车辆类型 */
export type VehicleType = 'normal' | 'ambulance' | 'firetruck'

/** 告警级别 */
export type AlertLevel = 'emergency' | 'error' | 'warning' | 'info'

/** 告警类别 */
export type AlertType =
  | 'abnormal_congestion'
  | 'device_offline'
  | 'device_fault'
  | 'control_failure'
  | 'emergency_event'
  | 'emergency_vehicle_enter'
  | 'green_wave_start'
  | 'green_wave_restore'
  | 'ai_control_start'
  | 'ai_control_pause'

/** 信号相位标识 */
export type SignalPhase =
  | 'eastwest_straight'
  | 'eastwest_left'
  | 'northsouth_straight'
  | 'northsouth_left'
  | 'all_red'

// ---- 实体接口 ----

/** 路口 / 信号控制节点 */
export interface Intersection {
  id: string
  name: string
  /** 屏幕横坐标 (px / 归一化 0-1) */
  x: number
  /** 屏幕纵坐标 (px / 归一化 0-1) */
  y: number
  /** 经度（MapLibre 地图用） */
  lng: number
  /** 纬度（MapLibre 地图用） */
  lat: number
  /** 4×3 网格行号 1..3 */
  row: number
  /** 4×3 网格列号 1..4 */
  col: number
  /** 当前信号相位 */
  currentPhase: SignalPhase
  /** 当前相位剩余绿灯时长 (秒) */
  greenRemain: number
  /** 当前排队车辆数 */
  queueLength: number
  /** 平均延误 (秒) */
  averageDelay: number
  /** 拥堵指数 0-100，越高越拥堵 */
  congestionIndex: number
  /** 信号机设备状态 */
  deviceStatus: DeviceStatus
  /** 关联的道路 ID 列表（用于点击红绿灯时高亮对应道路） */
  roadIds: string[]
}

/** 道路 / 路段 */
export interface Road {
  id: string
  /** 起始路口 ID */
  from: string
  /** 终点路口 ID */
  to: string
  name: string
  /** 当前流量 (辆/小时) */
  flow: number
  /** 平均车速 (km/h) */
  speed: number
  /** 排队长度 (米) */
  queueLength: number
  /** 拥堵指数 0-100 */
  congestionIndex: number
  /** 车道数量（≥16 zoom 时用于三车道渲染） */
  laneCount: number
  /** 通行方向 */
  direction: 'two-way' | 'one-way'
  /** 真实道路中心线经纬度路径 [lng, lat][], 至少含 from→to 两点 */
  path: [number, number][]
}

/** 车辆（模拟浮动车） */
export interface Vehicle {
  id: string
  /** 所在道路 ID */
  roadId: string
  /** 行驶进度 0-1，0 = 起点，1 = 终点（mock 模式 / 降级方案） */
  progress: number
  /** 当前速度 (km/h) */
  speed: number
  type: VehicleType
  /** 车道索引 0..laneCount-1（用于横向偏移到对应车道） */
  laneIndex: number
  /** 后端推送的精确坐标 x（CityFlow 坐标系，来自 SimVehicleState） */
  x?: number
  /** 后端推送的精确坐标 y（CityFlow 坐标系，来自 SimVehicleState） */
  y?: number
  /** 后端推送的车头朝向角度（度，来自 SimVehicleState） */
  angle?: number
}

/** 应急车辆（继承普通车辆 + 应急元信息） */
export interface EmergencyVehicle {
  id: string
  type: 'ambulance' | 'firetruck'
  /** 当前所在路口 ID */
  currentIntersectionId: string
  /** 目的地方向描述 */
  destination: string
  /** 已激活绿波 */
  greenWaveActive: boolean
  /** 预计到达时间 (分钟) */
  eta: number
}

// ---- 应急调度 ----

/** 车辆类型（调度用，不含 normal） */
export type EmergencyEvType = 'ambulance' | 'firetruck'

/** 应急调度请求（对齐后端 EvDispatchRequest，只需路口 ID） */
export interface DispatchRequest {
  /** 起点路口 ID（必填） */
  startIntersection: string
  /** 终点路口 ID（必填） */
  endIntersection: string
  /** 车辆 ID（可选，不填后端自动生成） */
  evId?: string
  /** 车辆类型 */
  evType?: EmergencyEvType
  /** 优先级 1-5，1 最高 */
  priority?: number
}

/** 应急调度响应 */
export interface DispatchResponse {
  sid: string
  evId: string
  evType: string
  priority: number
  route: string[]
  routeRoads: string[]
  estimatedTravelTime: number
}

/** 应急调度表单数据（本地组件用） */
export interface DispatchFormData {
  startIntersectionId: string
  endIntersectionId: string
  evType: EmergencyEvType
  priority: number
}

/** 应急调度结果（展示用） */
export interface DispatchResult {
  evId: string
  route: string[]
  routeRoads: string[]
  estimatedTravelTime: number
  startName: string
  endName: string
}

/** 告警记录 */
export interface Alert {
  id: string
  type: AlertType
  level: AlertLevel
  title: string
  location: string
  time: string
  /** 关联路口 ID（可选） */
  intersectionId?: string
  /** 是否已确认 */
  acknowledged: boolean
}

// ---- 聚合统计 ----

/** 全局统计指标 */
export interface GlobalStatistics {
  /** 总车流量 (辆/小时) */
  totalFlow: number
  /** 路网平均车速 (km/h) */
  averageSpeed: number
  /** 平均等待时间 (秒) */
  averageWaitTime: number
  /** 路网综合拥堵指数 0-100 */
  congestionIndex: number
  /** 拥堵路段数 */
  congestedRoadCount: number
  /** 已优化路口数 */
  optimizedIntersectionCount: number
  /** 当前应急车辆数 */
  emergencyVehicleCount: number
  /** 设备在线率 (%) */
  deviceOnlineRate: number
  /** 今日告警总数 */
  todayAlertCount: number
  /** 当前活跃绿波通道数 */
  greenWaveCount: number
}

// ---- AI 对比指标 ----

/** 单组对比指标 */
export interface CompareMetricItem {
  name: string
  traditional: number
  ai: number
  unit: string
  /** 'lower' = 越低越好, 'higher' = 越高越好 */
  direction: 'lower' | 'higher'
}

/** 控制效果对比（传统 vs AI） */
export interface CompareMetrics {
  averageWaitTime: CompareMetricItem
  averageSpeed: CompareMetricItem
  queueLength: CompareMetricItem
  emergencyPassTime: CompareMetricItem
}

// ---- 趋势 & 配置 ----

/** 拥堵趋势数据点 */
export interface CongestionTrendPoint {
  time: string
  value: number
}

/** 数据刷新配置 */
export interface RefreshConfig {
  /** 刷新间隔 (毫秒) */
  intervalMs: number
  /** 是否启用自动刷新 */
  autoRefresh: boolean
}

// ---- 智能体 ----

/** 智能体回复映射 */
export type AssistantReplies = Record<string, string>

// ---- 相位信息（用于展示） ----

/** 相位中文映射 */
export const PHASE_LABELS: Record<SignalPhase, string> = {
  eastwest_straight: '东西直行',
  eastwest_left: '东西左转',
  northsouth_straight: '南北直行',
  northsouth_left: '南北左转',
  all_red: '全向红灯',
}

/** 设备状态中文映射 */
export const DEVICE_STATUS_LABELS: Record<DeviceStatus, string> = {
  online: '在线',
  offline: '离线',
  fault: '故障',
}

/** 告警类型中文映射 */
export const ALERT_TYPE_LABELS: Record<AlertType, string> = {
  abnormal_congestion: '异常拥堵',
  device_offline: '设备离线',
  device_fault: '设备故障',
  control_failure: '控制失败',
  emergency_event: '应急事件',
  emergency_vehicle_enter: '应急车辆进入',
  green_wave_start: '绿波启动',
  green_wave_restore: '恢复普通控制',
  ai_control_start: 'AI 启动',
  ai_control_pause: 'AI 暂停',
}

/** 告警等级中文映射 */
export const ALERT_LEVEL_LABELS: Record<AlertLevel, string> = {
  emergency: '应急',
  error: '严重',
  warning: '预警',
  info: '提示',
}

// ================================================================
// 仿真数据类型（对接后端 WebSocket SimFrameData）
// ================================================================

/** 仿真会话状态 */
export type SimulationStatus =
  | 'booting'
  | 'paused'
  | 'running'
  | 'finished'

/** 单帧车辆状态（后端 VehicleStateDto） */
export interface SimVehicleState {
  id: string
  roadId: string
  lane: number
  x: number
  y: number
  angle: number
  speed: number
}

/** 单帧道路状态（后端 RoadStateDto） */
export interface SimRoadState {
  id: string
  vehicleCount: number
  queueCount: number
  avgSpeed: number
  level: 'free' | 'slow' | 'jammed' | 'unknown'
}

/** 单帧路口状态（后端 IntersectionStateDto） */
export interface SimIntersectionState {
  id: string
  queueCount: number
  avgWait: number
  level: string
}

/** 单帧信号状态（后端 SignalStateDto） */
export interface SimSignalState {
  intersectionId: string
  phaseIndex: number
  phaseCode: string
}

/** 单帧聚合指标（后端 SimulationMetricsDto） */
export interface SimMetrics {
  vehicleCount: number
  queueCount: number
  avgSpeed: number
  avgWait: number
  throughput: number
}

/** 单帧仿真数据（后端 SimFrameData） */
export interface SimFrameData {
  simTime: number
  status?: 'running' | 'finished'
  vehicles: SimVehicleState[]
  roads: SimRoadState[]
  intersections: SimIntersectionState[]
  signals: SimSignalState[]
  metrics: SimMetrics
}

/** WebSocket 消息信封（后端 WsMessage<T>） */
export interface WsMessage<T = SimFrameData> {
  v: string
  type: string
  sid: string
  seq: number
  simTime: number
  sentAt: string
  data: T
}

/** AI 控制决策（后端 WebSocket control.decision） */
export interface ControlDecision {
  intersectionId: string
  controllerType: string
  phaseIndex: number
  phaseCode: string
  durationSec: number
  confidence: number
  reason: string
}

/** 创建仿真请求 */
export interface CreateSimulationRequest {
  sceneId: string
  speed?: number
  controllerType?: string
}

/** 创建仿真响应 */
export interface CreateSimulationResponse {
  sid: string
  sceneId: string
  status: string
  controllerType: string
}

/** 路网数据（后端 RoadnetResponse） */
export interface SimRoadnetResponse {
  sceneId: string
  intersections: SimRoadnetIntersection[]
  roads: SimRoadnetRoad[]
  roadLinks: SimRoadLink[]
  phases: SimPhase[]
}

export interface SimRoadnetIntersection {
  id: string
  x: number
  y: number
  virtual: boolean
}

export interface SimRoadnetRoad {
  id: string
  from: string
  to: string
  points: Array<{ x: number; y: number }>
  laneCount: number
}

export interface SimRoadLink {
  intersectionId: string
  index: number
  fromRoadId: string
  toRoadId: string
  type: 'go_straight' | 'turn_left' | 'turn_right'
}

export interface SimPhase {
  intersectionId: string
  phaseIndex: number
  phaseCode: string
  roadLinkIndexes: number[]
}
