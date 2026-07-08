// ================================================================
// AI 自适应信号控制与应急绿波数字孪生系统 — Mock 数据
// ================================================================
import type {
  Intersection,
  Road,
  Vehicle,
  EmergencyVehicle,
  Alert,
  GlobalStatistics,
  CompareMetrics,
  CongestionTrendPoint,
  RefreshConfig,
  AssistantReplies,
} from '@/types/traffic'

// ================================================================
// 1. 路口列表 (12 个 — 上海市中心真实路口坐标，4×3 沿主干道)
//
// 横轴（经度增大 = 东）：陕西南路 → 瑞金路 → 黄陂路 → 西藏路
// 纵轴（纬度增大 = 北）：建国路 → 淮海路 → 南京路
//
// 12 个路口全部在陆地上，连线沿真实道路走向
// ================================================================
// 基准范围：lng 121.450 ~ 121.485, lat 31.213 ~ 31.240

function mkIt(
  id: string, name: string, row: number, col: number,
  lng: number, lat: number,
  phase: Intersection['currentPhase'], green: number, ci: number, ds: Intersection['deviceStatus'],
): Intersection {
  return {
    id, name,
    // Three.js 抽象路网用归一化坐标（从真实经纬度推算）
    x: (lng - 121.450) / 0.035,
    y: (31.240 - lat) / 0.027,
    lng, lat, row, col,
    currentPhase: phase,
    greenRemain: green,
    queueLength: 5 + Math.round((row + col) * 2 + Math.random() * 8),
    averageDelay: 15 + Math.round(ci * 0.6),
    congestionIndex: ci,
    deviceStatus: ds,
  }
}

// 上海市中心真实路口坐标（人民广场→静安→黄浦核心区）
export const mockIntersections: Intersection[] = [
  // row 1 — 南京路（北）
  mkIt('A01', '西藏中路-南京东路', 1, 1, 121.4756, 31.2356, 'eastwest_straight',  28, 68, 'online'),
  mkIt('A02', '黄陂北路-南京西路', 1, 2, 121.4710, 31.2335, 'northsouth_left',    14, 45, 'online'),
  mkIt('A03', '茂名北路-南京西路', 1, 3, 121.4612, 31.2318, 'eastwest_straight',  42, 87, 'online'),
  mkIt('A04', '常德路-南京西路',   1, 4, 121.4518, 31.2305, 'eastwest_left',      18, 32, 'online'),
  // row 2 — 淮海路（中）
  mkIt('A05', '西藏南路-淮海东路', 2, 1, 121.4770, 31.2275, 'northsouth_straight', 35, 72, 'online'),
  mkIt('A06', '黄陂南路-淮海中路', 2, 2, 121.4725, 31.2255, 'eastwest_straight',   0, 92, 'fault'),
  mkIt('A07', '瑞金二路-淮海中路', 2, 3, 121.4632, 31.2238, 'northsouth_left',    22, 55, 'online'),
  mkIt('A08', '常熟路-淮海中路',   2, 4, 121.4530, 31.2225, 'all_red',             3, 22, 'online'),
  // row 3 — 建国路（南）
  mkIt('A09', '西藏南路-建国东路', 3, 1, 121.4778, 31.2208, 'eastwest_straight',  50, 48, 'online'),
  mkIt('A10', '黄陂南路-建国东路', 3, 2, 121.4730, 31.2190, 'northsouth_straight', 32, 64, 'online'),
  mkIt('A11', '瑞金二路-建国中路', 3, 3, 121.4640, 31.2160, 'eastwest_left',      25, 40, 'online'),
  mkIt('A12', '襄阳南路-建国西路', 3, 4, 121.4538, 31.2145, 'northsouth_left',    40, 30, 'offline'),
]

// ================================================================
// 2. 道路列表 — 上海真实道路网格（4×3 路口之间的连接）
// 横向：南京路 / 淮海路 / 建国路  纵向：西藏路 / 黄陂路 / 瑞金路 / 常熟路→襄阳路
// ================================================================
function mkRoad(id: string, from: string, to: string, name: string, flow: number, speed: number, queueLength: number, ci: number, path: [number, number][]): Road {
  return { id, from, to, name, flow, speed, queueLength, congestionIndex: ci, laneCount: 3, direction: 'two-way' as const, path }
}

