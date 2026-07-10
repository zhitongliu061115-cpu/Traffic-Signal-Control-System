// ================================================================
// AI 自适应信号控制与应急绿波数字孪生系统 — Pinia Store
// ================================================================
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type {
  SystemMode,
  SignalPhase,
  Intersection,
  Road,
  Vehicle,
  EmergencyVehicle,
  Alert,
  AlertType,
  AlertLevel,
  GlobalStatistics,
  CompareMetrics,
  CongestionTrendPoint,
  RefreshConfig,
  SimulationStatus,
  SimFrameData,
  SimVehicleState,
  SimRoadState,
  SimSignalState,
  SimIntersectionState,
  SimMetrics,
} from '@/types/traffic'
import {
  mockIntersections,
  mockRoads,
  mockVehicles,
  mockEmergencyVehicle,
  mockEmergencyRoute,
  mockStatistics,
  mockCompareMetrics,
  mockInitialAlerts,
  mockRefreshConfig,
  generateInitialTrend,
  findAssistantReply,
  mockAssistantReplies,
} from '@/mock/trafficMock'
import { fetchDashboardBootstrap } from '@/api/dashboard'
import {
  createSimulation,
  startSimulation,
  pauseSimulation,
  stopSimulation,
  fetchRoadnet,
} from '@/api/simulation'
import type { CreateSimulationResponse } from '@/types/traffic'

type DataSourceStatus = 'loading' | 'database' | 'mock'

// ---- 相位循环顺序 ----
const PHASE_CYCLE: SignalPhase[] = [
  'eastwest_straight',
  'eastwest_left',
  'northsouth_straight',
  'northsouth_left',
]

const PHASE_DURATIONS: Record<SignalPhase, number> = {
  eastwest_straight: 60,
  eastwest_left: 30,
  northsouth_straight: 50,
  northsouth_left: 25,
  all_red: 5,
}

let trendTick = 0 // 不放在 state 里避免响应式开销

