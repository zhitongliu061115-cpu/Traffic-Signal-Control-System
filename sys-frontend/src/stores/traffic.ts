// =========================================================
// AI 自适应信号控制与应急绿波数字孪生系统 — Pinia Store
// =========================================================
import { defineStore } from 'pinia'
import { ref, computed, shallowRef } from 'vue'
import { signalRemainingSec, toSignalPhase } from '@/simulation/signalState'
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
  SimRoadnetResponse,
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
  dispatchEmergency,
} from '@/api/simulation'
import type { ControlDecision, CreateSimulationResponse, DispatchResponse, EmergencyEvType, EvEventDto, EvStatusDto } from '@/types/traffic'

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
  // =========================================================
  // 1. State
  // =========================================================
  const systemMode = ref<SystemMode>('normal')
  const aiEnabled = ref(true)
  const selectedIntersectionId = ref<string | null>(null)
  const intersections = ref<Intersection[]>(structuredClone(mockIntersections))
  const roads = ref<Road[]>(structuredClone(mockRoads))
  const vehicles = ref<Vehicle[]>(structuredClone(mockVehicles))
  const emergencyVehicle = ref<EmergencyVehicle>(structuredClone(mockEmergencyVehicle))
  /** CityFlow 分配的车辆 ID，用于在仿真帧数据中匹配 EV */
  const emergencyCfVehicleId = ref<string>('')
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

  // ---- AI 控制决策（WebSocket control.decision）----
  const latestControlDecision = ref<ControlDecision | null>(null)
  function handleControlDecision(decision: ControlDecision): void {
    latestControlDecision.value = decision
  }

  // ---- EV 事件与状态（每帧随 SimFrameData 推送）----
  const latestEvEvents = ref<EvEventDto[]>([])
  const latestEvStatus = ref<EvStatusDto[]>([])

  // ---- compareMetrics 更新节流 ----
  let lastCompareMetricsUpdate = 0

  // ---- 告警检测跟踪变量 ----
  let lastSimStatus: string | null = null
  let lastAvgSpeedForAlert = 0
  let lastFrameForAlerts: any = null

  // ---- 实时指标趋势（给 CompareCharts 用）----
  function genInitialTrend(base: number, variance: number): CongestionTrendPoint[] {
    const pts: CongestionTrendPoint[] = []
    const now = new Date()
    for (let i = 59; i >= 0; i--) {
      const t = new Date(now.getTime() - i * 60_000)
      pts.push({ time: `${String(t.getHours()).padStart(2, '0')}:${String(t.getMinutes()).padStart(2, '0')}`, value: Math.round((base + (Math.random() - 0.5) * variance) * 10) / 10 })
    }
    return pts
  }
  const waitTrend = ref<CongestionTrendPoint[]>(genInitialTrend(32, 10))
  const speedTrend = ref<CongestionTrendPoint[]>(genInitialTrend(42, 8))

  // ---- 仿真状态 ----
  const simulationStatus = ref<SimulationStatus>('booting')
  const simulationSid = ref<string | null>(null)
  const simulationSceneId = ref('jinan_3x4')
  const simulationSpeed = ref(1.0)
  const simulationControllerType = ref('fixed-time')
  const simulationSimTime = ref(0)
  const simulationFrameCount = ref(0)
  const simulationLastFrameAt = ref(0)
  // CityFlow frames replace the complete array. Deep-proxying every vehicle makes
  // a dense frame expensive before either map or 3D rendering can use it.
  const simulationVehicles = shallowRef<SimVehicleState[]>([])
  const simulationRoads = ref<SimRoadState[]>([])
  const simulationSignals = ref<SimSignalState[]>([])
  const simulationIntersections = ref<SimIntersectionState[]>([])
  const simulationMetrics = ref<SimMetrics | null>(null)
  const simulationErrorMessage = ref<string | null>(null)
  // CityFlow 静态路网（用于车辆坐标 → 地图经纬度 的仿射变换）
  const simRoadnet = ref<SimRoadnetResponse | null>(null)
  /** 道路拥堵指数 EMA 平滑（key=roadId, value=smoothed value），避免帧间跳动 */
  const roadCongestionSmooth = new Map<string, number>()

  // =========================================================
  // 2. Getters
  // =========================================================
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

  // =========================================================
  // 3. Actions — 系统控制
  // =========================================================
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

  // =========================================================
  // 4. Actions — 应急绿波
  // =========================================================
  /**
   * 调度应急车辆（优先调用后端 API，失败降级到 mock）
   * @returns 调度结果，失败时返回 null
   */
  async function dispatchEmergencyVehicle(params: {
    startIntersection: string
    endIntersection: string
    evType: EmergencyEvType
    priority: number
  }): Promise<{ evId: string; route: string[]; routeRoads: string[]; estimatedTravelTime: number; startName: string; endName: string } | null> {
    const evId = `EV-${Date.now()}`
    const startInter = intersections.value.find((it) => it.id === params.startIntersection)
    const endInter = intersections.value.find((it) => it.id === params.endIntersection)

    if (!startInter || !endInter) {
      console.error('[TrafficStore] Invalid intersection IDs for dispatch')
      return null
    }

    // 将 mock 路口 ID 映射为 CityFlow 真实路口 ID
    const toCityFlowId = (id: string): string => {
      const it = intersections.value.find((i) => i.id === id)
      if (it && it.col > 0 && it.row > 0) {
        return 'intersection_' + it.col + '_' + it.row
      }
      return id
    }
    const realStartId = toCityFlowId(params.startIntersection)
    const realEndId = toCityFlowId(params.endIntersection)

    // 优先调用后端 API
    if (simulationSid.value) {
      try {
        const result = await dispatchEmergency(simulationSid.value, {
          startIntersection: realStartId,
          endIntersection: realEndId,
          evId,
          evType: params.evType,
          priority: params.priority,
        })

        applyDispatchResult(result, startInter.name, endInter.name)
        return {
          evId: result.evId,
          route: result.route,
          routeRoads: result.routeRoads,
          estimatedTravelTime: result.estimatedTravelTime,
          startName: startInter.name,
          endName: endInter.name,
        }
      } catch (err) {
        console.warn('[TrafficStore] Backend dispatch API failed, falling back to mock', err)
      }
    }

    // 降级：mock 路径
    const mockRoute = buildMockRoute(params.startIntersection, params.endIntersection)
    applyMockDispatchResult(mockRoute, params.evType, evId, startInter.name, endInter.name)
    return {
      evId,
      route: mockRoute,
      routeRoads: [],
      estimatedTravelTime: mockRoute.length * 2.5,
      startName: startInter.name,
      endName: endInter.name,
    }
  }

  /** 将后端 dispatch 响应应用到 store */
  function applyDispatchResult(
    result: DispatchResponse,
    startName: string,
    endName: string,
  ): void {
    emergencyCfVehicleId.value = result.cfVehicleId || ''
    emergencyVehicle.value = {
      id: result.evId,
      type: result.evType as 'ambulance' | 'fire_truck',
      currentIntersectionId: result.route[0] ?? '',
      destination: result.evType === 'fire_truck'
        ? `${endName} (火警)`
        : `${endName} (医院)`,
      greenWaveActive: true,
      eta: +(result.estimatedTravelTime / 60).toFixed(1),
    }
    emergencyRoute.value = result.route
    activeGreenWaveIndex.value = 0
    systemMode.value = 'emergency'

    generateMockAlert(
      'emergency_vehicle_enter',
      'emergency',
      `应急车辆 ${result.evId} 已调度 — ${result.evType === 'fire_truck' ? '消防车' : '救护车'}进入路网`,
      `${startName} → ${endName}`,
      result.route[0] ?? '',
    )
  }

  /** Mock 降级：将本地生成的路线应用到 store */
  function applyMockDispatchResult(
    route: string[],
    evType: EmergencyEvType,
    evId: string,
    startName: string,
    endName: string,
  ): void {
    emergencyVehicle.value = {
      id: evId,
      type: evType,
      currentIntersectionId: route[0] ?? '',
      destination: evType === 'fire_truck'
        ? `${endName} (火警)`
        : `${endName} (医院)`,
      greenWaveActive: true,
      eta: route.length * 1.8,
    }
    emergencyRoute.value = route
    activeGreenWaveIndex.value = 0
    systemMode.value = 'emergency'

    // 将应急车辆放置于路线首段
    if (route.length >= 2) {
      const routeRoad = roads.value.find(
        (r) => r.from === route[0] && r.to === route[1],
      )
      const existingVeh = vehicles.value.find((v) => v.id === 'E001')
      if (existingVeh) {
        existingVeh.type = evType
        existingVeh.speed = evType === 'ambulance' ? 62 : 55
        existingVeh.progress = 0.05
        if (routeRoad) existingVeh.roadId = routeRoad.id
      }
    }

    generateMockAlert(
      'emergency_vehicle_enter',
      'emergency',
      `应急车辆 ${evId} 已调度（本地模拟）— ${evType === 'fire_truck' ? '消防车' : '救护车'}进入路网`,
      `${startName} → ${endName}`,
      route[0] ?? '',
    )
  }

  /**
   * Mock 路径构建：Manhattan 网格路由
   * 按路口网格 row/col 行走，生成从起点到终点的路口 ID 序列
   */
  function buildMockRoute(fromId: string, toId: string): string[] {
    const from = intersections.value.find((it) => it.id === fromId)
    const to = intersections.value.find((it) => it.id === toId)
    if (!from || !to) return [fromId, toId]

    const route: string[] = [fromId]
    let cr = from.row
    let cc = from.col
    const tr = to.row
    const tc = to.col

    // 先走列，再走行
    while (cc !== tc) {
      cc += cc < tc ? 1 : -1
      const node = intersections.value.find((it) => it.row === cr && it.col === cc)
      if (node) route.push(node.id)
    }
    while (cr !== tr) {
      cr += cr < tr ? 1 : -1
      const node = intersections.value.find((it) => it.row === cr && it.col === cc)
      if (node) route.push(node.id)
    }
    return route
  }

  /**
   * 模拟应急车辆出发（保留为本地快捷触发，供 UI 直接调用）
   * 使用当前 emergencyRoute 的起点/终点，默认救护车 + 优先级 3
   */
  function simulateEmergencyVehicle(): void {
    const startId = emergencyRoute.value[0] ?? intersections.value[0]?.id
    const endId = emergencyRoute.value[emergencyRoute.value.length - 1] ?? intersections.value[intersections.value.length - 1]?.id
    if (!startId || !endId) return
    void dispatchEmergencyVehicle({
      startIntersection: startId,
      endIntersection: endId,
      evType: 'ambulance',
      priority: 3,
    })
  }

  /** 启动应急绿波 */
  function startEmergencyGreenWave(): void {
    systemMode.value = 'emergency'
    activeGreenWaveIndex.value = 0
    emergencyVehicle.value.greenWaveActive = true
  }

  /** 恢复正常模式 */
  function restoreNormalMode(): void {
    systemMode.value = 'normal'
    emergencyVehicle.value.greenWaveActive = false
    emergencyVehicle.value.eta = 8
    activeGreenWaveIndex.value = -1

    const ev = vehicles.value.find((v) => v.id === emergencyVehicle.value.id)
    if (ev) {
      ev.type = 'normal'
      ev.speed = 30 + Math.random() * 30
    }
  }

  // =========================================================
  // 5. Actions — 数据刷新
  // =========================================================
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
    // =========================================================
    // 仿真运行中：使用 CityFlow / WebSocket 真实数据，不添加本地噪声
    // （intersections / roads / vehicles 已由 applySimFrameToTrafficData 更新）
    // =========================================================
    if (
      simulationSid.value !== null &&
      simulationFrameCount.value > 0 &&
      simulationStatus.value !== 'finished'
    ) {
      const m = simulationMetrics.value
      if (m) {
        statistics.value.totalFlow = m.vehicleCount
        statistics.value.averageSpeed = m.avgSpeed
        statistics.value.averageWaitTime = m.avgWait
        statistics.value.throughput = m.throughput
      }

      const simRoads = simulationRoads.value
      if (simRoads.length > 0) {
        const jammed = simRoads.filter((r) => r.level === 'jammed').length
        const slow = simRoads.filter((r) => r.level === 'slow').length
        statistics.value.congestedRoadCount = jammed
        statistics.value.congestionIndex = +(((jammed * 85 + slow * 45) / simRoads.length) || 0).toFixed(1)
      }

      statistics.value.optimizedIntersectionCount = simulationIntersections.value.filter(
        (it) => it.level !== 'free',
      ).length
      statistics.value.emergencyVehicleCount = vehicles.value.filter(
        (v) => v.type !== 'normal',
      ).length
      statistics.value.deviceOnlineRate = 100
      statistics.value.greenWaveCount = systemMode.value === 'emergency' ? 1 : 0
      statistics.value.todayAlertCount = alerts.value.length

      // 每 30s 用真实仿真数据更新 AI 对比指标
      const now = Date.now()
      if (m && now - lastCompareMetricsUpdate > 30_000) {
        lastCompareMetricsUpdate = now
        compareMetrics.value = {
          ...compareMetrics.value,
          averageWaitTime: {
            ...compareMetrics.value.averageWaitTime,
            ai: Math.round(m.avgWait * 10) / 10,
          },
          averageSpeed: {
            ...compareMetrics.value.averageSpeed,
            ai: Math.round(m.avgSpeed * 3.6 * 10) / 10, // m/s → km/h
          },
          queueLength: {
            ...compareMetrics.value.queueLength,
            ai: m.queueCount,
          },
        }
        // 追加实时趋势点
        const timeLabel = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
        waitTrend.value = [...waitTrend.value.slice(-59), { time: timeLabel, value: Math.round(m.avgWait * 10) / 10 }]
        speedTrend.value = [...speedTrend.value.slice(-59), { time: timeLabel, value: Math.round(m.avgSpeed * 3.6 * 10) / 10 }]
      }

      updateSystemLatency()
      return
    }

    // =========================================================
    // 无仿真：本地 mock / 后端 DB 数据 + 轻微随机噪声（演示用）
    // =========================================================
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

    // ---- 仿真中断检测 ----
    const prevStatus = lastSimStatus
    lastSimStatus = simulationStatus.value
    if (
      prevStatus === 'running' &&
      (simulationStatus.value === 'paused' || simulationStatus.value === 'finished')
    ) {
      generateMockAlert('control_failure', 'error', '仿真异常中断', `状态从 running → ${simulationStatus.value}`, undefined)
    }

    // ---- AI 决策置信度低 ----
    const decision = latestControlDecision.value
    if (decision && decision.confidence < 0.3) {
      const key = `ai_low_confidence:${decision.intersectionId}`
      const last = alertCooldown.get(key)
      if (!last || now - last > ALERT_COOLDOWN_MS) {
        alertCooldown.set(key, now)
        generateMockAlert('control_failure', 'warning', `AI 决策置信度低 (${Math.round(decision.confidence * 100)}%) — ${decision.intersectionId}`, `控制器 ${decision.controllerType}`, decision.intersectionId)
      }
    }

    // ---- 路网性能恶化（均速骤降） ----
    let prevAvgSpeed = lastAvgSpeedForAlert
    lastAvgSpeedForAlert = statistics.value.averageSpeed
    if (prevAvgSpeed > 0 && statistics.value.averageSpeed < prevAvgSpeed * 0.7) {
      const key = 'perf_degradation'
      const last = alertCooldown.get(key)
      if (!last || now - last > ALERT_COOLDOWN_MS) {
        alertCooldown.set(key, now)
        generateMockAlert('abnormal_congestion', 'error', `路网均速骤降: ${prevAvgSpeed.toFixed(1)} → ${statistics.value.averageSpeed.toFixed(1)} km/h`, '路网性能恶化预警')
      }
    }

    // ---- 应急车辆阻塞（来自仿真帧 evEvents） ----
    if (simulationStatus.value === 'running' && simulationVehicles.value) {
      // evEvents 在 SimFrameData 中但前端类型未包含，用 any 兜底
      const frame = lastFrameForAlerts
      if (frame && (frame as any).evEvents) {
        for (const ev of (frame as any).evEvents) {
          if (ev.status === 'blocked') {
            const key = `ev_blocked:${ev.evId}:${ev.intersectionId}`
            const last = alertCooldown.get(key)
            if (!last || now - last > ALERT_COOLDOWN_MS) {
              alertCooldown.set(key, now)
              generateMockAlert('emergency_event', 'emergency', `应急车辆 ${ev.evId} 在 ${ev.intersectionId} 被阻塞`, ev.blockedBy ? `被 ${ev.blockedBy} 阻塞` : '决策 blocked', ev.intersectionId)
            }
          }
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

  // =========================================================
  // 6. Actions — 仿真管理
  // =========================================================
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
    lastFrameForAlerts = frame

    // 捕获 EV 事件和状态
    if (frame.evEvents && frame.evEvents.length > 0) {
      latestEvEvents.value = frame.evEvents
    }
    if (frame.evStatus && frame.evStatus.length > 0) {
      latestEvStatus.value = frame.evStatus
    }
    if (frame.status === 'finished') {
      simulationStatus.value = 'finished'
    }

    // 用仿真数据同步刷新前端路口/道路/车辆状态
    applySimFrameToTrafficData(frame)

    // A received frame restores realtime health; a separate watchdog detects silence.
    simulationErrorMessage.value = null
  }

  /** Detect frame silence independently from the receive callback. */
  function checkSimulationFrameTimeout(now = Date.now()): boolean {
    const timedOut = simulationStatus.value === 'running'
      && simulationLastFrameAt.value > 0
      && now - simulationLastFrameAt.value > 3500

    if (timedOut) {
      simulationErrorMessage.value = `等待新帧中… last frame ${simulationFrameCount.value}`
    } else if (simulationErrorMessage.value?.startsWith('等待新帧中…')) {
      simulationErrorMessage.value = null
    }
    return timedOut
  }

  /** 将仿真帧数据同步到现有的 traffic 数据结构（渐进式替换 mock） */
  function applySimFrameToTrafficData(frame: SimFrameData): void {
    // ---- 建立 CityFlow ID → 上海路口 的映射 ----
    // CityFlow 网格是转置的：intersection_R_C 中 R=上海col，C=上海row
    // 所以上海 (row, col) 对应 CityFlow intersection_{col}_{row}
    const itBySimKey = new Map<string, Intersection>()
    for (const it of intersections.value) {
      itBySimKey.set(`${it.col}_${it.row}`, it) // key = "R_C"（R=col, C=row）
    }
    // 从 CityFlow ID 取出匹配键（去掉 intersection_ 前缀即为 "R_C"）
    function simKeyOf(id: string): string | null {
      const m = id.match(/^intersection_(\d+)_(\d+)$/)
      return m ? `${m[1]}_${m[2]}` : null
    }

    // ---- 信号灯 → 路口相位（按转置键精确匹配）----
    for (const sig of frame.signals) {
      const key = simKeyOf(sig.intersectionId)
      const it = key ? itBySimKey.get(key) : undefined
      if (!it) continue
      it.currentPhase = toSignalPhase(sig.phaseCode)
      it.deviceStatus = 'online'
      const remaining = signalRemainingSec(sig)
      it.greenRemainKnown = remaining !== null
      it.greenRemain = remaining ?? 0
    }

    // ---- 路口排队/延误（按转置键精确匹配）----
    for (const istate of frame.intersections) {
      const key = simKeyOf(istate.id)
      const it = key ? itBySimKey.get(key) : undefined
      if (!it) continue
      it.queueLength = istate.queueCount
      it.averageDelay = Math.round(istate.avgWait)
      it.congestionIndex = istate.level === 'jammed' ? 90
        : istate.level === 'slow' ? 55
        : 25
    }

    // ---- 道路状态：按端点对匹配（用 CityFlow 静态路网的 from/to 拓扑）----
    // 上海道路 DB-R01 的 from/to 是路口 ID；CityFlow 道路 road_x 的 from/to 是 intersection_R_C。
    // 两端都换算成转置键，用无序端点对做匹配，双向道路取聚合。
    if (simRoadnet.value) {
      // 上海路口 ID → 转置键 "col_row"
      const shIdToKey = new Map<string, string>()
      for (const it of intersections.value) shIdToKey.set(it.id, `${it.col}_${it.row}`)

      // CityFlow 路口 ID → 键（去掉 intersection_ 前缀即为 "R_C"）
      const simIdToKey = (id: string): string | null => {
        const m = id.match(/^intersection_(\d+)_(\d+)$/)
        return m ? `${m[1]}_${m[2]}` : null
      }

      // CityFlow roadId → 无序端点对键 "a|b"
      const simRoadPairKey = new Map<string, string>()
      for (const sr of simRoadnet.value.roads) {
        const a = simIdToKey(sr.from)
        const b = simIdToKey(sr.to)
        if (!a || !b) continue
        simRoadPairKey.set(sr.id, [a, b].sort().join('|'))
      }

      // 端点对 → 聚合帧状态（同一对的正反向道路合并）
      const pairState = new Map<string, { veh: number; queue: number; speed: number; n: number }>()
      for (const rs of frame.roads) {
        const pk = simRoadPairKey.get(rs.id)
        if (!pk) continue
        const cur = pairState.get(pk) ?? { veh: 0, queue: 0, speed: 0, n: 0 }
        cur.veh += rs.vehicleCount
        cur.queue += rs.queueCount
        cur.speed += rs.avgSpeed
        cur.n += 1
        pairState.set(pk, cur)
      }

      // 写回上海道路
      for (const r of roads.value) {
        const ka = shIdToKey.get(r.from)
        const kb = shIdToKey.get(r.to)
        if (!ka || !kb) continue
        const st = pairState.get([ka, kb].sort().join('|'))
        if (!st || st.n === 0) continue
        const avgSpeed = st.speed / st.n
        r.flow = st.veh * 60
        r.speed = avgSpeed
        r.queueLength = st.queue
        // 拥堵指数：0-1→5  2-3→15-25  4-6→30-50  7-9→55-70  10+→75-100
        const base = st.veh <= 1 ? st.veh * 5 : st.veh <= 3 ? 5 + st.veh * 7 : st.veh <= 6 ? 10 + st.veh * 6 : 20 + st.veh * 5
        const rawCi = Math.min(100, base + st.queue * 3)
        // EMA 平滑：新值 30% + 旧值 70%，避免颜色跳动
        const prev = roadCongestionSmooth.get(r.id) ?? rawCi
        const smoothed = +(prev * 0.7 + rawCi * 0.3).toFixed(1)
        roadCongestionSmooth.set(r.id, smoothed)
        r.congestionIndex = smoothed
      }
    }

    // ---- 车辆：将 SimVehicleState[] 注入到 vehicles[] ----
    // ---- 全局统计 ----
    if (frame.metrics) {
      statistics.value.totalFlow = frame.metrics.vehicleCount ?? statistics.value.totalFlow
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

      // 拉取 CityFlow 静态路网，供车辆坐标映射使用
      try {
        simRoadnet.value = await fetchRoadnet(simulationSceneId.value)
        console.log('[TrafficStore] sim roadnet loaded', simRoadnet.value.intersections.length, 'intersections')
      } catch (e) {
        console.warn('[TrafficStore] sim roadnet fetch failed', e)
      }

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
      simulationLastFrameAt.value = Date.now()
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
  async function stopSimulationSession(expectedSid: string | null = simulationSid.value): Promise<void> {
    const sid = expectedSid
    if (!sid || (simulationSid.value === sid && simulationStatus.value === 'finished')) return
    try {
      await stopSimulation(sid)
      if (simulationSid.value === sid) {
        simulationStatus.value = 'finished'
      }
      console.log('[TrafficStore] simulation stopped')
    } catch (err) {
      if (simulationSid.value === sid) {
        simulationErrorMessage.value = `停止仿真失败: ${err instanceof Error ? err.message : String(err)}`
      }
      throw err
    }
  }

  /** 切换控制策略：停旧仿真 → 换 controllerType → 建新仿真并保持暂停 → 返回新 sid */
  async function recreateSimulation(controllerType: string): Promise<string | null> {
    simulationErrorMessage.value = null

    // 1. 停掉当前仿真
    if (simulationSid.value && simulationStatus.value !== 'finished') {
      try {
        await stopSimulation(simulationSid.value)
      } catch {
        // 后端可能已清理，忽略错误
      }
    }

    // 2. 更新策略类型 + 重置状态
    simulationControllerType.value = controllerType
    resetSimulationState()

    // 3. 创建新仿真
    const result = await initSimulationSession()
    if (!result?.sid) {
      simulationStatus.value = 'finished'
      return null
    }

    // 4. 自动启动新仿真
    try {
      await startSimulation(result.sid)
      simulationStatus.value = 'running'
      simulationLastFrameAt.value = Date.now()
      console.log('[TrafficStore] recreated + started with controller:', controllerType, 'sid:', result.sid)
    } catch (err) {
      console.warn('[TrafficStore] auto-start failed, simulation left paused', err)
    }
    return result.sid
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
    roadCongestionSmooth.clear()
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

  // =========================================================
  // 6. 导出
  // =========================================================
  return {
    // state
    systemMode,
    aiEnabled,
    selectedIntersectionId,
    intersections,
    roads,
    vehicles,
    emergencyVehicle,
    emergencyCfVehicleId,
    emergencyRoute,
    activeGreenWaveIndex,
    alerts,
    statistics,
    compareMetrics,
    congestionTrend,
    waitTrend,
    speedTrend,
    refreshConfig,
    systemLatency,
    mapZoom,
    alertIdCounter,
    dataSourceStatus,
    dataSourceMessage,
    latestControlDecision,
    handleControlDecision,

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
    simRoadnet,

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
    dispatchEmergencyVehicle,
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

    // EV data
    latestEvEvents,
    latestEvStatus,

    // simulation actions
    handleSimFrame,
    checkSimulationFrameTimeout,
    applySimFrameToTrafficData,
    initSimulationSession,
    resumeSimulation,
    pauseSimulationSession,
    stopSimulationSession,
    recreateSimulation,
    resetSimulationState,
  }
})