// 上海市中心真实道路中心线坐标（沿实际街道几何提取的中间点）
// 每条 path 从 from 路口到 to 路口，中间点沿道路弯曲方向插值
export const mockRoads: Road[] = [
  // ===== 横向主干道 =====
  // 南京东路 (A01 西藏中路→A02 黄陂北路)：沿南京路步行街，微向北弯
  mkRoad('R01', 'A01', 'A02', '南京东路', 1820, 45, 120, 65, [
    [121.4756, 31.2356], [121.4734, 31.2348], [121.4710, 31.2335],
  ]),
  // 南京西路东段 (A02 黄陂北路→A03 茂名北路)：略弯
  mkRoad('R02', 'A02', 'A03', '南京西路', 2240, 38, 180, 78, [
    [121.4710, 31.2335], [121.4662, 31.2328], [121.4612, 31.2318],
  ]),
  // 南京西路西段 (A03 茂名北路→A04 常德路)：继续西行
  mkRoad('R03', 'A03', 'A04', '南京西路', 1560, 52, 85, 42, [
    [121.4612, 31.2318], [121.4565, 31.2312], [121.4518, 31.2305],
  ]),

  // 淮海东路 (A05 西藏南路→A06 黄陂南路)：向东微偏南
  mkRoad('R05', 'A05', 'A06', '淮海东路', 1380, 48, 95, 52, [
    [121.4770, 31.2275], [121.4748, 31.2266], [121.4725, 31.2255],
  ]),
  // 淮海中路中段 (A06 黄陂南路→A07 瑞金二路)：缓慢南弯
  mkRoad('R06', 'A06', 'A07', '淮海中路', 2600, 22, 280, 94, [
    [121.4725, 31.2255], [121.4680, 31.2248], [121.4632, 31.2238],
  ]),
  // 淮海中路西段 (A07 瑞金二路→A08 常熟路)：继续西南
  mkRoad('R07', 'A07', 'A08', '淮海中路', 1120, 54, 60, 35, [
    [121.4632, 31.2238], [121.4582, 31.2232], [121.4530, 31.2225],
  ]),

  // 建国东路 (A09 西藏南路→A10 黄陂南路)：略弯
  mkRoad('R10', 'A09', 'A10', '建国东路', 1440, 46, 105, 56, [
    [121.4778, 31.2208], [121.4754, 31.2200], [121.4730, 31.2190],
  ]),
  // 建国中路 (A10 黄陂南路→A11 瑞金二路)：西行缓弯
  mkRoad('R11', 'A10', 'A11', '建国中路', 1650, 44, 110, 58, [
    [121.4730, 31.2190], [121.4685, 31.2175], [121.4640, 31.2160],
  ]),
  // 建国西路 (A11 瑞金二路→A12 襄阳南路)：继续西偏南
  mkRoad('R12', 'A11', 'A12', '建国西路', 1360, 49, 88, 50, [
    [121.4640, 31.2160], [121.4590, 31.2153], [121.4538, 31.2145],
  ]),

  // ===== 纵向主干道（较直）=====
  // 西藏中路 (A01 南京东路→A05 淮海东路)：南偏西
  mkRoad('R15', 'A01', 'A05', '西藏中路', 1750, 40, 160, 74, [
    [121.4756, 31.2356], [121.4762, 31.2316], [121.4770, 31.2275],
  ]),
  // 西藏南路 (A05 淮海东路→A09 建国东路)：继续南偏西
  mkRoad('R16', 'A05', 'A09', '西藏南路', 1900, 36, 175, 76, [
    [121.4770, 31.2275], [121.4774, 31.2242], [121.4778, 31.2208],
  ]),

  // 黄陂北路 (A02 南京西路→A06 淮海中路)：向南微偏西
  mkRoad('R18', 'A02', 'A06', '黄陂北路', 1980, 42, 145, 70, [
    [121.4710, 31.2335], [121.4718, 31.2295], [121.4725, 31.2255],
  ]),
  // 黄陂南路 (A06 淮海中路→A10 建国东路)：继续向南
  mkRoad('R19', 'A06', 'A10', '黄陂南路', 1720, 41, 130, 66, [
    [121.4725, 31.2255], [121.4728, 31.2223], [121.4730, 31.2190],
  ]),

  // 瑞金二路北段 (A03 南京西路→A07 淮海中路)：南偏东
  mkRoad('R21', 'A03', 'A07', '瑞金二路', 2100, 35, 200, 85, [
    [121.4612, 31.2318], [121.4622, 31.2278], [121.4632, 31.2238],
  ]),
  // 瑞金二路南段 (A07 淮海中路→A11 建国中路)：继续南偏东
  mkRoad('R22', 'A07', 'A11', '瑞金二路', 980, 56, 42, 30, [
    [121.4632, 31.2238], [121.4636, 31.2200], [121.4640, 31.2160],
  ]),

  // 常熟路 (A04 南京西路→A08 淮海中路)：南偏西
  mkRoad('R24', 'A04', 'A08', '常熟路', 860, 60, 35, 20, [
    [121.4518, 31.2305], [121.4524, 31.2265], [121.4530, 31.2225],
  ]),
  // 襄阳南路 (A08 淮海中路→A12 建国西路)：继续南偏西
  mkRoad('R25', 'A08', 'A12', '襄阳南路', 720, 62, 30, 18, [
    [121.4530, 31.2225], [121.4534, 31.2185], [121.4538, 31.2145],
  ]),
]