export const useTrafficStore = defineStore('traffic', () => {
  // ================================================================
  // 1. State
  // ================================================================

  const systemMode = ref<SystemMode>('normal')
  const aiEnabled = ref(true)
  const selectedIntersectionId = ref<string | null>(null)
  const intersections = ref<Intersection[]>(structuredClone(mockIntersections))
  const roads = ref<Road[]>(structuredClone(mockRoads))
  const vehicles = ref<Vehicle[]>(structuredClone(mockVehicles))
  const emergencyVehicle = ref<EmergencyVehicle>(structuredClone(mockEmergencyVehicle))
  const emergencyRoute = ref<string[]>(structuredClone(mockEmergencyRoute))
  const activeGreenWaveIndex = ref(0)
  const alerts = ref<Alert[]>(structuredClone(mockInitialAlerts))
  const statistics = ref<GlobalStatistics>(structuredClone(mockStatistics))
  const compareMetrics = ref<CompareMetrics>(structuredClone(mockCompareMetrics))
  const congestionTrend = ref<CongestionTrendPoint[]>(generateInitialTrend())
  const refreshConfig = ref<RefreshConfig>({ ...mockRefreshConfig })
  const assistantReplies = ref(structuredClone(mockAssistantReplies))
  const systemLatency = ref(42)
  const mapZoom = ref(13)
  const alertIdCounter = ref(100)
  const dataSourceStatus = ref<DataSourceStatus>('mock')
  const dataSourceMessage = ref('当前显示本地演示数据')

  // ---- 仿真状态 ----
  const simulationStatus = ref<SimulationStatus>('booting')
  const simulationSid = ref<string | null>(null)
  const simulationSceneId = ref('jinan_3x4')
  const simulationSpeed = ref(1.0)
  const simulationControllerType = ref('fixed')
  const simulationSimTime = ref(0)
  const simulationFrameCount = ref(0)
  const simulationLastFrameAt = ref(0)
  const simulationVehicles = ref<SimVehicleState[]>([])
  const simulationRoads = ref<SimRoadState[]>([])
  const simulationSignals = ref<SimSignalState[]>([])
  const simulationIntersections = ref<SimIntersectionState[]>([])
  const simulationMetrics = ref<SimMetrics | null>(null)
  const simulationErrorMessage = ref<string | null>(null)

  // ================================================================
  // 2. Getters
  // ================================================================

  const selectedIntersection = computed<Intersection | undefined>(() =>
    intersections.value.find((it) => it.id === selectedIntersectionId.value),
  )

  const onlineIntersections = computed(() =>
    intersections.value.filter((it) => it.deviceStatus === 'online'),
  )

  const faultIntersections = computed(() =>
    intersections.value.filter((it) => it.deviceStatus !== 'online'),
  )

  const congestedRoads = computed(() =>
    roads.value.filter((r) => r.congestionIndex >= 75),
  )

  const emergencyVehiclesOnRoad = computed(() =>
    vehicles.value.filter((v) => v.type !== 'normal'),
  )

  const highAlerts = computed(() =>
    alerts.value.filter(
      (a) => (a.level === 'emergency' || a.level === 'error') && !a.acknowledged,
    ),
  )

  const unacknowledgedAlertCount = computed(() =>
    alerts.value.filter((a) => !a.acknowledged).length,
  )

  /** 应急路线上的路口 */
  const emergencyRouteIntersections = computed(() =>
    intersections.value.filter((it) => emergencyRoute.value.includes(it.id)),
  )

  // ================================================================
  // 3. Actions — 系统控制
  // ================================================================

  /** 启动 AI 自适应控制 */
  function startAiControl(): void {
    aiEnabled.value = true
    // AI 自适应控制已启动
  }

  /** 暂停 AI 控制（保留当前配时） */
  function pauseAiControl(): void {
    aiEnabled.value = false
    // AI 自适应控制已暂停
  }

  /** 手动切换指定路口的信号相位 */
  function switchPhase(intersectionId: string): void {
    const it = intersections.value.find((i) => i.id === intersectionId)
    if (!it) return

    const currentIdx = PHASE_CYCLE.indexOf(it.currentPhase as SignalPhase)
    const nextIdx = currentIdx === -1 ? 0 : (currentIdx + 1) % PHASE_CYCLE.length
    it.currentPhase = PHASE_CYCLE[nextIdx]!
    it.greenRemain = PHASE_DURATIONS[PHASE_CYCLE[nextIdx]!]!
    // 相位切换完成
  }

  /** 选中路口 */
  function selectIntersection(id: string | null): void {
    selectedIntersectionId.value = id
  }

  /** 更新地图缩放级别（MapLibre 同步） */
  function updateMapZoom(zoom: number): void {
    mapZoom.value = zoom
  }

  // ================================================================
  // 4. Actions — 应急绿波
  // ================================================================

  /** 模拟应急车辆出发 */
  function simulateEmergencyVehicle(): void {
    const ev = emergencyVehicle.value
    ev.greenWaveActive = true
    const startNode = emergencyRoute.value[0]

    if (startNode) {
      ev.currentIntersectionId = startNode
    }

    // 将 E001 车辆类型设为救护车并放置于应急路线首段
    const amber = vehicles.value.find((v) => v.id === emergencyVehicle.value.id)
    if (amber) {
      amber.type = 'ambulance'
      amber.speed = 62
      amber.progress = 0.05
      // 找到从 startNode 出发的道路
      const routeRoad = roads.value.find(
        (r) => r.from === emergencyRoute.value[0] && r.to === emergencyRoute.value[1],
      )
      if (routeRoad) {
        amber.roadId = routeRoad.id
      }
    }

    activeGreenWaveIndex.value = 0
    systemMode.value = 'emergency'
    generateMockAlert(
      'emergency_vehicle_enter',
      'emergency',
      '应急绿波通道已激活',
      `${emergencyRoute.value.join(' → ')}`,
      emergencyRoute.value[0],
    )
    // 应急车辆已出发，绿波通道激活
  }

  /** 启动应急绿波 */
  function startEmergencyGreenWave(): void {
    systemMode.value = 'emergency'
    activeGreenWaveIndex.value = 0
    emergencyVehicle.value.greenWaveActive = true
    // 应急绿波模式启用
  }

  /** 恢复正常模式 */
  function restoreNormalMode(): void {
    systemMode.value = 'normal'
    emergencyVehicle.value.greenWaveActive = false
    emergencyVehicle.value.eta = 8
    activeGreenWaveIndex.value = -1

    // 将应急车辆还原为普通车辆
    const ev = vehicles.value.find((v) => v.id === emergencyVehicle.value.id)
    if (ev) {
      ev.type = 'normal'
      ev.speed = 30 + Math.random() * 30
    }

    // 已恢复正常信号模式，应急车辆已清除
  }

  // ================================================================
  // 5. Actions — 数据刷新
  // ================================================================

  /**
   * 高频更新：仅推进车辆位置（200ms 间隔）
   * 轻量级，避免每帧刷新全部指标
   */
  function updateVehiclePositions(deltaMs: number = 200): void {
    for (const v of vehicles.value) {
      const step = (v.speed / 3600) * (deltaMs / 1000) * (1 + (Math.random() - 0.5) * 0.4)
      v.progress = Math.min(1, v.progress + step)

      if (v.progress >= 1) {
        v.progress = Math.random() * 0.15
        v.speed =
          v.type === 'normal'
            ? 25 + Math.random() * 40
            : 50 + Math.random() * 30
        const randomRoad = roads.value[Math.floor(Math.random() * roads.value.length)]
        if (randomRoad) {
          v.roadId = randomRoad.id
        }
      }

      // 应急车辆靠近目标时推进绿波索引
      if (
        v.id === emergencyVehicle.value.id &&
        v.progress > 0.6 &&
        activeGreenWaveIndex.value < emergencyRoute.value.length - 1
      ) {
        activeGreenWaveIndex.value++
      }
    }
  }

  /**
   * 中频更新：道路指数、信号灯、统计指标（2s 间隔）
   */
  function updateTrafficIndicators(deltaMs: number = 2000): void {
    // ---- 道路拥堵波动 ----
    for (const r of roads.value) {
      const drift = (Math.random() - 0.5) * 4
      r.congestionIndex = Math.max(10, Math.min(98, r.congestionIndex + drift))
      r.speed = Math.max(15, Math.min(70, r.speed + (Math.random() - 0.5) * 3))
      r.queueLength = Math.max(20, Math.min(300, r.queueLength + (Math.random() - 0.5) * 20))
      r.flow = Math.max(600, Math.min(3000, r.flow + (Math.random() - 0.5) * 80))
    }

    // ---- 信号灯倒计时 ----
    for (const it of intersections.value) {
      if (it.deviceStatus === 'fault' || it.deviceStatus === 'offline') {
        it.greenRemain = 0
        continue
      }

      it.greenRemain = Math.max(0, it.greenRemain - deltaMs / 1000)

      if (it.greenRemain <= 0) {
        if (it.currentPhase === 'all_red') {
          it.currentPhase = 'eastwest_straight'
          it.greenRemain = PHASE_DURATIONS.eastwest_straight
        } else {
          const curIdx = PHASE_CYCLE.indexOf(it.currentPhase as SignalPhase)
          if (curIdx >= 0) {
            const nextPhase = PHASE_CYCLE[(curIdx + 1) % PHASE_CYCLE.length]!
            it.currentPhase = nextPhase
            it.greenRemain = PHASE_DURATIONS[nextPhase]
          }
        }
      }

      it.queueLength = Math.max(2, Math.min(40, it.queueLength + Math.round((Math.random() - 0.5) * 3)))
      it.averageDelay = Math.max(8, Math.min(90, it.averageDelay + (Math.random() - 0.5) * 5))
      it.congestionIndex = Math.max(10, Math.min(95, it.congestionIndex + (Math.random() - 0.5) * 4))
    }

    // ---- 统计指标 ----
    const s = statistics.value
    const minFlow = dataSourceStatus.value === 'database' ? 6500 : 2500
    const maxFlow = dataSourceStatus.value === 'database' ? 12000 : 5500
    s.totalFlow = Math.max(minFlow, Math.min(maxFlow, s.totalFlow + Math.round((Math.random() - 0.5) * 120)))
    s.averageSpeed = Math.max(30, Math.min(55, +(s.averageSpeed + (Math.random() - 0.5) * 2).toFixed(1)))
    s.averageWaitTime = Math.max(20, Math.min(50, +(s.averageWaitTime + (Math.random() - 0.5) * 3).toFixed(1)))
    s.congestionIndex = Math.max(30, Math.min(80, +(s.congestionIndex + (Math.random() - 0.5) * 4).toFixed(1)))
    s.congestedRoadCount = congestedRoads.value.length
    s.optimizedIntersectionCount = onlineIntersections.value.length
    s.emergencyVehicleCount = emergencyVehiclesOnRoad.value.length
    s.deviceOnlineRate = +(
      (onlineIntersections.value.length / intersections.value.length) *
      100
    ).toFixed(1)
    s.greenWaveCount = systemMode.value === 'emergency' ? 1 : 0

    // ---- 系统延迟 ----
    updateSystemLatency()

    // ---- 自动告警检测（带去重） ----
    checkAndGenerateAlerts()
  }

  // ---- 自动告警去重 Map（key = type:id, value = 上次生成时间戳） ----
  const alertCooldown = new Map<string, number>()
  const ALERT_COOLDOWN_MS = 120_000 // 同类型+同路口 2 分钟内不重复

  /** 扫描道路/路口状态，自动生成告警（带去重） */
  function checkAndGenerateAlerts(): void {
    const now = Date.now()

    // 道路拥堵 > 85 → 异常拥堵告警
    for (const r of roads.value) {
      if (r.congestionIndex > 85) {
        const key = `abnormal_congestion:${r.id}`
        const last = alertCooldown.get(key)
        if (!last || now - last > ALERT_COOLDOWN_MS) {
          alertCooldown.set(key, now)
          generateMockAlert(
            'abnormal_congestion',
            'warning',
            `${r.name} 拥堵指数达 ${Math.round(r.congestionIndex)}，超阈值`,
            `${r.name} · 流量 ${r.flow} 辆/h`,
          )
        }
      }
    }

    // 设备离线 → 设备离线告警
    for (const it of intersections.value) {
      if (it.deviceStatus === 'offline') {
        const key = `device_offline:${it.id}`
        const last = alertCooldown.get(key)
        if (!last || now - last > ALERT_COOLDOWN_MS) {
          alertCooldown.set(key, now)
          generateMockAlert(
            'device_offline',
            'error',
            `${it.name} 信号控制器离线`,
            `离线 · 最后在线时间未知`,
            it.id,
          )
        }
      }

      // 设备故障 → 设备故障告警
      if (it.deviceStatus === 'fault') {
        const key = `device_fault:${it.id}`
        const last = alertCooldown.get(key)
        if (!last || now - last > ALERT_COOLDOWN_MS) {
          alertCooldown.set(key, now)
          generateMockAlert(
            'device_fault',
            'error',
            `${it.name} 信号控制器故障，AI 已降级`,
            `故障 · 需立即派单巡检`,
            it.id,
          )
        }
      }
    }

    // 清理超过 5 分钟的旧记录
    for (const [key, ts] of alertCooldown) {
      if (now - ts > 300_000) alertCooldown.delete(key)
    }
  }

  /** 核心定时更新：推进所有模拟数据一帧 */
  function updateMockTraffic(_deltaMs: number = 1000): void {
    // ---- 5a. 车辆 progress 前进 ----
    for (const v of vehicles.value) {
      // progress 单步增量 = speed / 3600 * deltaS ≈ km/h → 每帧比例
      const step = (v.speed / 3600) * (_deltaMs / 1000) * (1 + (Math.random() - 0.5) * 0.4)
      v.progress = Math.min(1, v.progress + step)

      // 到达终点：重置并随机分配到另一条路
      if (v.progress >= 1) {
        v.progress = Math.random() * 0.15
        v.speed = v.type === 'normal'
          ? 25 + Math.random() * 40
          : 50 + Math.random() * 30
        const randomRoad = roads.value[Math.floor(Math.random() * roads.value.length)]
        if (randomRoad) {
          v.roadId = randomRoad.id
        }
      }

      // 应急车辆靠近目标路口时推进绿波索引
      if (
        v.id === emergencyVehicle.value.id &&
        v.progress > 0.6 &&
        activeGreenWaveIndex.value < emergencyRoute.value.length - 1
      ) {
        activeGreenWaveIndex.value++
      }
    }

    // ---- 5b. 道路拥堵指数轻微波动 ----
    for (const r of roads.value) {
      const drift = (Math.random() - 0.5) * 4
      r.congestionIndex = Math.max(10, Math.min(98, r.congestionIndex + drift))
      r.speed = Math.max(15, Math.min(70, r.speed + (Math.random() - 0.5) * 3))
      r.queueLength = Math.max(20, Math.min(300, r.queueLength + (Math.random() - 0.5) * 20))
      r.flow = Math.max(600, Math.min(3000, r.flow + (Math.random() - 0.5) * 80))
    }

    // ---- 5c. 信号灯倒计时（受 AI 控制影响） ----
    for (const it of intersections.value) {
      if (it.deviceStatus === 'fault' || it.deviceStatus === 'offline') {
        // 故障设备：绿灯归零，不推进倒计时
        it.greenRemain = 0
        continue
      }

      it.greenRemain = Math.max(0, it.greenRemain - (_deltaMs / 1000))

      // 倒计时归零 → 切换相位
      if (it.greenRemain <= 0) {
        if (it.currentPhase === 'all_red') {
          // 全红结束 → 进入东西直行
          it.currentPhase = 'eastwest_straight'
          it.greenRemain = PHASE_DURATIONS.eastwest_straight
        } else {
          const curIdx = PHASE_CYCLE.indexOf(it.currentPhase as SignalPhase)
          if (curIdx >= 0 && aiEnabled.value) {
            const nextPhase = PHASE_CYCLE[(curIdx + 1) % PHASE_CYCLE.length]!
            it.currentPhase = nextPhase
            it.greenRemain = PHASE_DURATIONS[nextPhase]
          } else if (curIdx >= 0) {
            // AI 关闭时也正常循环
            const nextPhase = PHASE_CYCLE[(curIdx + 1) % PHASE_CYCLE.length]!
            it.currentPhase = nextPhase
            it.greenRemain = PHASE_DURATIONS[nextPhase]
          }
        }
      }

      // 拥堵 & 排队随机波动
      it.queueLength = Math.max(2, Math.min(40, it.queueLength + Math.round((Math.random() - 0.5) * 3)))
      it.averageDelay = Math.max(8, Math.min(90, it.averageDelay + (Math.random() - 0.5) * 5))
      it.congestionIndex = Math.max(10, Math.min(95, it.congestionIndex + (Math.random() - 0.5) * 4))
    }

    // ---- 5d. 统计指标轻微变化 ----
    const s = statistics.value
    const minFlow = dataSourceStatus.value === 'database' ? 6500 : 2500
    const maxFlow = dataSourceStatus.value === 'database' ? 12000 : 5500
    s.totalFlow = Math.max(minFlow, Math.min(maxFlow, s.totalFlow + Math.round((Math.random() - 0.5) * 120)))
    s.averageSpeed = Math.max(30, Math.min(55, +(s.averageSpeed + (Math.random() - 0.5) * 2).toFixed(1)))
    s.averageWaitTime = Math.max(20, Math.min(50, +(s.averageWaitTime + (Math.random() - 0.5) * 3).toFixed(1)))
    s.congestionIndex = Math.max(30, Math.min(80, +(s.congestionIndex + (Math.random() - 0.5) * 4).toFixed(1)))
    s.congestedRoadCount = congestedRoads.value.length
    s.optimizedIntersectionCount = onlineIntersections.value.length
    s.emergencyVehicleCount = emergencyVehiclesOnRoad.value.length
    s.deviceOnlineRate = +((onlineIntersections.value.length / intersections.value.length) * 100).toFixed(1)
    s.greenWaveCount = systemMode.value === 'emergency' ? 1 : 0

    // ---- 5e. 系统延迟 ----
    updateSystemLatency()

    // ---- 5f. 拥堵趋势 ----
    trendTick++
    if (trendTick % 60 === 0) {
      addCongestionTrendPoint()
    }
  }

  /** 生成一条模拟告警 */
  function generateMockAlert(
    type: AlertType,
    level: AlertLevel,
    title: string,
    location: string,
    intersectionId?: string,
  ): void {
    const now = new Date()
    const time = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')} ${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`

    alertIdCounter.value++
    const alert: Alert = {
      id: `ALT${String(alertIdCounter.value).padStart(3, '0')}`,
      type,
      level,
      title,
      location,
      time,
      intersectionId,
      acknowledged: false,
    }

    alerts.value.unshift(alert)

    // 保留最新 50 条
    if (alerts.value.length > 50) {
      alerts.value = alerts.value.slice(0, 50)
    }

    statistics.value.todayAlertCount++
    // 新告警已生成
  }

  /** 确认（消隐）一条告警 */
  function acknowledgeAlert(alertId: string): void {
    const a = alerts.value.find((x) => x.id === alertId)
    if (a) {
      a.acknowledged = true
    }
  }

  /** 更新系统延迟（模拟网络波动） */
  function updateSystemLatency(): void {
    const jitter = (Math.random() - 0.5) * 6
    systemLatency.value = Math.max(25, Math.min(80, systemLatency.value + jitter))
  }

  /** 向拥堵趋势追加一个数据点 */
  function addCongestionTrendPoint(): void {
    const now = new Date()
    const hh = String(now.getHours()).padStart(2, '0')
    const mm = String(now.getMinutes()).padStart(2, '0')
    const point: CongestionTrendPoint = {
      time: `${hh}:${mm}`,
      value: statistics.value.congestionIndex,
    }

    congestionTrend.value.push(point)

    // 保留最新 120 个点（2 小时）
    if (congestionTrend.value.length > 120) {
      congestionTrend.value = congestionTrend.value.slice(-120)
    }
  }

  /** 智能体问答 */
  function askAssistant(input: string): string {
    return findAssistantReply(input, assistantReplies.value)
  }

  async function loadDashboardData(): Promise<boolean> {
    dataSourceStatus.value = 'loading'
    dataSourceMessage.value = '正在连接后端数据库'

    try {
      const data = await fetchDashboardBootstrap()
      intersections.value = data.intersections
      roads.value = data.roads
      vehicles.value = data.vehicles
      emergencyVehicle.value = data.emergencyVehicle
      emergencyRoute.value = data.emergencyRoute
      alerts.value = data.alerts
      statistics.value = data.statistics
      compareMetrics.value = data.compareMetrics
      congestionTrend.value = data.congestionTrend
      assistantReplies.value = data.assistantReplies
      dataSourceStatus.value = 'database'
      dataSourceMessage.value = '已连接后端数据库，当前显示数据库数据'
      console.log('[TrafficStore] dashboard data loaded from backend')
      return true
    } catch (error) {
      dataSourceStatus.value = 'mock'
      dataSourceMessage.value = '后端接口不可用，当前显示本地演示数据'
      console.warn('[TrafficStore] backend dashboard data unavailable, using local mock', error)
      return false
    }
  }

  // ================================================================
  // 6. Actions — 仿真管理
  // ================================================================

  /** 处理从 WebSocket/API 收到的仿真帧数据 */
  function handleSimFrame(frame: SimFrameData): void {
    simulationSimTime.value = frame.simTime
    simulationVehicles.value = frame.vehicles ?? []
    simulationRoads.value = frame.roads ?? []
    simulationSignals.value = frame.signals ?? []
    simulationIntersections.value = frame.intersections ?? []
    simulationMetrics.value = frame.metrics ?? null
    simulationFrameCount.value++
    simulationLastFrameAt.value = Date.now()

    // 用仿真数据同步刷新前端路口/道路/车辆状态
    applySimFrameToTrafficData(frame)

    // 检查是否有帧超时（仿真运行中但 3.5s 没收到新帧）
    if (
      simulationStatus.value === 'running' &&
      simulationLastFrameAt.value > 0 &&
      Date.now() - simulationLastFrameAt.value > 3500
    ) {
      simulationErrorMessage.value = `等待新帧中… last frame ${simulationFrameCount.value}`
    } else {
      simulationErrorMessage.value = null
    }
  }

  /** 将仿真帧数据同步到现有的 traffic 数据结构（渐进式替换 mock） */
  function applySimFrameToTrafficData(frame: SimFrameData): void {
    // ---- 信号灯 → 路口相位 ----
    for (const sig of frame.signals) {
      const it = intersections.value.find((i) => i.id === sig.intersectionId)
      if (!it) continue

      // 映射 phaseCode → SignalPhase
      // CFRP 1.0: ETWT=东西直行, NTST=南北直行, ELWL=东西左转, NLSL=南北左转
      const phaseMap: Record<string, SignalPhase> = {
        ETWT: 'eastwest_straight', ew_straight: 'eastwest_straight',
        NTST: 'northsouth_straight', ns_straight: 'northsouth_straight',
        ELWL: 'eastwest_left', ew_left: 'eastwest_left',
        NLSL: 'northsouth_left', ns_left: 'northsouth_left',
        all_red: 'all_red',
      }
      it.currentPhase = phaseMap[sig.phaseCode] ?? it.currentPhase
      // 标记设备在线
      it.deviceStatus = 'online'
    }

    // ---- 路口排队/延误 ----
    for (const istate of frame.intersections) {
      const it = intersections.value.find((i) => i.id === istate.id)
      if (!it) continue
      it.queueLength = istate.queueCount
      it.averageDelay = Math.round(istate.avgWait)
      it.congestionIndex = istate.level === 'jammed' ? 90
        : istate.level === 'slow' ? 55
        : 25
    }

    // ---- 道路状态 ----
    for (const roadState of frame.roads) {
      const r = roads.value.find((rd) => rd.id === roadState.id)
      if (!r) continue
      r.flow = roadState.vehicleCount * 60 // 粗略换算 veh/h
      r.speed = roadState.avgSpeed
      r.queueLength = roadState.queueCount
      r.congestionIndex =
        roadState.level === 'jammed' ? 90
        : roadState.level === 'slow' ? 55
        : 20
    }

    // ---- 车辆：将 SimVehicleState[] 注入到 vehicles[] ----
    if (frame.vehicles.length > 0) {
      const newVehicles: Vehicle[] = frame.vehicles.map((sv) => ({
        id: sv.id,
        roadId: sv.roadId,
        progress: 0,
        speed: sv.speed,
        type: 'normal' as const,
        laneIndex: sv.lane,
      }))
      const specialVehicles = vehicles.value.filter((v) => v.type !== 'normal')
      vehicles.value = [...newVehicles, ...specialVehicles]
    }

    // ---- 全局统计 ----
    if (frame.metrics) {
      statistics.value.totalFlow = frame.metrics.throughput ?? statistics.value.totalFlow
      statistics.value.averageSpeed = frame.metrics.avgSpeed ?? statistics.value.averageSpeed
      statistics.value.averageWaitTime = frame.metrics.avgWait ?? statistics.value.averageWaitTime
    }
  }

  /** 创建并初始化仿真会话 */
  async function initSimulationSession(): Promise<CreateSimulationResponse | null> {
    simulationStatus.value = 'booting'
    simulationErrorMessage.value = null

    try {
      const result = await createSimulation({
        sceneId: simulationSceneId.value,
        speed: simulationSpeed.value,
        controllerType: simulationControllerType.value,
      })

      simulationSid.value = result.sid
      simulationStatus.value = 'paused'
      console.log('[TrafficStore] simulation created', result)
      return result
    } catch (err) {
      simulationStatus.value = 'finished'
      simulationErrorMessage.value = `创建仿真会话失败: ${err instanceof Error ? err.message : String(err)}`
      console.error('[TrafficStore] simulation creation failed', err)
      return null
    }
  }

  /** 启动仿真（帧开始推送） */
  async function resumeSimulation(): Promise<void> {
    if (!simulationSid.value || simulationStatus.value !== 'paused') return
    try {
      await startSimulation(simulationSid.value)
      simulationStatus.value = 'running'
      simulationErrorMessage.value = null
      console.log('[TrafficStore] simulation started')
    } catch (err) {
      simulationErrorMessage.value = `启动仿真失败: ${err instanceof Error ? err.message : String(err)}`
      throw err
    }
  }

  /** 暂停仿真 */
  async function pauseSimulationSession(): Promise<void> {
    if (!simulationSid.value || simulationStatus.value !== 'running') return
    try {
      await pauseSimulation(simulationSid.value)
      simulationStatus.value = 'paused'
      console.log('[TrafficStore] simulation paused')
    } catch (err) {
      simulationErrorMessage.value = `暂停仿真失败: ${err instanceof Error ? err.message : String(err)}`
      throw err
    }
  }

  /** 停止仿真 */
  async function stopSimulationSession(): Promise<void> {
    if (!simulationSid.value || simulationStatus.value === 'finished') return
    try {
      await stopSimulation(simulationSid.value)
      simulationStatus.value = 'finished'
      console.log('[TrafficStore] simulation stopped')
    } catch (err) {
      simulationErrorMessage.value = `停止仿真失败: ${err instanceof Error ? err.message : String(err)}`
      throw err
    }
  }

  /** 重置仿真状态（不操作后端） */
  function resetSimulationState(): void {
    simulationStatus.value = 'booting'
    simulationSid.value = null
    simulationSimTime.value = 0
    simulationFrameCount.value = 0
    simulationLastFrameAt.value = 0
    simulationVehicles.value = []
    simulationRoads.value = []
    simulationSignals.value = []
    simulationIntersections.value = []
    simulationMetrics.value = null
    simulationErrorMessage.value = null
  }

  /** 重置所有数据到初始状态 */
  function resetAllData(): void {
    intersections.value = structuredClone(mockIntersections)
    roads.value = structuredClone(mockRoads)
    vehicles.value = structuredClone(mockVehicles)
    emergencyVehicle.value = structuredClone(mockEmergencyVehicle)
    emergencyRoute.value = structuredClone(mockEmergencyRoute)
    alerts.value = structuredClone(mockInitialAlerts)
    statistics.value = structuredClone(mockStatistics)
    compareMetrics.value = structuredClone(mockCompareMetrics)
    congestionTrend.value = generateInitialTrend()
    systemMode.value = 'normal'
    aiEnabled.value = true
    selectedIntersectionId.value = null
    activeGreenWaveIndex.value = 0
    systemLatency.value = 42
    alertIdCounter.value = 100
    assistantReplies.value = structuredClone(mockAssistantReplies)
    dataSourceStatus.value = 'mock'
    dataSourceMessage.value = '当前显示本地演示数据'
    trendTick = 0
    // 数据已全部重置
  }

  // ================================================================
  // 6. 导出
  // ================================================================

  return {
    // state
    systemMode,
    aiEnabled,
    selectedIntersectionId,
    intersections,
    roads,
    vehicles,
    emergencyVehicle,
    emergencyRoute,
    activeGreenWaveIndex,
    alerts,
    statistics,
    compareMetrics,
    congestionTrend,
    refreshConfig,
    systemLatency,
    mapZoom,
    alertIdCounter,
    dataSourceStatus,
    dataSourceMessage,

    // simulation state
    simulationStatus,
    simulationSid,
    simulationSceneId,
    simulationSpeed,
    simulationControllerType,
    simulationSimTime,
    simulationFrameCount,
    simulationVehicles,
    simulationRoads,
    simulationSignals,
    simulationIntersections,
    simulationMetrics,
    simulationErrorMessage,

    // getters
    selectedIntersection,
    onlineIntersections,
    faultIntersections,
    congestedRoads,
    emergencyVehiclesOnRoad,
    highAlerts,
    unacknowledgedAlertCount,
    emergencyRouteIntersections,

    // actions
    startAiControl,
    pauseAiControl,
    switchPhase,
    selectIntersection,
    updateMapZoom,
    simulateEmergencyVehicle,
    startEmergencyGreenWave,
    restoreNormalMode,
    updateVehiclePositions,
    updateTrafficIndicators,
    updateMockTraffic,
    checkAndGenerateAlerts,
    generateMockAlert,
    acknowledgeAlert,
    updateSystemLatency,
    addCongestionTrendPoint,
    askAssistant,
    loadDashboardData,
    resetAllData,

    // simulation actions
    handleSimFrame,
    applySimFrameToTrafficData,
    initSimulationSession,
    resumeSimulation,
    pauseSimulationSession,
    stopSimulationSession,
    resetSimulationState,
  }
})