// ================================================================
// 3. 车辆列表 (16 辆 — 只引用现有 roadId）
// ================================================================
export const mockVehicles: Vehicle[] = [
  { id: 'V001', roadId: 'R02', progress: 0.32, speed: 35, type: 'normal', laneIndex: 0 },
  { id: 'V002', roadId: 'R02', progress: 0.58, speed: 40, type: 'normal', laneIndex: 1 },
  { id: 'V003', roadId: 'R06', progress: 0.15, speed: 28, type: 'normal', laneIndex: 2 },
  { id: 'V004', roadId: 'R06', progress: 0.45, speed: 32, type: 'normal', laneIndex: 0 },
  { id: 'V005', roadId: 'R06', progress: 0.72, speed: 38, type: 'normal', laneIndex: 1 },
  { id: 'V006', roadId: 'R21', progress: 0.22, speed: 25, type: 'normal', laneIndex: 0 },
  { id: 'V007', roadId: 'R21', progress: 0.48, speed: 30, type: 'normal', laneIndex: 1 },
  { id: 'V008', roadId: 'R22', progress: 0.76, speed: 28, type: 'normal', laneIndex: 2 },
  { id: 'V009', roadId: 'R19', progress: 0.60, speed: 42, type: 'normal', laneIndex: 0 },
  { id: 'V010', roadId: 'R01', progress: 0.85, speed: 48, type: 'normal', laneIndex: 1 },
  { id: 'V011', roadId: 'R12', progress: 0.30, speed: 34, type: 'normal', laneIndex: 0 },
  { id: 'V012', roadId: 'R15', progress: 0.55, speed: 40, type: 'normal', laneIndex: 2 },
  { id: 'E001', roadId: 'R01', progress: 0.40, speed: 62, type: 'ambulance', laneIndex: 0 },
  { id: 'E002', roadId: 'R11', progress: 0.18, speed: 55, type: 'firetruck', laneIndex: 1 },
  { id: 'V013', roadId: 'R03', progress: 0.90, speed: 50, type: 'normal', laneIndex: 0 },
  { id: 'V014', roadId: 'R16', progress: 0.25, speed: 38, type: 'normal', laneIndex: 1 },
  { id: 'V015', roadId: 'R10', progress: 0.55, speed: 30, type: 'normal', laneIndex: 2 },
]

// ================================================================
// 4. 应急路线 — 沿南京东路→黄陂南路南下至建国东路
// ================================================================
export const mockEmergencyRoute: string[] = ['A01', 'A02', 'A06', 'A10']

export const mockEmergencyVehicle: EmergencyVehicle = {
  id: 'E001',
  type: 'ambulance',
  currentIntersectionId: 'A01',
  destination: '市第一人民医院（A07 附近）',
  greenWaveActive: false,
  eta: 8,
}

// ================================================================
// 5. 全局统计指标
// ================================================================
export const mockStatistics: GlobalStatistics = {
  totalFlow: 3847,
  averageSpeed: 42.6,
  averageWaitTime: 32.8,
  congestionIndex: 58,
  congestedRoadCount: 5,
  optimizedIntersectionCount: 8,
  emergencyVehicleCount: 2,
  deviceOnlineRate: 90.0,
  todayAlertCount: 6,
  greenWaveCount: 1,
}

// ================================================================
// 6. 控制效果对比
// ================================================================
export const mockCompareMetrics: CompareMetrics = {
  averageWaitTime: {
    name: '平均等待时间',
    traditional: 46.8,
    ai: 28.6,
    unit: 's',
    direction: 'lower',
  },
  averageSpeed: {
    name: '平均通行速度',
    traditional: 38.2,
    ai: 45.8,
    unit: 'km/h',
    direction: 'higher',
  },
  queueLength: {
    name: '路口排队长度',
    traditional: 185,
    ai: 112,
    unit: 'm',
    direction: 'lower',
  },
  emergencyPassTime: {
    name: '应急车辆通行时间',
    traditional: 14.5,
    ai: 5.2,
    unit: 'min',
    direction: 'lower',
  },
}

// ================================================================
// 7. 智能体模拟回复
// ================================================================
export const mockAssistantReplies: AssistantReplies = {
  拥堵:
    '当前路网共有 5 处拥堵路段。建议：① 优化 A06（中山大道-建设路）信号配时，拥堵指数已达 92；② 对 R09（建设路东段）启动分流引导；③ 关注 R06（北京路南段）车流密度变化。',
  绿波:
    '当前仅 1 条绿波通道活跃（人民路-建国路方向）。建议：① 开启中山大道东西向绿波，预计通行效率提升 35%；② 在长江路-北京路（A03）增设绿波入口检测器。',
  应急:
    '检测到 2 辆应急车辆在线。E001（救护车）位于 R05 解放路南段，预计 8 分钟到达目标。建议：提前激活 A02→A06 方向绿波，清空前方排队车辆。',
  信号:
    '8 个路口已完成 AI 自适应优化。A06（中山大道-建设路）信号控制器故障，已切换至手动降级模式，建议立即派单巡检。剩余 2 个路口（A03、A08）待优化。',
  设备:
    '全网 10 台信号控制器：在线 9 台、故障 1 台（A06）、离线 0 台。设备在线率 90.0%，较昨日下降 1.4 个百分点，需关注 A06 维修进度。',
  预案:
    '建议启动高峰应急预案：① 增加长江路-北京路（A03）直行相位时长 8s；② 对建设路（R09）实施单向限流；③ 向高新区（A04/A08）方向引导分流车流。',
}

// ================================================================
// 8. 初始告警列表
// ================================================================
export const mockInitialAlerts: Alert[] = [
  {
    id: 'ALT001',
    type: 'device_fault',
    level: 'error',
    title: 'A06 中山大道-建设路 信号控制器故障',
    location: '中山大道 · 核心节点',
    time: '2026-07-07 14:32:18',
    intersectionId: 'A06',
    acknowledged: false,
  },
  {
    id: 'ALT002',
    type: 'abnormal_congestion',
    level: 'error',
    title: '建设路东段（R09）拥堵指数超阈值',
    location: '中山大道-建设路 → 长江路-文化路',
    time: '2026-07-07 14:28:05',
    intersectionId: 'A06',
    acknowledged: false,
  },
  {
    id: 'ALT003',
    type: 'control_failure',
    level: 'error',
    title: 'A06 绿波同步丢失，周边 3 路口降级运行',
    location: '中山大道沿线 · 东西方向',
    time: '2026-07-07 14:18:05',
    intersectionId: 'A06',
    acknowledged: false,
  },
  {
    id: 'ALT004',
    type: 'abnormal_congestion',
    level: 'warning',
    title: '北京路南段（R06）车流量超阈值',
    location: '长江路-北京路 → 长江路-文化路',
    time: '2026-07-07 13:55:42',
    intersectionId: 'A03',
    acknowledged: false,
  },
  {
    id: 'ALT005',
    type: 'emergency_vehicle_enter',
    level: 'emergency',
    title: 'E001 救护车申请应急绿波通道',
    location: '解放路南段 → 市第一人民医院',
    time: '2026-07-07 14:35:00',
    intersectionId: 'A02',
    acknowledged: false,
  },
  {
    id: 'ALT006',
    type: 'abnormal_congestion',
    level: 'warning',
    title: '人民路-和平路（A05）排队长度异常',
    location: '人民路南段 · 南北方向',
    time: '2026-07-07 13:40:10',
    intersectionId: 'A05',
    acknowledged: true,
  },
]

// ================================================================
// 9. 拥堵趋势初始数据
// ================================================================
export function generateInitialTrend(): CongestionTrendPoint[] {
  const points: CongestionTrendPoint[] = []
  const now = new Date()
  for (let i = 59; i >= 0; i--) {
    const t = new Date(now.getTime() - i * 60_000)
    const hh = String(t.getHours()).padStart(2, '0')
    const mm = String(t.getMinutes()).padStart(2, '0')
    const base = 50 + 15 * Math.sin((i / 60) * Math.PI * 2) + (Math.random() - 0.5) * 10
    points.push({ time: `${hh}:${mm}`, value: Math.round(base * 10) / 10 })
  }
  return points
}

// ================================================================
// 10. 刷新配置
// ================================================================
export const mockRefreshConfig: RefreshConfig = {
  intervalMs: 1000,
  autoRefresh: true,
}

// ================================================================
// 11. 工具函数：根据关键词查找智能体回复
// ================================================================
export function findAssistantReply(input: string, replies: AssistantReplies): string {
  for (const [keyword, reply] of Object.entries(replies)) {
    if (input.includes(keyword)) {
      return reply
    }
  }
  return '收到您的指令。我将综合分析当前路网数据、信号控制状态和应急预案，为您生成最优决策建议。请提供更具体的需求关键词（如：拥堵、绿波、应急、信号、设备、预案）。'
}
