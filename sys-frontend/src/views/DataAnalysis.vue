<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'

import bgVideo from '@/assets/images/bg/bg-video.mp4'

type Tone = 'amber' | 'emerald' | 'rose' | 'sky'
type StatusTone = 'amber' | 'emerald' | 'rose' | 'slate'

interface MonitoringMetric {
  detail: string
  label: string
  tone: Tone
  value: string
}

interface StatusBucket {
  count: number
  label: string
  tone: StatusTone
}

interface DailyPoint {
  date: string
  electricity: number
  hvac: number
  occupancy: number
  water: number
}

interface HourlyPoint {
  electricity: number
  hour: string
  hvac: number
  occupancy: number
  temperature: number
}

interface BuildingSummary {
  averageOccupancy: number
  buildingId: string
  buildingType: string
  efficiencyScore: number
  electricity: number
  hvac: number
  statusLabel: string
  warningCount: number
  water: number
}

interface HeatmapCell {
  date: string
  electricity: number
  hour: string
  intensity: number
  occupancy: number
}

interface CompositionItem {
  color: string
  label: string
  value: number
}

interface ScatterPoint {
  buildingId: string
  electricity: number
  hour: string
  id: string
  occupancy: number
  temperature: number
  tone: Tone
}

interface MonitoringRecord {
  building_id: string
  building_type: string
  chilled_water_return_temp: number
  chilled_water_supply_temp: number
  device_id: string
  device_status: 'maintenance' | 'normal' | 'offline' | 'warning'
  electricity_kwh: number
  env_humidity: number
  env_temperature: number
  hvac_kwh: number
  id: number
  monitor_time: string
  occupancy_density: number
  water_m3: number
}

interface ChartPoint {
  x: number
  y: number
}

interface BarShape {
  height: number
  width: number
  x: number
  y: number
}

interface ScatterChartPoint extends ScatterPoint {
  cx: number
  cy: number
  xValue: number
}

interface ScatterTrendLine {
  x1: number
  x2: number
  y1: number
  y2: number
}

interface DashboardTooltipRow {
  label: string
  tone?: 'amber' | 'cyan' | 'emerald' | 'rose'
  value: string
}

interface DashboardTooltipContent {
  actions?: string[]
  body?: string
  rows?: DashboardTooltipRow[]
  title: string
}

interface DashboardToast {
  body: string
  id: number
  title: string
  tone: 'cyan' | 'emerald' | 'rose'
}

const colors = {
  amber: '#ffb800',
  bgPanel: '#0a2540',
  cyan: '#00d4ff',
  cyanBright: '#7af7ff',
  emerald: '#22d3a0',
  rose: '#ff4d6d',
  slate: '#5a7595',
  violet: '#7c5cff',
}

const now = ref(new Date())
const syncSeconds = ref(2)
const sampleCount = ref(1284)
const sampleRate = ref(96)
const hoveredDailyIndex = ref<number | null>(null)
const hoveredHourlyIndex = ref<number | null>(null)
const hoveredComposition = ref<string | null>(null)
const hoveredHeatmap = ref<{ date?: string; hour?: string; mode: 'cell' | 'column' | 'row' } | null>(null)
const sampledHeatmapKey = ref<string | null>(null)
const scanIndex = ref(3)
const sampledPointId = ref('BLDG-C-07-27')
const hoveredScatterTone = ref<Tone | null>(null)
const hoveredScatterRiskChart = ref<string | null>(null)
const hoveredScatterTrendChart = ref<string | null>(null)
const hiddenTones = ref<Set<Tone>>(new Set())
const metricDeltas = ref<Record<string, { tone: 'down' | 'up'; value: string }>>({})
const metricFlash = ref<Record<string, 'down' | 'up'>>({})
const metricTrendPoints = ref<Record<string, number[]>>({})
const toasts = ref<DashboardToast[]>([])
const tooltipContent = ref<DashboardTooltipContent | null>(null)
const tooltipVisible = ref(false)
const tooltipPosition = ref({ x: 0, y: 0 })

let clockTimer: ReturnType<typeof setInterval> | null = null
let syncTimer: ReturnType<typeof setInterval> | null = null
let scanTimer: ReturnType<typeof setInterval> | null = null
let liveMetricTimer: ReturnType<typeof setInterval> | null = null
let hourlyTimer: ReturnType<typeof setInterval> | null = null
let energyTimer: ReturnType<typeof setInterval> | null = null
let healthTimer: ReturnType<typeof setInterval> | null = null
let tableTimer: number | null = null
let eventTimer: number | null = null
let tooltipHideTimer: number | null = null

const metrics = ref<MonitoringMetric[]>([
  {
    detail: '今日 00:00 起累计电耗，只随实时采样累加。',
    label: '今日累计',
    tone: 'sky',
    value: '6428 kWh',
  },
  {
    detail: '当前时刻楼宇人流活跃度估计值。',
    label: '当前人流指数',
    tone: 'emerald',
    value: '71.4',
  },
  {
    detail: 'BLDG-C-07 · 18:00 负荷峰值。',
    label: '今日峰值负荷',
    tone: 'amber',
    value: '188.4 kWh',
  },
  {
    detail: '当前时段暖通系统电耗占比。',
    label: 'HVAC 当前占比',
    tone: 'sky',
    value: '43.6%',
  },
  {
    detail: '最新监测时间 2026-07-09 18:00。',
    label: '今日预警',
    tone: 'rose',
    value: '3 条',
  },
])

const statusDistribution = ref<StatusBucket[]>([
  { count: 101, label: '正常', tone: 'emerald' },
  { count: 194, label: '预警', tone: 'rose' },
  { count: 2, label: '维护中', tone: 'amber' },
  { count: 2, label: '离线', tone: 'slate' },
])

const dailySeries: DailyPoint[] = [
  { date: '06-28', electricity: 469.2, hvac: 196.6, occupancy: 48.6, water: 62.4 },
  { date: '06-29', electricity: 501.8, hvac: 210.9, occupancy: 53.2, water: 67.1 },
  { date: '06-30', electricity: 486.6, hvac: 204.4, occupancy: 51.5, water: 64.5 },
  { date: '07-01', electricity: 522.5, hvac: 229.2, occupancy: 58.1, water: 69.7 },
  { date: '07-02', electricity: 553.4, hvac: 238.4, occupancy: 62.8, water: 72.4 },
  { date: '07-03', electricity: 534.1, hvac: 226.7, occupancy: 59.4, water: 70.2 },
  { date: '07-04', electricity: 497.6, hvac: 205.1, occupancy: 50.2, water: 63.8 },
  { date: '07-05', electricity: 518.9, hvac: 218.5, occupancy: 56.7, water: 66.9 },
  { date: '07-06', electricity: 575.2, hvac: 251.9, occupancy: 65.6, water: 75.3 },
  { date: '07-07', electricity: 604.6, hvac: 267.4, occupancy: 69.4, water: 77.1 },
  { date: '07-08', electricity: 624.3, hvac: 276.2, occupancy: 72.8, water: 79.8 },
  { date: '07-09', electricity: 642.8, hvac: 280.1, occupancy: 71.4, water: 81.5 },
]

const hourlySeries = ref<HourlyPoint[]>([
  { electricity: 118.4, hour: '00:00', hvac: 44.8, occupancy: 22.5, temperature: 24.6 },
  { electricity: 152.7, hour: '06:00', hvac: 61.9, occupancy: 48.2, temperature: 25.1 },
  { electricity: 176.5, hour: '12:00', hvac: 79.4, occupancy: 68.6, temperature: 26.3 },
  { electricity: 188.4, hour: '18:00', hvac: 86.2, occupancy: 71.4, temperature: 27.1 },
])

const buildingSummaries = ref<BuildingSummary[]>([
  {
    averageOccupancy: 72.4,
    buildingId: 'BLDG-C-07',
    buildingType: 'lab',
    efficiencyScore: 72,
    electricity: 1688.4,
    hvac: 739.8,
    statusLabel: '预警优先',
    warningCount: 4,
    water: 188.3,
  },
  {
    averageOccupancy: 66.8,
    buildingId: 'BLDG-A-03',
    buildingType: 'office',
    efficiencyScore: 86,
    electricity: 1516.2,
    hvac: 602.6,
    statusLabel: '运行稳定',
    warningCount: 1,
    water: 164.2,
  },
  {
    averageOccupancy: 59.7,
    buildingId: 'BLDG-D-02',
    buildingType: 'mixed-use',
    efficiencyScore: 81,
    electricity: 1427.7,
    hvac: 621.5,
    statusLabel: '维护观察',
    warningCount: 2,
    water: 151.1,
  },
])

const heatmap: HeatmapCell[] = dailySeries.slice(-7).flatMap((day, dayIndex) =>
  hourlySeries.value.map((slot, slotIndex) => {
    const electricity = Number(
      (day.electricity * (0.16 + slotIndex * 0.055 + dayIndex * 0.01)).toFixed(1),
    )
    return {
      date: day.date,
      electricity,
      hour: slot.hour,
      intensity: Math.min(1, electricity / 170),
      occupancy: Number((slot.occupancy + dayIndex * 2.2).toFixed(1)),
    }
  }),
)

const composition = ref<CompositionItem[]>([
  { color: '#3b82f6', label: '暖通系统', value: 2757.6 },
  { color: '#22c55e', label: '照明系统', value: 1176.4 },
  { color: '#f59e0b', label: '插座与设备', value: 931.9 },
  { color: '#ef4444', label: '公共区域', value: 642.8 },
  { color: '#8b5cf6', label: '实验与专用负荷', value: 482.1 },
  { color: '#06b6d4', label: '其他损耗', value: 437.1 },
])

const scatterProfiles: Array<{
  baseElectricity: number
  baseOccupancy: number
  baseTemperature: number
  id: string
  tone: Tone
}> = [
  { baseElectricity: 126, baseOccupancy: 48, baseTemperature: 24.6, id: 'BLDG-A-03', tone: 'sky' },
  { baseElectricity: 132, baseOccupancy: 50, baseTemperature: 24.8, id: 'BLDG-A-05', tone: 'sky' },
  { baseElectricity: 118, baseOccupancy: 44, baseTemperature: 24.2, id: 'BLDG-A-08', tone: 'sky' },
  { baseElectricity: 114, baseOccupancy: 42, baseTemperature: 24.1, id: 'BLDG-B-01', tone: 'emerald' },
  { baseElectricity: 121, baseOccupancy: 45, baseTemperature: 24.4, id: 'BLDG-B-04', tone: 'emerald' },
  { baseElectricity: 108, baseOccupancy: 39, baseTemperature: 23.8, id: 'BLDG-B-09', tone: 'emerald' },
  { baseElectricity: 149, baseOccupancy: 56, baseTemperature: 23.9, id: 'BLDG-C-07', tone: 'amber' },
  { baseElectricity: 156, baseOccupancy: 58, baseTemperature: 24.3, id: 'BLDG-C-11', tone: 'amber' },
  { baseElectricity: 142, baseOccupancy: 52, baseTemperature: 24.0, id: 'BLDG-C-15', tone: 'amber' },
  { baseElectricity: 136, baseOccupancy: 52, baseTemperature: 25.0, id: 'BLDG-D-02', tone: 'rose' },
  { baseElectricity: 151, baseOccupancy: 57, baseTemperature: 25.4, id: 'BLDG-D-06', tone: 'rose' },
  { baseElectricity: 145, baseOccupancy: 54, baseTemperature: 25.2, id: 'BLDG-D-09', tone: 'rose' },
]

const scatterHourOffsets = [
  { electricity: 0, hour: '00:00', occupancy: 0, temperature: 0 },
  { electricity: 22, hour: '06:00', occupancy: 12, temperature: 0.7 },
  { electricity: 42, hour: '12:00', occupancy: 22, temperature: 1.5 },
  { electricity: 52, hour: '18:00', occupancy: 26, temperature: 2.1 },
] as const

const scatterPoints: ScatterPoint[] = scatterProfiles.flatMap((profile, profileIndex) =>
  scatterHourOffsets.map((slot, slotIndex) => {
    const sampleIndex = profileIndex * scatterHourOffsets.length + slotIndex + 1
    const drift = ((profileIndex % 3) - 1) * 4 + slotIndex * 1.7
    const riskBoost = profile.tone === 'rose' ? 12 : profile.tone === 'amber' ? 8 : 0

    return {
      buildingId: profile.id,
      electricity: Number((profile.baseElectricity + slot.electricity + drift + riskBoost).toFixed(1)),
      hour: slot.hour,
      id: `${profile.id}-${String(sampleIndex).padStart(2, '0')}`,
      occupancy: Number((profile.baseOccupancy + slot.occupancy + (profileIndex % 4) * 1.4).toFixed(1)),
      temperature: Number((profile.baseTemperature + slot.temperature + (profileIndex % 5) * 0.08).toFixed(1)),
      tone: profile.tone,
    }
  }),
)

const records = ref<MonitoringRecord[]>([
  {
    building_id: 'BLDG-C-07',
    building_type: 'lab',
    chilled_water_return_temp: 12.4,
    chilled_water_supply_temp: 7.5,
    device_id: 'BLDG-C-07-DEV-18',
    device_status: 'warning',
    electricity_kwh: 188.4,
    env_humidity: 46,
    env_temperature: 27.4,
    hvac_kwh: 86.2,
    id: 1042,
    monitor_time: '2026-07-09 18:00',
    occupancy_density: 76,
    water_m3: 19.3,
  },
  {
    building_id: 'BLDG-A-03',
    building_type: 'office',
    chilled_water_return_temp: 11.8,
    chilled_water_supply_temp: 7.1,
    device_id: 'BLDG-A-03-DEV-21',
    device_status: 'normal',
    electricity_kwh: 171.2,
    env_humidity: 49,
    env_temperature: 27.2,
    hvac_kwh: 72.4,
    id: 1041,
    monitor_time: '2026-07-09 18:00',
    occupancy_density: 74,
    water_m3: 17.5,
  },
  {
    building_id: 'BLDG-D-02',
    building_type: 'mixed-use',
    chilled_water_return_temp: 12.1,
    chilled_water_supply_temp: 7.0,
    device_id: 'BLDG-D-02-DEV-09',
    device_status: 'maintenance',
    electricity_kwh: 174.0,
    env_humidity: 51,
    env_temperature: 27.0,
    hvac_kwh: 74.1,
    id: 1040,
    monitor_time: '2026-07-09 18:00',
    occupancy_density: 70,
    water_m3: 16.1,
  },
  {
    building_id: 'BLDG-B-01',
    building_type: 'teaching',
    chilled_water_return_temp: 11.2,
    chilled_water_supply_temp: 6.7,
    device_id: 'BLDG-B-01-DEV-14',
    device_status: 'normal',
    electricity_kwh: 151.3,
    env_humidity: 53,
    env_temperature: 26.6,
    hvac_kwh: 58.8,
    id: 1039,
    monitor_time: '2026-07-09 18:00',
    occupancy_density: 64,
    water_m3: 14.2,
  },
  {
    building_id: 'BLDG-C-07',
    building_type: 'lab',
    chilled_water_return_temp: 12.0,
    chilled_water_supply_temp: 7.4,
    device_id: 'BLDG-C-07-DEV-11',
    device_status: 'warning',
    electricity_kwh: 184.3,
    env_humidity: 45,
    env_temperature: 27.0,
    hvac_kwh: 82.7,
    id: 1038,
    monitor_time: '2026-07-09 12:00',
    occupancy_density: 75,
    water_m3: 18.9,
  },
  {
    building_id: 'BLDG-A-03',
    building_type: 'office',
    chilled_water_return_temp: 11.6,
    chilled_water_supply_temp: 7.0,
    device_id: 'BLDG-A-03-DEV-08',
    device_status: 'normal',
    electricity_kwh: 162.5,
    env_humidity: 48,
    env_temperature: 26.1,
    hvac_kwh: 69.3,
    id: 1037,
    monitor_time: '2026-07-09 12:00',
    occupancy_density: 71,
    water_m3: 16.8,
  },
  {
    building_id: 'BLDG-D-02',
    building_type: 'mixed-use',
    chilled_water_return_temp: 12.3,
    chilled_water_supply_temp: 7.2,
    device_id: 'BLDG-D-02-DEV-16',
    device_status: 'warning',
    electricity_kwh: 169.4,
    env_humidity: 52,
    env_temperature: 26.5,
    hvac_kwh: 76.9,
    id: 1036,
    monitor_time: '2026-07-09 12:00',
    occupancy_density: 68,
    water_m3: 15.4,
  },
  {
    building_id: 'BLDG-B-01',
    building_type: 'teaching',
    chilled_water_return_temp: 11.4,
    chilled_water_supply_temp: 6.9,
    device_id: 'BLDG-B-01-DEV-06',
    device_status: 'normal',
    electricity_kwh: 148.1,
    env_humidity: 54,
    env_temperature: 25.6,
    hvac_kwh: 55.2,
    id: 1035,
    monitor_time: '2026-07-09 12:00',
    occupancy_density: 63,
    water_m3: 13.7,
  },
  {
    building_id: 'BLDG-C-07',
    building_type: 'lab',
    chilled_water_return_temp: 12.6,
    chilled_water_supply_temp: 7.6,
    device_id: 'BLDG-C-07-DEV-07',
    device_status: 'warning',
    electricity_kwh: 171.4,
    env_humidity: 47,
    env_temperature: 25.9,
    hvac_kwh: 78.4,
    id: 1034,
    monitor_time: '2026-07-09 06:00',
    occupancy_density: 64,
    water_m3: 17.2,
  },
  {
    building_id: 'BLDG-A-03',
    building_type: 'office',
    chilled_water_return_temp: 11.5,
    chilled_water_supply_temp: 6.8,
    device_id: 'BLDG-A-03-DEV-19',
    device_status: 'normal',
    electricity_kwh: 146.2,
    env_humidity: 50,
    env_temperature: 25.4,
    hvac_kwh: 61.8,
    id: 1033,
    monitor_time: '2026-07-09 06:00',
    occupancy_density: 61,
    water_m3: 15.6,
  },
  {
    building_id: 'BLDG-D-02',
    building_type: 'mixed-use',
    chilled_water_return_temp: 12.1,
    chilled_water_supply_temp: 7.1,
    device_id: 'BLDG-D-02-DEV-23',
    device_status: 'maintenance',
    electricity_kwh: 155.2,
    env_humidity: 51,
    env_temperature: 25.8,
    hvac_kwh: 64.7,
    id: 1032,
    monitor_time: '2026-07-09 06:00',
    occupancy_density: 59,
    water_m3: 14.9,
  },
  {
    building_id: 'BLDG-B-01',
    building_type: 'teaching',
    chilled_water_return_temp: 11.0,
    chilled_water_supply_temp: 6.6,
    device_id: 'BLDG-B-01-DEV-02',
    device_status: 'normal',
    electricity_kwh: 132.4,
    env_humidity: 55,
    env_temperature: 24.8,
    hvac_kwh: 52.1,
    id: 1031,
    monitor_time: '2026-07-09 06:00',
    occupancy_density: 55,
    water_m3: 12.8,
  },
])

const dateText = computed(() =>
  new Intl.DateTimeFormat('zh-CN', {
    day: '2-digit',
    month: '2-digit',
    weekday: 'short',
  }).format(now.value),
)

const timeText = computed(() =>
  new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    hour12: false,
    minute: '2-digit',
    second: '2-digit',
  }).format(now.value),
)

const statusTotal = computed(() => statusDistribution.value.reduce((sum, item) => sum + item.count, 0))
const warningCount = computed(
  () => statusDistribution.value.find((item) => item.tone === 'rose')?.count ?? 0,
)
const normalCount = computed(
  () => statusDistribution.value.find((item) => item.tone === 'emerald')?.count ?? 0,
)
const maintenanceCount = computed(
  () => statusDistribution.value.find((item) => item.tone === 'amber')?.count ?? 0,
)
const riskCount = computed(
  () =>
    (statusDistribution.value.find((item) => item.tone === 'rose')?.count ?? 0) +
    (statusDistribution.value.find((item) => item.tone === 'slate')?.count ?? 0),
)

const healthScore = ref(96)
const healthGaugeStyle = computed(() => ({
  backgroundImage: `conic-gradient(${colors.cyan} 0deg ${healthScore.value * 3.6}deg, rgba(0,212,255,0.08) ${healthScore.value * 3.6}deg 360deg)`,
}))

const peakPoint = computed(() =>
  hourlySeries.value.reduce((best, point) => (point.electricity > best.electricity ? point : best)),
)
const quietPoint = computed(() =>
  hourlySeries.value.reduce((best, point) => (point.electricity < best.electricity ? point : best)),
)
const busiestPoint = computed(() =>
  hourlySeries.value.reduce((best, point) => (point.occupancy > best.occupancy ? point : best)),
)

const heatmapDates = computed(() => [...new Set(heatmap.map((item) => item.date))])
const heatmapHours = computed(() => [...new Set(heatmap.map((item) => item.hour))])
const peakHeatmapCell = computed(() =>
  heatmap.reduce((best, item) => (item.electricity > best.electricity ? item : best)),
)
const averageHeatmapElectricity = computed(
  () => heatmap.reduce((sum, item) => sum + item.electricity, 0) / heatmap.length,
)

const compositionTotal = computed(() => composition.value.reduce((sum, item) => sum + item.value, 0))
const compositionMaxValue = computed(() => Math.max(...composition.value.map((item) => item.value), 1))
const compositionArcs = computed(() => {
  let cursor = -88
  return composition.value.map((item, index) => {
    const ratio = item.value / Math.max(compositionTotal.value, 1)
    const angle = ratio * 360
    const gap = 2.2
    const start = cursor + gap / 2
    const end = cursor + angle - gap / 2
    const mid = (start + end) / 2
    cursor += angle
    return {
      ...item,
      endAngle: end,
      gradientId: `compositionGradientVue-${index}`,
      highlightId: `compositionHighlightVue-${index}`,
      liftX: Math.cos((mid * Math.PI) / 180) * 5,
      liftY: Math.sin((mid * Math.PI) / 180) * 5,
      ratio: ratio * 100,
      startAngle: start,
    }
  })
})

const dailyChart = computed(() => {
  const width = 860
  const height = 320
  const padding = 56
  const electricity = dailySeries.map((point) => point.electricity)
  const occupancy = dailySeries.map((point) => point.occupancy)
  const maxElectricity = Math.max(...electricity, 1)
  const occupancyExtent = axisExtent([0, ...occupancy])
  const bars = buildBars(electricity, width, height, padding)
  const points = buildLinePoints(occupancy, width, height, padding, occupancyExtent.min, occupancyExtent.max)

  return {
    areaPath: buildAreaPath(points, height, padding),
    bars,
    electricityTicks: buildAxisTicks(0, maxElectricity),
    height,
    linePath: buildLinePath(points),
    maxElectricity,
    occupancyExtent,
    occupancyPoints: points,
    occupancyTicks: buildAxisTicks(occupancyExtent.min, occupancyExtent.max),
    padding,
    width,
  }
})

const hourlyChart = computed(() => {
  const width = 460
  const height = 230
  const padding = 42
  const electricity = hourlySeries.value.map((point) => point.electricity)
  const occupancy = hourlySeries.value.map((point) => point.occupancy)
  const maxElectricity = Math.max(...electricity, 1)
  const occupancyExtent = axisExtent([0, ...occupancy])
  const bars = buildBars(electricity, width, height, padding)
  const points = buildLinePoints(occupancy, width, height, padding, occupancyExtent.min, occupancyExtent.max)

  return {
    bars,
    electricityTicks: buildAxisTicks(0, maxElectricity),
    height,
    linePath: buildLinePath(points),
    maxElectricity,
    occupancyExtent,
    occupancyPoints: points,
    occupancyTicks: buildAxisTicks(occupancyExtent.min, occupancyExtent.max),
    padding,
    width,
  }
})

const scatterCharts = computed(() => [
  makeScatterChart({
    correlation: 0.82,
    title: '能耗与人流关系',
    xKey: 'occupancy',
    xLabel: '人流指数',
  }),
  makeScatterChart({
    correlation: 0.68,
    title: '能耗与温度关系',
    xKey: 'temperature',
    xLabel: '温度 °C',
  }),
])

const visibleScatterLegend = [
  { color: colors.cyan, label: '办公楼', tone: 'sky' as Tone },
  { color: colors.emerald, label: '教学楼', tone: 'emerald' as Tone },
  { color: colors.amber, label: '实验楼', tone: 'amber' as Tone },
  { color: colors.rose, label: '风险点', tone: 'rose' as Tone },
]

const detailHeaders = [
  { colClass: 'col-building', label: '楼栋', meaning: '楼栋唯一编号' },
  { colClass: 'col-time', label: '时间', meaning: '监测采样时间' },
  { colClass: 'col-num', label: '电耗', meaning: '当前时段电耗，单位 kWh' },
  { colClass: 'col-num', label: '暖通', meaning: 'HVAC 系统电耗，单位 kWh' },
  { colClass: 'col-num', label: '用水', meaning: '当前时段用水量，单位 m³' },
  { colClass: 'col-num', label: '温度', meaning: '环境温度，单位 °C' },
  { colClass: 'col-num', label: '湿度', meaning: '环境湿度，单位 %' },
  { colClass: 'col-num', label: '人流', meaning: '人员活跃指数' },
  { colClass: 'col-device', label: '设备', meaning: '关联采集设备 ID' },
  { colClass: 'col-status', label: '状态', meaning: '设备运行状态' },
] as const

function seedMetricTrends() {
  metricTrendPoints.value = Object.fromEntries(
    metrics.value.map((metric, metricIndex) => [
      metric.label,
      Array.from({ length: 7 }, (_, index) => 38 + metricIndex * 5 + Math.sin(index * 0.9 + metricIndex) * 10),
    ]),
  )
}

function updateMetricTrend(label: string) {
  const source = metricTrendPoints.value[label] ?? [42, 48, 45, 55, 52, 60, 58]
  const last = source[source.length - 1] ?? 50
  const next = clamp(last + randomBetween(-8, 8), 22, 82)
  metricTrendPoints.value = {
    ...metricTrendPoints.value,
    [label]: [...source.slice(1), next],
  }
}

function metricTrendPolyline(label: string) {
  const points = metricTrendPoints.value[label] ?? []
  if (points.length === 0) return ''
  const max = Math.max(...points, 1)
  const min = Math.min(...points, 0)
  const range = Math.max(max - min, 1)
  return points
    .map((value, index) => {
      const x = (index / Math.max(points.length - 1, 1)) * 160
      const y = 24 - ((value - min) / range) * 18
      return `${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
}

function metricTrendLastPoint(label: string) {
  const points = metricTrendPoints.value[label] ?? []
  const max = Math.max(...points, 1)
  const min = Math.min(...points, 0)
  const value = points[points.length - 1] ?? 0
  const range = Math.max(max - min, 1)
  return {
    x: 160,
    y: 24 - ((value - min) / range) * 18,
  }
}

function getMetricNumber(label: string) {
  const metric = metrics.value.find((item) => item.label === label)
  return metric ? parseMetricValue(metric.value).numeric : 0
}

function updateMetricNumber(
  label: string,
  nextValue: number,
  options: { decimals?: number; detail?: string; suffix?: string } = {},
) {
  const currentValue = getMetricNumber(label)
  const decimals = options.decimals ?? (String(nextValue).includes('.') ? 1 : 0)
  const suffix = options.suffix ?? metrics.value.find((item) => item.label === label)?.value.replace(/^[-\d.]+/, '') ?? ''
  const rounded = Number(nextValue.toFixed(decimals))
  const delta = rounded - currentValue

  metrics.value = metrics.value.map((metric) =>
    metric.label === label
      ? {
          ...metric,
          detail: options.detail ?? metric.detail,
          value: `${formatNumber(rounded, decimals)}${suffix}`,
        }
      : metric,
  )

  if (Math.abs(delta) > 0.001) {
    const tone = delta >= 0 ? 'up' : 'down'
    metricFlash.value = { ...metricFlash.value, [label]: tone }
    metricDeltas.value = {
      ...metricDeltas.value,
      [label]: {
        tone,
        value: `${delta >= 0 ? '+' : ''}${formatNumber(delta, decimals)}`,
      },
    }
    window.setTimeout(() => {
      const nextFlash = { ...metricFlash.value }
      const nextDeltas = { ...metricDeltas.value }
      delete nextFlash[label]
      delete nextDeltas[label]
      metricFlash.value = nextFlash
      metricDeltas.value = nextDeltas
    }, 1000)
  }

  updateMetricTrend(label)
}

function pushToast(toast: Omit<DashboardToast, 'id'>) {
  toasts.value = [{ ...toast, id: Date.now() + Math.random() }, ...toasts.value].slice(0, 3)
}

function currentSlotIndex() {
  const hour = now.value.getHours()
  if (hour < 6) return 0
  if (hour < 12) return 1
  if (hour < 18) return 2
  return 3
}

function createLiveMonitoringRecord(id: number, warning = false): MonitoringRecord {
  const buildingPool = [
    { id: 'BLDG-A-03', status: 'normal' as const, type: 'office' },
    { id: 'BLDG-B-01', status: 'normal' as const, type: 'teaching' },
    { id: 'BLDG-C-07', status: warning ? ('warning' as const) : ('normal' as const), type: 'lab' },
    { id: 'BLDG-D-02', status: warning ? ('maintenance' as const) : ('normal' as const), type: 'mixed-use' },
  ]
  const building = buildingPool[randomInt(0, buildingPool.length - 1)]!
  const slot = hourlySeries.value[currentSlotIndex()]!
  const loadFactor = warning ? randomBetween(1.12, 1.28) : randomBetween(0.86, 1.08)
  const deviceNo = randomInt(8, 31)
  const monitorTime = `${now.value.getFullYear()}-${String(now.value.getMonth() + 1).padStart(2, '0')}-${String(now.value.getDate()).padStart(2, '0')} ${slot.hour}`

  return {
    building_id: building.id,
    building_type: building.type,
    chilled_water_return_temp: Number(randomBetween(11.2, 12.8).toFixed(1)),
    chilled_water_supply_temp: Number(randomBetween(6.6, 7.8).toFixed(1)),
    device_id: `${building.id}-DEV-${String(deviceNo).padStart(2, '0')}`,
    device_status: building.status,
    electricity_kwh: Number((slot.electricity * loadFactor).toFixed(1)),
    env_humidity: Number(randomBetween(45, 56).toFixed(0)),
    env_temperature: Number((slot.temperature + randomBetween(-0.7, 0.8)).toFixed(1)),
    hvac_kwh: Number((slot.hvac * loadFactor).toFixed(1)),
    id,
    monitor_time: monitorTime,
    occupancy_density: Number((slot.occupancy + randomBetween(-4, 4)).toFixed(1)),
    water_m3: Number(randomBetween(13, 21).toFixed(1)),
  }
}

function insertLiveRecord(warning = false) {
  const newRecord = createLiveMonitoringRecord(Date.now(), warning)
  records.value = [newRecord, ...records.value].slice(0, 12)
  sampleCount.value += 1
  sampleRate.value = randomInt(24, warning ? 42 : 36)
  syncSeconds.value = 0

  if (warning) {
    const nextWarning = getMetricNumber('今日预警') + 1
    updateMetricNumber('今日预警', nextWarning, {
      decimals: 0,
      detail: `${newRecord.building_id} 触发暖通能耗异常`,
      suffix: ' 条',
    })
    statusDistribution.value = statusDistribution.value.map((bucket) =>
      bucket.tone === 'rose' ? { ...bucket, count: bucket.count + 1 } : bucket,
    )
  }
}

function scheduleTableInsert() {
  tableTimer = window.setTimeout(() => {
    insertLiveRecord(false)
    scheduleTableInsert()
  }, randomInt(2000, 3000))
}

function scheduleRandomEvent() {
  eventTimer = window.setTimeout(() => {
    const eventType = randomInt(1, 4)
    if (eventType === 1) {
      insertLiveRecord(true)
      pushToast({
        body: `BLDG-C-07 电耗超阈值 ${randomInt(12, 28)}%`,
        title: '新预警提示',
        tone: 'rose',
      })
    } else if (eventType === 2) {
      pushToast({
        body: `共扫描 ${statusTotal.value} 个监测点`,
        title: '系统扫描完成',
        tone: 'emerald',
      })
    } else if (eventType === 3) {
      pushToast({
        body: `BLDG-${['A-03', 'B-01', 'C-07', 'D-02'][randomInt(0, 3)]} 完成状态巡检`,
        title: '设备状态切换',
        tone: 'emerald',
      })
    } else {
      pushToast({
        body: '热力矩阵与关系图完成一次采集脉冲',
        title: '采集周期完成',
        tone: 'cyan',
      })
    }
    scheduleRandomEvent()
  }, randomInt(8000, 15000))
}

function tooltipAttrs(content: DashboardTooltipContent) {
  return {
    'data-tooltip-actions': content.actions?.length ? JSON.stringify(content.actions) : undefined,
    'data-tooltip-body': content.body,
    'data-tooltip-rows': content.rows?.length ? JSON.stringify(content.rows) : undefined,
    'data-tooltip-title': content.title,
  }
}

function metricTooltip(metric: MonitoringMetric): DashboardTooltipContent {
  const parsed = parseMetricValue(metric.value)
  const value = parsed.numeric

  if (metric.label.includes('累计') || metric.label.includes('耗电') || metric.label.includes('电量')) {
    return {
      rows: [
        { label: '今日', value: compactKwh(value, 0) },
        { label: '昨日', value: compactKwh(value * 0.96, 0) },
        { label: '本周累计', value: compactKwh(value * 5.8, 0) },
        { label: '同比变化', tone: 'cyan', value: '+6.8%' },
      ],
      title: '今日累计明细',
    }
  }

  if (metric.label.includes('人流')) {
    return {
      rows: [
        { label: '当前', value: value.toFixed(1) },
        { label: '今日峰值', value: (value * 1.23).toFixed(1) },
        { label: '今日均值', value: (value * 0.94).toFixed(1) },
        { label: '峰值时段', value: '12:00-14:00' },
      ],
      title: '人流指数详情',
    }
  }

  if (metric.label.includes('峰值')) {
    return {
      rows: [
        { label: '峰值时刻', value: metric.detail.split(' · ')[1] ?? '今日当前时段' },
        { label: '峰值楼栋', value: 'BLDG-C-07' },
        { label: '峰值设备 ID', value: 'BLDG-C-07-DEV-15' },
      ],
      title: '峰值负荷明细',
    }
  }

  if (metric.label.includes('HVAC')) {
    return {
      rows: [
        { label: 'HVAC 实耗', value: compactKwh(18_627, 0) },
        { label: '总耗电量', value: compactKwh(35_420, 0) },
        { label: '同期对比', tone: 'amber', value: '+3.1%' },
      ],
      title: 'HVAC 占电比',
    }
  }

  return {
    rows: [
      { label: '今日预警数', tone: 'rose', value: `${Math.round(value)} 条` },
      { label: '已处理', tone: 'emerald', value: `${Math.max(0, Math.round(value * 0.72))} 条` },
      { label: '待处理', tone: 'amber', value: `${Math.max(0, Math.round(value * 0.28))} 条` },
      { label: '最近预警', value: '暖通能耗超阈值 15%' },
    ],
    title: '今日预警详情',
  }
}

function healthGaugeTooltip(score: number): DashboardTooltipContent {
  return {
    rows: [
      { label: '运行稳定性', value: `${Math.round(score * 0.32)} 分` },
      { label: '能效表现', value: `${Math.round(score * 0.28)} 分` },
      { label: '故障率', value: `${Math.round(score * 0.2)} 分` },
      { label: '维护及时性', value: `${Math.round(score * 0.2)} 分` },
      {
        label: '评级',
        tone: score >= 85 ? 'emerald' : score >= 72 ? 'cyan' : 'amber',
        value: score >= 85 ? '优秀' : score >= 72 ? '良好' : '一般',
      },
    ],
    title: '运行健康评分构成',
  }
}

function statusBucketTooltip(
  bucket: StatusBucket,
  bucketRatio: number,
  buildings: BuildingSummary[],
): DashboardTooltipContent {
  const relatedBuildings = buildings
    .filter((building) =>
      bucket.tone === 'emerald'
        ? building.warningCount === 0
        : bucket.tone === 'rose'
          ? building.warningCount > 0
          : true,
    )
    .slice(0, 4)
    .map((building) => building.buildingId)

  return {
    rows: [
      { label: '楼栋列表', value: relatedBuildings.join(' / ') || '暂无' },
      { label: '数量', value: `${bucket.count} 条` },
      { label: '占比', value: `${bucketRatio.toFixed(1)}%` },
      {
        label: '最近变化',
        tone: bucket.tone === 'rose' ? 'amber' : 'cyan',
        value: bucket.tone === 'rose' ? '+2 条' : '-1 条',
      },
    ],
    title: `${bucket.label}状态分布`,
  }
}

function ratioTooltip(label: string, value: string, bucketCount: number, total: number): DashboardTooltipContent {
  const numeratorLabel = label === '风险占比' ? '预警 + 离线' : label.replace('占比', '')

  return {
    rows: [
      { label: '计算公式', value: `${label} = ${numeratorLabel} / 监测总量` },
      { label: '涉及记录', value: `${bucketCount} 条` },
      { label: '监测总量', value: `${total} 条` },
      { label, value },
    ],
    title: `${label}计算口径`,
  }
}

function dailyTooltip(point: DailyPoint | undefined, previous: DailyPoint | undefined): DashboardTooltipContent {
  if (!point) {
    return {
      rows: [{ label: '状态', value: '暂无采样' }],
      title: '每日走势详情',
    }
  }

  const change = previous
    ? ((point.electricity - previous.electricity) / Math.max(previous.electricity, 1)) * 100
    : 0
  return {
    rows: [
      { label: '日期', value: point.date },
      { label: '电耗', value: compactKwh(point.electricity) },
      { label: '当日人流指数', value: point.occupancy.toFixed(1) },
      {
        label: '环比前一日',
        tone: change >= 0 ? 'cyan' : 'amber',
        value: `${change >= 0 ? '+' : ''}${change.toFixed(1)}%`,
      },
      { label: '峰值时段', value: point.occupancy > 78 ? '12:00-14:00' : '18:00-20:00' },
    ],
    title: '每日走势详情',
  }
}

function hourlyTooltip(point: HourlyPoint | undefined, index: number): DashboardTooltipContent {
  if (!point) {
    return {
      rows: [{ label: '状态', value: '暂无采样' }],
      title: '时段负荷详情',
    }
  }

  const nextHour = index >= 3 ? '24:00' : `${String((index + 1) * 6).padStart(2, '0')}:00`
  return {
    rows: [
      { label: '时段范围', value: `${point.hour}-${nextHour}` },
      { label: '平均负荷', value: compactKwh(point.electricity) },
      { label: '峰值楼栋', value: point.electricity > 165 ? 'BLDG-C-07' : 'BLDG-A-03' },
      { label: '人流均值', value: point.occupancy.toFixed(1) },
    ],
    title: '时段负荷详情',
  }
}

function heatmapCellTooltip(cell: HeatmapCell | undefined): DashboardTooltipContent {
  if (!cell) {
    return {
      rows: [{ label: '状态', value: '暂无采样' }],
      title: '热力单元详情',
    }
  }

  const yesterday = cell.electricity * stableRatio(`${cell.date}-${cell.hour}-yesterday`, 0.92, 1.08)
  const lastWeek = cell.electricity * stableRatio(`${cell.date}-${cell.hour}-last-week`, 0.88, 1.12)
  return {
    rows: [
      { label: '日期时段', value: `${cell.date} ${cell.hour}` },
      { label: '能耗值', value: compactKwh(cell.electricity) },
      {
        label: '较昨日同时段',
        tone: cell.electricity >= yesterday ? 'amber' : 'emerald',
        value: `${(((cell.electricity - yesterday) / yesterday) * 100).toFixed(1)}%`,
      },
      {
        label: '较上周同时段',
        value: `${(((cell.electricity - lastWeek) / lastWeek) * 100).toFixed(1)}%`,
      },
      {
        label: '负荷等级',
        value: cell.intensity > 0.82 ? '高' : cell.intensity > 0.62 ? '中' : '低',
      },
      { label: '主要贡献楼栋', value: cell.intensity > 0.82 ? 'BLDG-C-07' : 'BLDG-A-03' },
    ],
    title: '热力单元详情',
  }
}

function riskRowTooltip(summary: BuildingSummary, rank: number): DashboardTooltipContent {
  return {
    rows: [
      { label: '楼栋全称', value: `${summary.buildingId} ${buildingTypeReadable(summary.buildingType)}` },
      { label: '当前效率值', value: `${summary.efficiencyScore}` },
      {
        label: '风险等级',
        tone: summary.warningCount > 10 ? 'rose' : 'amber',
        value: summary.warningCount > 10 ? '高' : '中',
      },
      { label: '最近预警', value: '最近 24 小时 暖通能耗超阈值' },
      { label: '关联设备数', value: `${22 + rank * 3} 台` },
      { label: '待处理告警', value: `${Math.max(1, Math.round(summary.warningCount / 3))} 条` },
      { label: '建议动作', value: '复核冷站策略与末端阀门' },
    ],
    title: `${summary.buildingId} 风险详情`,
  }
}

function compositionTooltip(item: CompositionItem, itemRatio: number): DashboardTooltipContent {
  return {
    rows: [
      { label: '分类名称', value: item.label },
      { label: '数值', value: compactKwh(item.value, 0) },
      { label: '占比', value: `${itemRatio.toFixed(1)}%` },
      {
        label: '同比变化',
        tone: itemRatio > 35 ? 'amber' : 'cyan',
        value: itemRatio > 35 ? '+4.2%' : '+1.6%',
      },
      { label: 'Top 3 楼栋', value: 'BLDG-C-07 / BLDG-A-03 / BLDG-D-02' },
    ],
    title: '能耗构成详情',
  }
}

function scatterTooltip(point: ScatterPoint, xLabel: string): DashboardTooltipContent {
  const useTemperature = xLabel.includes('温度') || xLabel.includes('°C')
  return {
    rows: [
      { label: '类型', value: scatterTypeLabel(point.tone) },
      { label: '能耗', value: compactKwh(point.electricity) },
      {
        label: useTemperature ? '温度' : '人流指数',
        value: useTemperature ? `${point.temperature.toFixed(1)}°C` : point.occupancy.toFixed(1),
      },
      { label: '楼栋与时间', value: `${point.buildingId} ${point.hour}` },
      {
        label: '风险点',
        tone: point.tone === 'rose' || point.tone === 'amber' ? 'rose' : 'emerald',
        value: point.tone === 'rose' || point.tone === 'amber' ? '是' : '否',
      },
    ],
    title: '采样点详情',
  }
}

function tableRowTooltip(record: MonitoringRecord): DashboardTooltipContent {
  return {
    rows: [
      { label: '楼栋详情', value: `${record.building_id} ${buildingTypeReadable(record.building_type)}` },
      { label: '对比基线', value: `电耗 ${compactKwh(record.electricity_kwh * 0.88)}` },
      {
        label: '预警规则',
        value: record.device_status === 'warning' ? '暖通能耗超阈值 15%' : '未触发规则',
      },
      { label: '关联设备', value: record.device_id },
      { label: '24小时趋势', value: '▁▂▃▅▆▅▇' },
    ],
    title: '监测明细',
  }
}

function fieldHeaderTooltip(header: (typeof detailHeaders)[number]): DashboardTooltipContent {
  return {
    rows: [
      { label: '列含义', value: header.meaning },
      { label: '交互', value: '支持悬停查看字段详情' },
    ],
    title: `${header.label}列`,
  }
}

function detailMetricCells(record: MonitoringRecord): Array<{
  decimals: number
  label: string
  rows: DashboardTooltipRow[]
  suffix: string
  value: number
}> {
  return [
    {
      decimals: 1,
      label: '电耗',
      rows: [
        { label: '阈值范围', value: '70-180 kWh' },
        { label: '当前异常', tone: record.electricity_kwh > 180 ? 'rose' : 'emerald', value: record.electricity_kwh > 180 ? '是' : '否' },
        { label: '历史均值', value: compactKwh(record.electricity_kwh * 0.88) },
      ],
      suffix: '',
      value: record.electricity_kwh,
    },
    {
      decimals: 1,
      label: '暖通',
      rows: [
        { label: '阈值范围', value: '25-95 kWh' },
        { label: '当前异常', tone: record.hvac_kwh > 95 ? 'rose' : 'emerald', value: record.hvac_kwh > 95 ? '是' : '否' },
        { label: '历史均值', value: compactKwh(record.hvac_kwh * 0.9) },
      ],
      suffix: '',
      value: record.hvac_kwh,
    },
    {
      decimals: 1,
      label: '用水',
      rows: [
        { label: '阈值范围', value: '5-28 m³' },
        { label: '当前异常', tone: record.water_m3 > 28 ? 'rose' : 'emerald', value: record.water_m3 > 28 ? '是' : '否' },
        { label: '历史均值', value: `${formatNumber(record.water_m3 * 0.92)} m³` },
      ],
      suffix: '',
      value: record.water_m3,
    },
    {
      decimals: 1,
      label: '温度',
      rows: [
        { label: '阈值范围', value: '22-28°C' },
        { label: '当前异常', tone: record.env_temperature > 28 ? 'rose' : 'emerald', value: record.env_temperature > 28 ? '是' : '否' },
        { label: '历史均值', value: `${formatNumber(record.env_temperature * 0.96)}°C` },
      ],
      suffix: '°C',
      value: record.env_temperature,
    },
    {
      decimals: 1,
      label: '湿度',
      rows: [
        { label: '阈值范围', value: '40-65%' },
        { label: '当前异常', tone: record.env_humidity > 65 ? 'rose' : 'emerald', value: record.env_humidity > 65 ? '是' : '否' },
        { label: '历史均值', value: `${formatNumber(record.env_humidity * 0.96)}%` },
      ],
      suffix: '%',
      value: record.env_humidity,
    },
    {
      decimals: 1,
      label: '人流',
      rows: [
        { label: '阈值范围', value: '0-90' },
        { label: '当前异常', tone: record.occupancy_density > 90 ? 'rose' : 'emerald', value: record.occupancy_density > 90 ? '是' : '否' },
        { label: '历史均值', value: formatNumber(record.occupancy_density * 0.88) },
      ],
      suffix: '',
      value: record.occupancy_density,
    },
  ]
}

function readTooltipContent(target: Element | null): DashboardTooltipContent | null {
  const element = target as (Element & { dataset?: DOMStringMap }) | null
  const title = element?.dataset?.tooltipTitle
  if (!title) return null

  return {
    actions: parseTooltipArray<string>(element.dataset?.tooltipActions),
    body: element.dataset?.tooltipBody,
    rows: parseTooltipArray<DashboardTooltipRow>(element.dataset?.tooltipRows),
    title,
  }
}

function parseTooltipArray<T>(value: string | undefined): T[] {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? (parsed as T[]) : []
  } catch {
    return []
  }
}

function clampTooltipPosition(point: { x: number; y: number }) {
  const margin = 12
  const width = 300
  const height = 180
  let x = point.x + 16
  let y = point.y + 16
  if (x + width + margin > window.innerWidth) x = point.x - width - 16
  if (y + height + margin > window.innerHeight) y = point.y - height - 16
  return {
    x: Math.max(margin, Math.min(x, window.innerWidth - width - margin)),
    y: Math.max(margin, Math.min(y, window.innerHeight - height - margin)),
  }
}

function handleTooltipShow(event: PointerEvent) {
  const target = (event.target as Element | null)?.closest?.('[data-tooltip-title]') ?? null
  const nextContent = readTooltipContent(target)
  if (!nextContent) return
  if (tooltipHideTimer) window.clearTimeout(tooltipHideTimer)
  tooltipContent.value = nextContent
  tooltipPosition.value = clampTooltipPosition({ x: event.clientX, y: event.clientY })
  tooltipVisible.value = true
}

function handleTooltipMove(event: PointerEvent) {
  if (!((event.target as Element | null)?.closest?.('[data-tooltip-title]') ?? null)) return
  tooltipPosition.value = clampTooltipPosition({ x: event.clientX, y: event.clientY })
}

function handleTooltipHide(event: PointerEvent) {
  const target = (event.target as Element | null)?.closest?.('[data-tooltip-title]') ?? null
  if (!target) return
  const relatedTarget = event.relatedTarget as Element | null
  if (relatedTarget && target.contains(relatedTarget)) return
  tooltipVisible.value = false
  tooltipHideTimer = window.setTimeout(() => {
    tooltipContent.value = null
    tooltipHideTimer = null
  }, 200)
}

onMounted(() => {
  seedMetricTrends()
  pushToast({
    body: `当前采样速率 ${sampleRate.value} 条/分钟，已接入 ${sampleCount.value} 条样本。`,
    title: '监测流已接入',
    tone: 'emerald',
  })
  pushToast({
    body: 'BLDG-C-07 近 24 小时负荷持续偏高。',
    title: '暖通能耗异常',
    tone: 'rose',
  })

  clockTimer = setInterval(() => {
    now.value = new Date()
  }, 1000)

  syncTimer = setInterval(() => {
    syncSeconds.value += 1
  }, 1000)

  liveMetricTimer = setInterval(() => {
    const nextOccupancy = clamp(getMetricNumber('当前人流指数') + randomBetween(-1.8, 2.2), 42, 86)
    updateMetricNumber('当前人流指数', nextOccupancy, {
      decimals: 1,
      detail: '当前时刻楼宇人流活跃度估计值，2 秒小幅浮动。',
    })
    const current = records.value[0]
    if (current) {
      records.value = [
        {
          ...current,
          env_temperature: Number((current.env_temperature + randomBetween(-0.2, 0.25)).toFixed(1)),
          occupancy_density: Number(clamp(current.occupancy_density + randomBetween(-1.4, 1.6), 30, 92).toFixed(1)),
        },
        ...records.value.slice(1),
      ]
    }
  }, 2000)

  scanTimer = setInterval(() => {
    scanIndex.value = (scanIndex.value + 1) % dailySeries.length
    sampledPointId.value = scatterPoints[(scanIndex.value + 4) % scatterPoints.length]?.id ?? sampledPointId.value
    const sampledCell = heatmap[(scanIndex.value * 3) % heatmap.length]
    if (sampledCell) {
      sampledHeatmapKey.value = `${sampledCell.date}-${sampledCell.hour}`
      window.setTimeout(() => {
        sampledHeatmapKey.value = null
      }, 600)
    }
  }, 3200)

  hourlyTimer = setInterval(() => {
    const index = currentSlotIndex()
    hourlySeries.value = hourlySeries.value.map((point, pointIndex) =>
      pointIndex === index
        ? {
            ...point,
            electricity: Number((point.electricity * (1 + randomBetween(-0.03, 0.03))).toFixed(1)),
            hvac: Number((point.hvac * (1 + randomBetween(-0.025, 0.025))).toFixed(1)),
            occupancy: Number((point.occupancy * (1 + randomBetween(-0.025, 0.025))).toFixed(1)),
          }
        : point,
    )
    const slot = hourlySeries.value[index]!
    updateMetricNumber('今日峰值负荷', Math.max(getMetricNumber('今日峰值负荷'), slot.electricity * randomBetween(1.05, 1.18)), {
      decimals: 1,
      detail: `${slot.hour} 当前时段峰值检查`,
      suffix: ' kWh',
    })
    updateMetricNumber('HVAC 当前占比', clamp(getMetricNumber('HVAC 当前占比') + randomBetween(-1.2, 1.2), 30, 58), {
      decimals: 1,
      suffix: '%',
    })
    syncSeconds.value = 0
  }, 8000)

  energyTimer = setInterval(() => {
    const increment = randomBetween(5, 30)
    const nextEnergy = getMetricNumber('今日累计') + increment
    updateMetricNumber('今日累计', nextEnergy, {
      decimals: 0,
      suffix: ' kWh',
    })
    composition.value = composition.value.map((item) => ({
      ...item,
      value: Number((item.value + increment * (item.value / Math.max(compositionTotal.value, 1))).toFixed(1)),
    }))
    pushToast({
      body: `今日累计 +${increment.toFixed(0)} kWh`,
      title: '累计电耗更新',
      tone: 'cyan',
    })
    syncSeconds.value = 0
  }, 10000)

  healthTimer = setInterval(() => {
    healthScore.value = Math.round(clamp(healthScore.value + randomInt(-1, 1), 52, 96))
    buildingSummaries.value = buildingSummaries.value.map((summary) => ({
      ...summary,
      efficiencyScore: Math.round(clamp(summary.efficiencyScore + randomBetween(-1, 1), 55, 98)),
    }))
  }, 20000)

  scheduleTableInsert()
  scheduleRandomEvent()
  document.addEventListener('pointerover', handleTooltipShow)
  document.addEventListener('pointermove', handleTooltipMove)
  document.addEventListener('pointerout', handleTooltipHide)
})

onUnmounted(() => {
  if (clockTimer) clearInterval(clockTimer)
  if (syncTimer) clearInterval(syncTimer)
  if (scanTimer) clearInterval(scanTimer)
  if (liveMetricTimer) clearInterval(liveMetricTimer)
  if (hourlyTimer) clearInterval(hourlyTimer)
  if (energyTimer) clearInterval(energyTimer)
  if (healthTimer) clearInterval(healthTimer)
  if (tableTimer) clearTimeout(tableTimer)
  if (eventTimer) clearTimeout(eventTimer)
  if (tooltipHideTimer) clearTimeout(tooltipHideTimer)
  document.removeEventListener('pointerover', handleTooltipShow)
  document.removeEventListener('pointermove', handleTooltipMove)
  document.removeEventListener('pointerout', handleTooltipHide)
})

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value))
}

function randomBetween(min: number, max: number) {
  return min + Math.random() * (max - min)
}

function randomInt(min: number, max: number) {
  return Math.floor(randomBetween(min, max + 1))
}

function stableRatio(seed: string, min: number, max: number) {
  let hash = 0
  for (let index = 0; index < seed.length; index += 1) {
    hash = (hash * 31 + seed.charCodeAt(index)) % 9973
  }
  return min + (hash / 9973) * (max - min)
}

function formatNumber(value: number, decimals = 1) {
  return value.toLocaleString('zh-CN', {
    maximumFractionDigits: decimals,
    minimumFractionDigits: decimals,
  })
}

function compactKwh(value: number, decimals = 1) {
  return `${formatNumber(value, decimals)} kWh`
}

function parseMetricValue(value: string) {
  const match = value.match(/^(-?\d+(?:\.\d+)?)(.*)$/)
  if (!match) return { decimals: 0, numeric: 0, suffix: value }
  const numericPart = match[1] ?? '0'

  return {
    decimals: numericPart.includes('.') ? 1 : 0,
    numeric: Number(numericPart),
    suffix: match[2] ?? '',
  }
}

function metricToneColor(tone: Tone) {
  if (tone === 'amber') return colors.amber
  if (tone === 'emerald') return colors.emerald
  if (tone === 'rose') return colors.rose
  return colors.cyan
}

function statusToneColor(tone: StatusTone) {
  if (tone === 'amber') return colors.amber
  if (tone === 'emerald') return colors.emerald
  if (tone === 'rose') return colors.rose
  return colors.slate
}

function statusText(status: MonitoringRecord['device_status']) {
  if (status === 'warning') return '预警'
  if (status === 'maintenance') return '维护'
  if (status === 'offline') return '离线'
  return '正常'
}

function buildingTypeReadable(type: string) {
  if (type === 'lab') return '实验楼'
  if (type === 'mixed-use') return '综合楼'
  if (type === 'office') return '办公楼'
  if (type === 'teaching') return '教学楼'
  return type
}

function buildingTypeText(type: string) {
  if (type === 'office') return '办公'
  if (type === 'teaching') return '教学'
  if (type === 'lab') return '实验'
  return '综合'
}

function scatterTypeLabel(tone: Tone) {
  if (tone === 'amber') return '实验'
  if (tone === 'emerald') return '教学'
  if (tone === 'rose') return '预警'
  return '办公'
}

function axisExtent(values: number[], fallbackMin = 0, fallbackMax = 1) {
  if (values.length === 0) return { max: fallbackMax, min: fallbackMin }
  const min = Math.min(...values)
  const max = Math.max(...values)
  if (min === max) return { max: max + 1, min: min - 1 }
  const padding = (max - min) * 0.12
  return { max: max + padding, min: Math.max(0, min - padding) }
}

function buildAxisTicks(minValue: number, maxValue: number, count = 4) {
  const step = (maxValue - minValue) / count
  return Array.from({ length: count + 1 }, (_, index) => minValue + step * index)
}

function axisY(value: number, minValue: number, maxValue: number, height: number, padding: number) {
  const ratio = (value - minValue) / Math.max(maxValue - minValue, 1)
  return height - padding - ratio * (height - padding * 2)
}

function axisX(index: number, total: number, width: number, padding: number) {
  if (total <= 1) return width / 2
  return padding + (index / (total - 1)) * (width - padding * 2)
}

function axisTickLabel(value: number, decimals = 0) {
  return value.toLocaleString('zh-CN', {
    maximumFractionDigits: decimals,
    minimumFractionDigits: decimals,
  })
}

function buildBars(values: number[], width: number, height: number, padding: number): BarShape[] {
  const maxValue = Math.max(...values, 1)
  const slot = (width - padding * 2) / Math.max(values.length, 1)
  const barWidth = Math.min(38, slot * 0.54)

  return values.map((value, index) => {
    const barHeight = (value / maxValue) * (height - padding * 2)
    const x = padding + index * slot + (slot - barWidth) / 2
    const y = height - padding - barHeight
    return { height: barHeight, width: barWidth, x, y }
  })
}

function buildLinePoints(
  values: number[],
  width: number,
  height: number,
  padding: number,
  min = Math.min(...values),
  max = Math.max(...values),
): ChartPoint[] {
  return values.map((value, index) => ({
    x: axisX(index, values.length, width, padding),
    y: axisY(value, min, max, height, padding),
  }))
}

function buildLinePath(points: ChartPoint[]) {
  if (points.length === 0) return ''
  return points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x} ${point.y}`).join(' ')
}

function buildAreaPath(points: ChartPoint[], height: number, padding: number) {
  if (points.length === 0) return ''
  const first = points[0]
  const last = points[points.length - 1]
  if (!first || !last) return ''
  return `${buildLinePath(points)} L ${last.x} ${height - padding} L ${first.x} ${height - padding} Z`
}

function polarToCartesian(cx: number, cy: number, radius: number, angle: number) {
  const radians = (angle * Math.PI) / 180
  return {
    x: cx + radius * Math.cos(radians),
    y: cy + radius * Math.sin(radians),
  }
}

function describeDonutArc(
  cx: number,
  cy: number,
  outerRadius: number,
  innerRadius: number,
  startAngle: number,
  endAngle: number,
) {
  const largeArcFlag = endAngle - startAngle <= 180 ? 0 : 1
  const outerStart = polarToCartesian(cx, cy, outerRadius, startAngle)
  const outerEnd = polarToCartesian(cx, cy, outerRadius, endAngle)
  const innerStart = polarToCartesian(cx, cy, innerRadius, endAngle)
  const innerEnd = polarToCartesian(cx, cy, innerRadius, startAngle)

  return [
    `M ${outerStart.x} ${outerStart.y}`,
    `A ${outerRadius} ${outerRadius} 0 ${largeArcFlag} 1 ${outerEnd.x} ${outerEnd.y}`,
    `L ${innerStart.x} ${innerStart.y}`,
    `A ${innerRadius} ${innerRadius} 0 ${largeArcFlag} 0 ${innerEnd.x} ${innerEnd.y}`,
    'Z',
  ].join(' ')
}

function heatmapCell(date: string, hour: string) {
  return heatmap.find((item) => item.date === date && item.hour === hour)
}

function heatmapColumnStats(hour: string) {
  const cells = heatmap.filter((cell) => cell.hour === hour)
  const fallback: HeatmapCell = { date: '-', electricity: 0, hour, intensity: 0, occupancy: 0 }
  const maxCell = cells.reduce((best, cell) => (cell.electricity > best.electricity ? cell : best), fallback)
  const minCell = cells.reduce((best, cell) => (cell.electricity < best.electricity ? cell : best), fallback)
  const average = cells.reduce((sum, cell) => sum + cell.electricity, 0) / Math.max(cells.length, 1)
  return { average, maxCell, minCell }
}

function heatmapRowStats(date: string) {
  const cells = heatmap.filter((cell) => cell.date === date)
  const fallback: HeatmapCell = { date, electricity: 0, hour: '-', intensity: 0, occupancy: 0 }
  const maxCell = cells.reduce((best, cell) => (cell.electricity > best.electricity ? cell : best), fallback)
  const total = cells.reduce((sum, cell) => sum + cell.electricity, 0)
  return { maxCell, total }
}

function heatmapCellState(date: string, hour: string) {
  const hovered = hoveredHeatmap.value
  const key = `${date}-${hour}`
  const isHovered = hovered?.mode === 'cell' && hovered.date === date && hovered.hour === hour
  const isRelated =
    hovered !== null &&
    ((hovered.date === date && hovered.mode !== 'column') ||
      (hovered.hour === hour && hovered.mode !== 'row'))
  return {
    dimmed: hovered !== null && !isHovered && !isRelated,
    hovered: isHovered,
    related: isRelated,
    sampled: sampledHeatmapKey.value === key,
  }
}

function heatmapStyle(cell: HeatmapCell | undefined) {
  const intensity = cell?.intensity ?? 0
  const hue = intensity > 0.82 ? '255,184,0' : intensity > 0.66 ? '34,211,160' : '0,212,255'
  const alpha = clamp(0.16 + intensity * 0.58, 0.16, 0.74)
  return {
    '--cell-fill': `linear-gradient(135deg, rgba(${hue},${alpha}) 0%, rgba(${hue},${alpha * 0.46}) 42%, rgba(3,21,38,0.18) 100%)`,
    '--cell-meter': `${Math.round(intensity * 100)}%`,
    '--cell-tone': `rgba(${hue},0.82)`,
    borderColor:
      cell === peakHeatmapCell.value ? 'rgba(255, 184, 0, 0.78)' : `rgba(0, 212, 255, ${0.2 + intensity * 0.42})`,
  }
}

function describeCorrelation(value: number) {
  if (value > 0.7) return '强正相关'
  if (value > 0.4) return '中等正相关'
  return '弱相关'
}

function scatterRiskSamples(chart: ReturnType<typeof makeScatterChart>) {
  return chart.points.filter((point) => point.electricity > 170 && (point.xValue > 70 || point.tone === 'rose')).length
}

function scatterPointDimmed(point: ScatterChartPoint, chartTitle: string) {
  if (hoveredScatterTone.value !== null && hoveredScatterTone.value !== point.tone) return true
  if (hoveredScatterRiskChart.value !== chartTitle) return false
  return !(point.electricity > 170 && (point.xValue > 70 || point.tone === 'rose'))
}

function scatterPointHighlighted(point: ScatterChartPoint, chartTitle: string) {
  if (hoveredScatterTone.value === point.tone) return true
  if (hoveredScatterRiskChart.value !== chartTitle) return false
  return point.electricity > 170 && (point.xValue > 70 || point.tone === 'rose')
}

function buildScatterTrendLine(
  points: ScatterChartPoint[],
  padding: number,
  width: number,
  height: number,
): ScatterTrendLine | null {
  if (points.length < 2) return null

  const count = points.length
  const sumX = points.reduce((sum, point) => sum + point.cx, 0)
  const sumY = points.reduce((sum, point) => sum + point.cy, 0)
  const sumXY = points.reduce((sum, point) => sum + point.cx * point.cy, 0)
  const sumXX = points.reduce((sum, point) => sum + point.cx * point.cx, 0)
  const denominator = count * sumXX - sumX * sumX

  if (Math.abs(denominator) < 0.001) return null

  const slope = (count * sumXY - sumX * sumY) / denominator
  const intercept = (sumY - slope * sumX) / count
  const x1 = padding
  const x2 = width - padding

  return {
    x1,
    x2,
    y1: clamp(slope * x1 + intercept, padding, height - padding),
    y2: clamp(slope * x2 + intercept, padding, height - padding),
  }
}

function makeScatterChart({
  correlation,
  title,
  xKey,
  xLabel,
}: {
  correlation: number
  title: string
  xKey: 'occupancy' | 'temperature'
  xLabel: string
}) {
  const width = 720
  const height = 330
  const padding = 52
  const visiblePoints = scatterPoints.filter((point) => !hiddenTones.value.has(point.tone))
  const sourcePoints = visiblePoints.length > 0 ? visiblePoints : scatterPoints
  const xValues = sourcePoints.map((point) => point[xKey])
  const yValues = sourcePoints.map((point) => point.electricity)
  const xExtent = axisExtent(xValues)
  const yExtent = axisExtent(yValues)
  const points: ScatterChartPoint[] = sourcePoints.map((point) => ({
    ...point,
    cx: padding + ((point[xKey] - xExtent.min) / Math.max(xExtent.max - xExtent.min, 1)) * (width - padding * 2),
    cy: axisY(point.electricity, yExtent.min, yExtent.max, height, padding),
    xValue: point[xKey],
  }))
  const trendLine = buildScatterTrendLine(points, padding, width, height)

  return {
    correlation,
    height,
    id: title.includes('温度') ? 'temperature' : 'occupancy',
    padding,
    points,
    title,
    trendLine,
    width,
    xExtent,
    xLabel,
    xTicks: [...buildAxisTicks(xExtent.min, xExtent.max, 6)].reverse(),
    yExtent,
    yLabel: '能耗 kWh',
    yTicks: buildAxisTicks(yExtent.min, yExtent.max, 6),
  }
}

function toggleTone(tone: Tone) {
  const next = new Set(hiddenTones.value)
  if (next.has(tone)) {
    next.delete(tone)
  } else {
    next.add(tone)
  }
  hiddenTones.value = next
}

function metricIcon(tone: Tone) {
  if (tone === 'amber') return 'M20.5 9 13 22h5l-.5 9L27 17h-5.6l-.9-8Z'
  if (tone === 'emerald') return 'M14 15a3 3 0 1 0 0.1 0M26 24a3 3 0 1 0 0.1 0M16.6 16.8 23.4 22.2'
  if (tone === 'rose') return 'M20 10.5 29 27H11l9-16.5ZM20 16.5v5.5M20 25.8v.2'
  return 'M13 27.5v-13l7-3.5 7 3.5v13M16.5 17.5h1.8M21.7 17.5h1.8M11 27.5h18'
}

function metricIconFilled(tone: Tone) {
  return tone === 'amber'
}

function scatterPointColor(tone: Tone) {
  return metricToneColor(tone)
}

function ratio(value: number, total: number) {
  return total === 0 ? 0 : (value / total) * 100
}
</script>

<template>
  <main class="data-page">
    <div class="data-video-bg" aria-hidden="true">
      <video autoplay muted loop playsinline preload="auto">
        <source :src="bgVideo" type="video/mp4" />
      </video>
      <div class="data-video-overlay" />
    </div>
    <div class="cockpit-atmosphere" />

    <header class="data-header">
      <nav class="cyber-nav-shell" aria-label="Workspace navigation">
        <RouterLink class="cyber-tab" to="/">能耗查询</RouterLink>
        <RouterLink class="cyber-tab cyber-tab-active" to="/data-analysis">数据分析</RouterLink>
        <button class="cyber-tab" type="button">智慧运维</button>
      </nav>

      <div class="title-plate">
        <svg aria-hidden="true" preserveAspectRatio="none" viewBox="0 0 620 58">
          <defs>
            <linearGradient id="dataTitleStrokeVue" x1="0" x2="1" y1="0" y2="0">
              <stop offset="0%" stop-color="#034d7a" stop-opacity="0" />
              <stop offset="16%" stop-color="#00d4ff" stop-opacity="0.76" />
              <stop offset="50%" stop-color="#7af7ff" stop-opacity="0.95" />
              <stop offset="84%" stop-color="#00d4ff" stop-opacity="0.76" />
              <stop offset="100%" stop-color="#034d7a" stop-opacity="0" />
            </linearGradient>
            <linearGradient id="dataTitleFillVue" x1="0" x2="0" y1="0" y2="1">
              <stop offset="0%" stop-color="#0a2540" stop-opacity="0.68" />
              <stop offset="100%" stop-color="#020817" stop-opacity="0.08" />
            </linearGradient>
          </defs>
          <path
            d="M48 6H572L606 29L572 52H48L14 29L48 6Z"
            fill="url(#dataTitleFillVue)"
            stroke="url(#dataTitleStrokeVue)"
            stroke-width="1.8"
          />
          <path d="M118 14H502M118 44H502" stroke="url(#dataTitleStrokeVue)" stroke-linecap="round" stroke-width="1.4" />
          <path d="M54 14H96M524 14H566M54 44H96M524 44H566" stroke="#7af7ff" stroke-linecap="round" stroke-width="2.2" />
          <path d="M78 7L52 29L78 51M542 7L568 29L542 51" fill="none" opacity="0.55" stroke="#00d4ff" stroke-width="1.2" />
        </svg>
        <div class="title-plate-text">建筑能耗管理与运维系统</div>
      </div>

      <div class="data-header-status">
        <div
          class="data-status-cell data-status-online"
          v-bind="
            tooltipAttrs({
              rows: [
                { label: '系统运行时长', value: '18 天 06:42' },
                { label: '在线设备数', value: '286 / 292' },
                { label: 'CPU / 内存', value: '38% / 61%' },
                { label: '最近心跳', value: `${syncSeconds} 秒前` },
              ],
              title: '系统在线状态',
            })
          "
        >
          <span class="data-status-dot" />
          <div>
            <div class="data-status-kicker">系统在线</div>
            <div class="data-status-value text-emerald">健康运行</div>
          </div>
        </div>
        <span class="data-status-divider" />
        <div
          class="data-status-cell text-right"
          v-bind="
            tooltipAttrs({
              rows: [
                { label: '服务器时间', value: timeText },
                { label: '时区', value: 'Asia/Shanghai' },
                { label: 'NTP 同步', value: '正常' },
              ],
              title: '时间同步状态',
            })
          "
        >
          <div>
            <div class="data-status-kicker">{{ dateText }}</div>
            <div class="status-time">{{ timeText }}</div>
          </div>
        </div>
        <span class="data-status-divider" />
        <div
          class="data-status-cell data-status-weather"
          v-bind="
            tooltipAttrs({
              rows: [
                { label: '天气', value: '多云' },
                { label: '室外温度', value: '26°C' },
                { label: '体感温度', value: '27°C' },
                { label: '数据来源', value: '本地气象站' },
              ],
              title: '室外天气',
            })
          "
        >
          <span class="weather-glyph" aria-hidden="true" />
          <div>
            <div class="data-status-kicker">多云</div>
            <div class="data-status-value text-cyan">26°C</div>
          </div>
        </div>
        <button
          class="data-status-icon-btn data-status-bell"
          type="button"
          v-bind="
            tooltipAttrs({
              rows: [
                { label: '活动告警', tone: 'rose', value: `${warningCount} 条` },
                { label: '待处理问题', value: '1 个' },
                { label: '最近事件', value: toasts[0]?.title ?? '暂无' },
              ],
              title: '通知中心',
            })
          "
        >
          <svg aria-hidden="true" viewBox="0 0 24 24">
            <path d="M12 3.5a5.5 5.5 0 0 0-5.5 5.5v3.4L5 15v1h14v-1l-1.5-2.6V9A5.5 5.5 0 0 0 12 3.5Zm-2.2 14a2.3 2.3 0 0 0 4.4 0h-4.4Z" fill="currentColor" />
          </svg>
        </button>
        <button
          class="data-status-icon-btn data-status-gear"
          type="button"
          v-bind="
            tooltipAttrs({
              rows: [
                { label: '刷新策略', value: '实时 / 当前时段 / 累计分层' },
                { label: '动效模式', value: 'CSS HUD' },
                { label: '数据源', value: 'mock 实时模型' },
              ],
              title: '页面设置',
            })
          "
        >
          <svg aria-hidden="true" viewBox="0 0 24 24">
            <path d="M19.4 13.5a7.8 7.8 0 0 0 0-3l2-1.5-2-3.4-2.4 1a7.3 7.3 0 0 0-2.6-1.5L14 2.5h-4l-.4 2.6A7.3 7.3 0 0 0 7 6.6l-2.4-1-2 3.4 2 1.5a7.8 7.8 0 0 0 0 3l-2 1.5 2 3.4 2.4-1a7.3 7.3 0 0 0 2.6 1.5l.4 2.6h4l.4-2.6a7.3 7.3 0 0 0 2.6-1.5l2.4 1 2-3.4-2-1.5ZM12 8.5a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7Z" fill="currentColor" />
          </svg>
        </button>
      </div>
    </header>

    <section
      class="realtime-sync-widget"
      v-bind="
        tooltipAttrs({
          rows: [
            { label: '最近刷新', value: `${syncSeconds} 秒前` },
            { label: '采样总数', value: `${sampleCount}` },
            { label: '采样速率', value: `${sampleRate} 条/分钟` },
          ],
          title: '实时同步状态',
        })
      "
    >
      <span class="sync-radar" />
      <span class="sync-copy">
        <span>实时同步中</span>
        <small>{{ syncSeconds }} 秒前刷新</small>
      </span>
    </section>

    <aside class="dashboard-toast-stack" aria-live="polite">
      <div
        v-for="toast in toasts"
        :key="toast.id"
        class="dashboard-toast"
        :data-tone="toast.tone"
        v-bind="
          tooltipAttrs({
            rows: [
              { label: '事件类型', value: toast.title },
              { label: '同步时间', value: `${syncSeconds} 秒前` },
            ],
            title: '事件通知',
          })
        "
      >
        <div class="dashboard-toast-title">{{ toast.title }}</div>
        <div class="dashboard-toast-body">{{ toast.body }}</div>
      </div>
    </aside>

    <div class="data-content">
      <section class="metric-grid">
        <article
          v-for="metric in metrics"
          :key="metric.label"
          class="hud-drawn-card hud-drawn-card-kpi data-analysis-card-frame metric-card-interactive"
          v-bind="tooltipAttrs(metricTooltip(metric))"
        >
          <div class="metric-card-content">
            <div
              class="metric-card-icon"
              :style="{
                background: `radial-gradient(circle, ${metricToneColor(metric.tone)} 0%, rgba(0,0,0,0) 62%)`,
                boxShadow: `0 0 22px ${metricToneColor(metric.tone)}55`,
              }"
            >
              <span class="hud-glyph" :style="{ color: metricToneColor(metric.tone) }">
                <svg viewBox="0 0 40 40">
                  <path
                    d="M20 2.8 34.8 11.4v17.2L20 37.2 5.2 28.6V11.4L20 2.8Z"
                    fill="currentColor"
                    opacity="0.12"
                    stroke="currentColor"
                    stroke-opacity="0.55"
                    stroke-width="1.5"
                  />
                  <path
                    :d="metricIcon(metric.tone)"
                    :fill="metricIconFilled(metric.tone) ? 'currentColor' : 'none'"
                    stroke="currentColor"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2.2"
                  />
                </svg>
              </span>
            </div>
            <div class="metric-copy">
              <div
                class="live-number metric-value"
                :data-flash="metricFlash[metric.label]"
                :style="{ color: metricToneColor(metric.tone) }"
              >
                {{ formatNumber(parseMetricValue(metric.value).numeric, parseMetricValue(metric.value).decimals) }}
                <span class="live-number-unit">{{ parseMetricValue(metric.value).suffix }}</span>
                <span
                  v-if="metricDeltas[metric.label]"
                  class="live-number-delta"
                  :data-tone="metricDeltas[metric.label]?.tone"
                >
                  {{ metricDeltas[metric.label]?.value }}
                </span>
              </div>
              <div class="metric-label">{{ metric.label }}</div>
              <svg class="metric-mini-trend" viewBox="0 0 160 28" aria-hidden="true">
                <polyline
                  fill="none"
                  :points="metricTrendPolyline(metric.label)"
                  stroke="rgba(122,247,255,0.72)"
                  stroke-width="2"
                />
                <circle :cx="metricTrendLastPoint(metric.label).x" :cy="metricTrendLastPoint(metric.label).y" fill="#7af7ff" r="3" />
              </svg>
            </div>
          </div>
        </article>
      </section>

      <section class="data-analysis-main-grid">
        <div class="data-analysis-column">
          <article class="hud-drawn-card data-analysis-card-frame panel-card">
            <header class="hud-panel-titlebar">
              <div class="titlebar-inner">
                <span class="hud-title-mark" />
                <span class="hud-glyph">
                  <svg viewBox="0 0 40 40"><path d="M12 22a8 8 0 1 1 16 0M20 22 25 15.5M14.5 25.5h11" fill="none" stroke="currentColor" stroke-linecap="round" stroke-width="2.2" /></svg>
                </span>
                <h2>运行健康评分</h2>
                <span class="titlebar-deco"><i /><i /><i /></span>
              </div>
            </header>

            <div class="health-layout">
              <div
                class="health-gauge-interactive"
                :style="healthGaugeStyle"
                v-bind="tooltipAttrs(healthGaugeTooltip(healthScore))"
              >
                <span class="health-gauge-orbit" />
                <div class="gauge-inner" />
                <div class="live-number gauge-value">{{ healthScore }}</div>
              </div>
              <div class="health-bars">
                <div
                  v-for="bucket in statusDistribution"
                  :key="bucket.label"
                  class="health-bar-item"
                  v-bind="
                    tooltipAttrs(
                      statusBucketTooltip(bucket, ratio(bucket.count, statusTotal), buildingSummaries),
                    )
                  "
                >
                  <div class="health-row">
                    <span>
                      <i :style="{ backgroundColor: statusToneColor(bucket.tone), color: statusToneColor(bucket.tone) }" />
                      {{ bucket.label }}
                    </span>
                    <b>{{ bucket.count }} / {{ ratio(bucket.count, statusTotal).toFixed(1) }}%</b>
                  </div>
                  <div class="health-bar-track">
                    <div
                      class="health-bar-fill"
                      :style="{
                        width: `${ratio(bucket.count, statusTotal)}%`,
                        backgroundColor: statusToneColor(bucket.tone),
                        color: statusToneColor(bucket.tone),
                      }"
                    />
                  </div>
                </div>
              </div>
            </div>

            <div class="health-summary-grid">
              <div
                v-bind="
                  tooltipAttrs({
                    rows: [
                      { label: '正常记录', value: `${normalCount} 条` },
                      { label: '占比', value: `${ratio(normalCount, statusTotal).toFixed(1)}%` },
                    ],
                    title: '正常记录',
                  })
                "
              >
                <span>正常记录</span>
                <b class="text-emerald">{{ normalCount }}</b>
              </div>
              <div
                v-bind="
                  tooltipAttrs({
                    rows: [
                      { label: '预警记录', tone: 'rose', value: `${warningCount} 条` },
                      { label: '待处理', tone: 'amber', value: `${Math.max(1, Math.round(warningCount * 0.28))} 条` },
                    ],
                    title: '预警记录',
                  })
                "
              >
                <span>预警记录</span>
                <b class="text-rose">{{ warningCount }}</b>
              </div>
              <div
                v-bind="
                  tooltipAttrs({
                    rows: [
                      { label: '监测总量', value: `${statusTotal} 条` },
                      { label: '在线口径', value: '正常 / 预警 / 维护 / 离线' },
                    ],
                    title: '监测总量',
                  })
                "
              >
                <span>监测总量</span>
                <b>{{ statusTotal }}</b>
              </div>
            </div>

            <div class="health-ratio-grid">
              <div
                class="health-ratio-card"
                v-bind="
                  tooltipAttrs(
                    ratioTooltip(
                      '风险占比',
                      `${ratio(riskCount, statusTotal).toFixed(1)}%`,
                      riskCount,
                      statusTotal,
                    ),
                  )
                "
              >
                <span>风险占比</span>
                <b class="text-rose">{{ ratio(riskCount, statusTotal).toFixed(1) }}%</b>
              </div>
              <div
                class="health-ratio-card"
                v-bind="
                  tooltipAttrs(
                    ratioTooltip(
                      '稳定占比',
                      `${ratio(normalCount, statusTotal).toFixed(1)}%`,
                      normalCount,
                      statusTotal,
                    ),
                  )
                "
              >
                <span>稳定占比</span>
                <b class="text-emerald">{{ ratio(normalCount, statusTotal).toFixed(1) }}%</b>
              </div>
              <div
                class="health-ratio-card"
                v-bind="
                  tooltipAttrs(
                    ratioTooltip(
                      '维护占比',
                      `${ratio(maintenanceCount, statusTotal).toFixed(1)}%`,
                      maintenanceCount,
                      statusTotal,
                    ),
                  )
                "
              >
                <span>维护占比</span>
                <b class="text-amber">{{ ratio(maintenanceCount, statusTotal).toFixed(1) }}%</b>
              </div>
            </div>
          </article>

          <article class="hud-drawn-card data-analysis-card-frame panel-card">
            <header class="hud-panel-titlebar">
              <div class="titlebar-inner">
                <span class="hud-title-mark" />
                <span class="hud-glyph text-amber">
                  <svg viewBox="0 0 40 40"><path d="M20.5 9 13 22h5l-.5 9L27 17h-5.6l-.9-8Z" fill="currentColor" opacity="0.74" /></svg>
                </span>
                <h2>峰值设备快照</h2>
                <span class="titlebar-deco"><i /><i /><i /></span>
              </div>
            </header>
            <div
              class="peak-card"
              v-bind="
                tooltipAttrs({
                  rows: [
                    { label: '峰值楼栋', value: 'BLDG-C-07' },
                    { label: '峰值设备 ID', value: 'BLDG-C-07-DEV-18' },
                    { label: '记录时间', value: peakPoint.hour },
                    { label: '峰值能耗', tone: 'amber', value: compactKwh(peakPoint.electricity) },
                  ],
                  title: '峰值设备快照',
                })
              "
            >
              <span>BLDG-C-07</span>
              <b class="peak-device-id">BLDG-C-07-DEV-18</b>
              <div class="peak-grid">
                <div
                  class="peak-device-metric"
                  v-bind="
                    tooltipAttrs({
                      rows: [
                        { label: '当前峰值', tone: 'amber', value: compactKwh(peakPoint.electricity) },
                        { label: '参考阈值', value: '180 kWh' },
                      ],
                      title: '峰值能耗',
                    })
                  "
                >
                  <span>峰值能耗</span>
                  <b>{{ compactKwh(peakPoint.electricity) }}</b>
                </div>
                <div
                  class="peak-device-metric"
                  v-bind="
                    tooltipAttrs({
                      rows: [
                        { label: '当前温度', value: `${peakPoint.temperature.toFixed(1)} °C` },
                        { label: '舒适区间', value: '22-28 °C' },
                      ],
                      title: '环境温度',
                    })
                  "
                >
                  <span>环境温度</span>
                  <b>{{ peakPoint.temperature.toFixed(1) }} °C</b>
                </div>
                <div
                  class="peak-device-metric"
                  v-bind="
                    tooltipAttrs({
                      rows: [
                        { label: '人流指数', value: peakPoint.occupancy.toFixed(1) },
                        { label: '人流峰值', value: busiestPoint.occupancy.toFixed(1) },
                      ],
                      title: '人流指数',
                    })
                  "
                >
                  <span>人流指数</span>
                  <b>{{ peakPoint.occupancy.toFixed(1) }}</b>
                </div>
                <div
                  class="peak-device-metric"
                  v-bind="
                    tooltipAttrs({
                      rows: [
                        { label: '记录时段', value: peakPoint.hour },
                        { label: '采样口径', value: '当前时段均值' },
                      ],
                      title: '记录时间',
                    })
                  "
                >
                  <span>记录时间</span>
                  <b>{{ peakPoint.hour }}</b>
                </div>
              </div>
            </div>
            <div class="related-list">
              <div
                v-for="record in records.slice(0, 3)"
                :key="record.id"
                class="peak-device-related"
                v-bind="tooltipAttrs(tableRowTooltip(record))"
              >
                <div>
                  <b>{{ record.device_id }}</b>
                  <span>{{ record.building_id }} · {{ statusText(record.device_status) }}</span>
                </div>
                <strong>{{ record.electricity_kwh.toFixed(1) }}</strong>
              </div>
            </div>
          </article>

          <article class="hud-drawn-card data-analysis-card-frame panel-card">
            <header class="hud-panel-titlebar">
              <div class="titlebar-inner">
                <span class="hud-title-mark" />
                <span class="hud-glyph text-rose">
                  <svg viewBox="0 0 40 40"><path d="M20 10.5 29 27H11l9-16.5ZM20 16.5v5.5M20 25.8v.2" fill="none" stroke="currentColor" stroke-linecap="round" stroke-width="2.2" /></svg>
                </span>
                <h2>风险分层</h2>
                <span class="titlebar-deco"><i /><i /><i /></span>
              </div>
            </header>
            <div class="risk-stat-grid">
              <div
                class="risk-stat-card"
                v-bind="
                  tooltipAttrs({
                    rows: [
                      { label: '预警记录', tone: 'rose', value: `${warningCount} 条` },
                      { label: '趋势', tone: 'amber', value: '今日累计只增不减' },
                    ],
                    title: '预警记录',
                  })
                "
              >
                <span>预警记录</span>
                <b class="text-rose">{{ warningCount }}</b>
              </div>
              <div
                class="risk-stat-card"
                v-bind="
                  tooltipAttrs({
                    rows: [
                      { label: '维护中', value: `${maintenanceCount} 条` },
                      { label: '建议动作', value: '排队复核设备巡检状态' },
                    ],
                    title: '维护中记录',
                  })
                "
              >
                <span>维护中</span>
                <b class="text-amber">{{ maintenanceCount }}</b>
              </div>
            </div>
            <div class="risk-list">
              <div
                v-for="(summary, index) in buildingSummaries"
                :key="summary.buildingId"
                class="risk-row"
                :data-rank="index + 1"
                v-bind="tooltipAttrs(riskRowTooltip(summary, index + 1))"
              >
                <div class="risk-row-head">
                  <div>
                    <span class="risk-rank">{{ index + 1 }}</span>
                    <div>
                      <b>{{ summary.buildingId }}</b>
                      <small>效率 {{ summary.efficiencyScore }} · {{ buildingTypeText(summary.buildingType) }}</small>
                    </div>
                  </div>
                  <strong>{{ summary.warningCount }}</strong>
                </div>
                <div class="risk-progress">
                  <div
                    class="risk-row-progress"
                    :style="{ width: `${clamp(summary.warningCount * 22, 16, 100)}%` }"
                  />
                </div>
              </div>
            </div>
          </article>
        </div>

        <div class="data-analysis-column">
          <article class="hud-drawn-card data-analysis-card-frame panel-card panel-card-large">
            <header class="hud-panel-titlebar">
              <div class="titlebar-inner">
                <span class="hud-title-mark" />
                <span class="hud-glyph">
                  <svg viewBox="0 0 40 40"><path d="M11 25 16 20l4 3 8-9M27 14h-5M27 14v5" fill="none" stroke="currentColor" stroke-linecap="round" stroke-width="2.4" /></svg>
                </span>
                <h2>每日能耗与人流走势</h2>
                <span class="titlebar-deco"><i /><i /><i /></span>
              </div>
            </header>
            <div class="pill-row">
              <span class="hud-pill">电耗柱状</span>
              <span class="hud-pill hud-pill-emerald">人流折线</span>
              <span class="hud-pill hud-pill-neutral">12 天窗口</span>
            </div>
            <div class="tech-chart-frame">
              <svg class="chart-svg" :viewBox="`0 0 ${dailyChart.width} ${dailyChart.height}`">
                <defs>
                  <linearGradient id="dailyBarFillVue" x1="0" x2="0" y1="0" y2="1">
                    <stop offset="0%" stop-color="#7af7ff" />
                    <stop offset="48%" stop-color="#00d4ff" />
                    <stop offset="100%" stop-color="#006ca8" />
                  </linearGradient>
                  <linearGradient id="dailyOccupancyFillVue" x1="0" x2="0" y1="0" y2="1">
                    <stop offset="0%" stop-color="#22d3a0" stop-opacity="0.22" />
                    <stop offset="100%" stop-color="#22d3a0" stop-opacity="0" />
                  </linearGradient>
                </defs>
                <g v-for="tick in dailyChart.electricityTicks" :key="`daily-y-${tick}`">
                  <line stroke="rgba(141,168,197,0.22)" stroke-dasharray="5 8" :x1="dailyChart.padding" :x2="dailyChart.width - dailyChart.padding" :y1="axisY(tick, 0, dailyChart.maxElectricity, dailyChart.height, dailyChart.padding)" :y2="axisY(tick, 0, dailyChart.maxElectricity, dailyChart.height, dailyChart.padding)" />
                  <text class="axis-text" text-anchor="end" :x="dailyChart.padding - 9" :y="axisY(tick, 0, dailyChart.maxElectricity, dailyChart.height, dailyChart.padding)">{{ axisTickLabel(tick) }}</text>
                </g>
                <line stroke="rgba(122,247,255,0.4)" :x1="dailyChart.padding" :x2="dailyChart.padding" :y1="dailyChart.padding" :y2="dailyChart.height - dailyChart.padding" />
                <line stroke="rgba(122,247,255,0.4)" :x1="dailyChart.padding" :x2="dailyChart.width - dailyChart.padding" :y1="dailyChart.height - dailyChart.padding" :y2="dailyChart.height - dailyChart.padding" />
                <text class="axis-title" :x="dailyChart.padding" :y="dailyChart.padding - 18">用电量 kWh</text>
                <text class="axis-title" text-anchor="end" :x="dailyChart.width - dailyChart.padding" :y="dailyChart.padding - 18">人流指数</text>
                <text v-for="(point, index) in dailySeries" :key="point.date" class="axis-text" text-anchor="middle" :x="axisX(index, dailySeries.length, dailyChart.width, dailyChart.padding)" :y="dailyChart.height - dailyChart.padding + 23">{{ point.date }}</text>
                <g
                  v-for="(bar, index) in dailyChart.bars"
                  :key="dailySeries[index]?.date"
                  v-bind="tooltipAttrs(dailyTooltip(dailySeries[index], dailySeries[index - 1]))"
                >
                  <rect
                    class="daily-chart-bar"
                    :data-dimmed="hoveredDailyIndex !== null && hoveredDailyIndex !== index"
                    :data-hovered="hoveredDailyIndex === index"
                    :data-scan="scanIndex === index"
                    fill="url(#dailyBarFillVue)"
                    :height="bar.height"
                    rx="6"
                    :width="bar.width"
                    :x="bar.x"
                    :y="bar.y"
                    @pointerenter="hoveredDailyIndex = index"
                    @pointerleave="hoveredDailyIndex = null"
                  />
                  <circle class="daily-bar-top-glow" fill="#7af7ff" r="3.5" :cx="bar.x + bar.width / 2" :cy="bar.y + 2" />
                  <rect class="daily-bar-flow-line" :height="Math.max(10, bar.height * 0.42)" width="2.4" :x="bar.x + bar.width * 0.62" :y="bar.y + bar.height * 0.58" />
                </g>
                <path :d="dailyChart.areaPath" fill="url(#dailyOccupancyFillVue)" />
                <path :d="dailyChart.linePath" fill="none" stroke="#22d3a0" stroke-linecap="round" stroke-linejoin="round" stroke-width="3.2" />
                <circle
                  v-for="(point, index) in dailyChart.occupancyPoints"
                  :key="`daily-line-${index}`"
                  class="daily-line-node"
                  :data-hovered="hoveredDailyIndex === index"
                  fill="#061829"
                  :r="hoveredDailyIndex === index ? 6.75 : 4.5"
                  stroke="#22d3a0"
                  stroke-width="2"
                  :cx="point.x"
                  :cy="point.y"
                  v-bind="tooltipAttrs(dailyTooltip(dailySeries[index], dailySeries[index - 1]))"
                  @pointerenter="hoveredDailyIndex = index"
                  @pointerleave="hoveredDailyIndex = null"
                />
              </svg>
            </div>
            <div class="daily-card-grid">
              <div
                v-for="(point, index) in dailySeries"
                :key="point.date"
                class="daily-date-card"
                :data-linked="hoveredDailyIndex === index"
                v-bind="tooltipAttrs(dailyTooltip(point, dailySeries[index - 1]))"
                @pointerenter="hoveredDailyIndex = index"
                @pointerleave="hoveredDailyIndex = null"
              >
                <span>{{ point.date }}</span>
                <b>{{ point.electricity.toFixed(1) }} kWh</b>
              </div>
            </div>
          </article>

          <article class="hud-drawn-card data-analysis-card-frame panel-card data-analysis-heatmap-panel">
            <header class="hud-panel-titlebar">
              <div class="titlebar-inner">
                <span class="hud-title-mark" />
                <span class="hud-glyph">
                  <svg viewBox="0 0 40 40">
                    <rect v-for="row in 3" :key="`hm-${row}`" fill="currentColor" height="5.2" opacity="0.45" rx="0.6" width="5.2" :x="10 + row * 6.8" :y="12 + row * 5" />
                  </svg>
                </span>
                <h2>负荷热力分布</h2>
                <span class="titlebar-deco"><i /><i /><i /></span>
              </div>
            </header>
            <div class="heatmap-metrics">
              <div
                class="heatmap-metric-card"
                v-bind="
                  tooltipAttrs({
                    rows: [
                      { label: '计算口径', value: '近 7 天 × 4 时段均值' },
                      { label: '均值负荷', value: compactKwh(averageHeatmapElectricity) },
                    ],
                    title: '均值负荷',
                  })
                "
              >
                <span>均值负荷</span>
                <b>{{ averageHeatmapElectricity.toFixed(1) }}</b>
              </div>
              <div
                class="heatmap-metric-card"
                v-bind="tooltipAttrs(heatmapCellTooltip(peakHeatmapCell))"
              >
                <span>峰值时段</span>
                <b class="text-amber">{{ peakHeatmapCell.date }} {{ peakHeatmapCell.hour }}</b>
              </div>
              <div
                class="heatmap-metric-card"
                v-bind="tooltipAttrs(heatmapCellTooltip(peakHeatmapCell))"
              >
                <span>峰值电耗</span>
                <b>{{ peakHeatmapCell.electricity.toFixed(0) }} kWh</b>
              </div>
            </div>
            <div class="tech-chart-frame heatmap-frame">
              <div class="heatmap-grid">
                <div />
                <div class="heatmap-hour-row">
                  <span
                    v-for="hour in heatmapHours"
                    :key="hour"
                    class="heatmap-axis-label"
                    v-bind="
                      tooltipAttrs({
                        rows: [
                          { label: '7 天均值', value: compactKwh(heatmapColumnStats(hour).average) },
                          { label: '最高日', value: `${heatmapColumnStats(hour).maxCell?.date ?? '-'} ${compactKwh(heatmapColumnStats(hour).maxCell?.electricity ?? 0)}` },
                          { label: '最低日', value: `${heatmapColumnStats(hour).minCell?.date ?? '-'} ${compactKwh(heatmapColumnStats(hour).minCell?.electricity ?? 0)}` },
                        ],
                        title: `${hour} 列统计`,
                      })
                    "
                    @pointerenter="hoveredHeatmap = { hour, mode: 'column' }"
                    @pointerleave="hoveredHeatmap = null"
                  >
                    {{ hour }}
                  </span>
                </div>
                <div class="heatmap-date-col">
                  <span
                    v-for="date in heatmapDates"
                    :key="date"
                    class="heatmap-axis-label"
                    v-bind="
                      tooltipAttrs({
                        rows: [
                          { label: '当日总能耗', value: compactKwh(heatmapRowStats(date).total) },
                          { label: '峰值时段', value: `${heatmapRowStats(date).maxCell?.hour ?? '-'} ${compactKwh(heatmapRowStats(date).maxCell?.electricity ?? 0)}` },
                        ],
                        title: `${date} 行统计`,
                      })
                    "
                    @pointerenter="hoveredHeatmap = { date, mode: 'row' }"
                    @pointerleave="hoveredHeatmap = null"
                  >
                    {{ date }}
                  </span>
                </div>
                <div class="heatmap-grid-body">
                  <template v-for="date in heatmapDates" :key="date">
                    <button
                      v-for="hour in heatmapHours"
                      :key="`${date}-${hour}`"
                      class="heatmap-cell"
                      :data-dimmed="heatmapCellState(date, hour).dimmed"
                      :data-hovered="heatmapCellState(date, hour).hovered"
                      :data-peak="heatmapCell(date, hour) === peakHeatmapCell"
                      :data-related="heatmapCellState(date, hour).related"
                      :data-sampled="heatmapCellState(date, hour).sampled"
                      :style="heatmapStyle(heatmapCell(date, hour))"
                      type="button"
                      v-bind="tooltipAttrs(heatmapCellTooltip(heatmapCell(date, hour)))"
                      @pointerenter="hoveredHeatmap = { date, hour, mode: 'cell' }"
                      @pointerleave="hoveredHeatmap = null"
                    >
                      <span>{{ Math.round((heatmapCell(date, hour)?.intensity ?? 0) * 100) }}</span>
                      <b>{{ heatmapCell(date, hour)?.electricity.toFixed(0) }}</b>
                      <i aria-hidden="true" />
                    </button>
                  </template>
                </div>
              </div>
              <div class="heatmap-scale-row">
                <span>低负荷</span>
                <i class="heatmap-scale" />
                <span>高负荷</span>
              </div>
            </div>
          </article>
        </div>

        <div class="data-analysis-column">
          <article class="hud-drawn-card data-analysis-card-frame panel-card">
            <header class="hud-panel-titlebar">
              <div class="titlebar-inner">
                <span class="hud-title-mark" />
                <span class="hud-glyph">
                  <svg viewBox="0 0 40 40"><path d="M12 25h16M14 25V15M20 25V11M26 25v-7" fill="none" stroke="currentColor" stroke-linecap="round" stroke-width="3" /></svg>
                </span>
                <h2>时段负荷关系</h2>
                <span class="titlebar-deco"><i /><i /><i /></span>
              </div>
            </header>
            <div class="hourly-summary-grid">
              <div class="hourly-summary-card" v-bind="tooltipAttrs(hourlyTooltip(peakPoint, hourlySeries.findIndex((item) => item.hour === peakPoint.hour)))"><span>电耗高峰</span><b>{{ peakPoint.hour }}</b></div>
              <div class="hourly-summary-card" v-bind="tooltipAttrs(hourlyTooltip(quietPoint, hourlySeries.findIndex((item) => item.hour === quietPoint.hour)))"><span>低谷时段</span><b>{{ quietPoint.hour }}</b></div>
              <div
                class="hourly-summary-card"
                v-bind="
                  tooltipAttrs({
                    rows: [
                      { label: '高峰负荷', value: compactKwh(peakPoint.electricity) },
                      { label: '低谷负荷', value: compactKwh(quietPoint.electricity) },
                      { label: '峰谷差值', tone: 'amber', value: compactKwh(peakPoint.electricity - quietPoint.electricity) },
                    ],
                    title: '峰谷差值',
                  })
                "
              ><span>峰谷差值</span><b class="text-amber">{{ (peakPoint.electricity - quietPoint.electricity).toFixed(0) }}</b></div>
              <div class="hourly-summary-card" v-bind="tooltipAttrs(hourlyTooltip(busiestPoint, hourlySeries.findIndex((item) => item.hour === busiestPoint.hour)))"><span>人流峰值</span><b class="text-cyan">{{ busiestPoint.occupancy.toFixed(0) }}</b></div>
            </div>
            <div class="tech-chart-frame hourly-frame">
              <svg class="chart-svg" :viewBox="`0 0 ${hourlyChart.width} ${hourlyChart.height}`">
                <defs>
                  <linearGradient id="hourlyBarFillVue" x1="0" x2="0" y1="0" y2="1">
                    <stop offset="0%" stop-color="#7af7ff" />
                    <stop offset="100%" stop-color="#0070ae" />
                  </linearGradient>
                </defs>
                <g v-for="tick in hourlyChart.electricityTicks" :key="`hourly-y-${tick}`">
                  <line stroke="rgba(141,168,197,0.2)" stroke-dasharray="5 8" :x1="hourlyChart.padding" :x2="hourlyChart.width - hourlyChart.padding" :y1="axisY(tick, 0, hourlyChart.maxElectricity, hourlyChart.height, hourlyChart.padding)" :y2="axisY(tick, 0, hourlyChart.maxElectricity, hourlyChart.height, hourlyChart.padding)" />
                </g>
                <line stroke="rgba(122,247,255,0.38)" :x1="hourlyChart.padding" :x2="hourlyChart.padding" :y1="hourlyChart.padding" :y2="hourlyChart.height - hourlyChart.padding" />
                <line stroke="rgba(122,247,255,0.38)" :x1="hourlyChart.padding" :x2="hourlyChart.width - hourlyChart.padding" :y1="hourlyChart.height - hourlyChart.padding" :y2="hourlyChart.height - hourlyChart.padding" />
                <text v-for="(point, index) in hourlySeries" :key="point.hour" class="axis-text" text-anchor="middle" :x="axisX(index, hourlySeries.length, hourlyChart.width, hourlyChart.padding)" :y="hourlyChart.height - hourlyChart.padding + 21">{{ point.hour }}</text>
                <rect
                  v-for="(bar, index) in hourlyChart.bars"
                  :key="hourlySeries[index]?.hour"
                  class="hourly-chart-bar"
                  :data-current="index === hourlySeries.length - 1"
                  :data-hovered="hoveredHourlyIndex === index"
                  fill="url(#hourlyBarFillVue)"
                  :height="bar.height"
                  rx="10"
                  :width="bar.width"
                  :x="bar.x"
                  :y="bar.y"
                  v-bind="tooltipAttrs(hourlyTooltip(hourlySeries[index], index))"
                  @pointerenter="hoveredHourlyIndex = index"
                  @pointerleave="hoveredHourlyIndex = null"
                />
                <path :d="hourlyChart.linePath" fill="none" stroke="#ffb800" stroke-linecap="round" stroke-linejoin="round" stroke-width="3" />
                <circle
                  v-for="(point, index) in hourlyChart.occupancyPoints"
                  :key="`hourly-node-${index}`"
                  class="hourly-line-node"
                  :data-hovered="hoveredHourlyIndex === index"
                  fill="#061829"
                  :r="hoveredHourlyIndex === index ? 6.5 : 4.5"
                  stroke="#ffb800"
                  stroke-width="2"
                  :cx="point.x"
                  :cy="point.y"
                  v-bind="tooltipAttrs(hourlyTooltip(hourlySeries[index], index))"
                  @pointerenter="hoveredHourlyIndex = index"
                  @pointerleave="hoveredHourlyIndex = null"
                />
              </svg>
            </div>
            <div class="hourly-slot-grid">
              <div
                v-for="(point, index) in hourlySeries"
                :key="point.hour"
                class="hourly-slot-card"
                :data-current="index === hourlySeries.length - 1"
                :data-linked="hoveredHourlyIndex === index"
                v-bind="tooltipAttrs(hourlyTooltip(point, index))"
                @pointerenter="hoveredHourlyIndex = index"
                @pointerleave="hoveredHourlyIndex = null"
              >
                <span>{{ point.hour }}</span>
                <b>{{ point.electricity.toFixed(1) }}</b>
                <small>kWh</small>
              </div>
            </div>
          </article>

          <article class="hud-drawn-card data-analysis-card-frame panel-card data-analysis-composition-panel">
            <header class="hud-panel-titlebar">
              <div class="titlebar-inner">
                <span class="hud-title-mark" />
                <span class="hud-glyph">
                  <svg viewBox="0 0 40 40"><circle cx="20" cy="20" fill="none" r="8.4" stroke="currentColor" stroke-width="2.2" /><path d="M20 11.6v8.4l7.2 4.1" fill="none" stroke="currentColor" stroke-width="2.4" /></svg>
                </span>
                <h2>能耗构成占比</h2>
                <span class="titlebar-deco"><i /><i /><i /></span>
              </div>
            </header>
            <div class="composition-donut">
              <svg viewBox="0 0 220 220">
                <defs>
                  <filter id="compositionSegmentGlowVue" x="-35%" y="-35%" width="170%" height="170%">
                    <feGaussianBlur result="blur" stdDeviation="3" />
                    <feMerge>
                      <feMergeNode in="blur" />
                      <feMergeNode in="SourceGraphic" />
                    </feMerge>
                  </filter>
                  <radialGradient id="compositionCoreGlowVue" cx="50%" cy="45%" r="58%">
                    <stop offset="0%" stop-color="#7af7ff" stop-opacity="0.28" />
                    <stop offset="62%" stop-color="#00d4ff" stop-opacity="0.12" />
                    <stop offset="100%" stop-color="#061829" stop-opacity="0.95" />
                  </radialGradient>
                  <linearGradient
                    v-for="arc in compositionArcs"
                    :id="arc.gradientId"
                    :key="arc.gradientId"
                    x1="0"
                    x2="1"
                    y1="0"
                    y2="1"
                  >
                    <stop offset="0%" :stop-color="arc.color" stop-opacity="0.5" />
                    <stop offset="52%" :stop-color="arc.color" stop-opacity="0.9" />
                    <stop offset="100%" stop-color="#e8f4ff" stop-opacity="0.82" />
                  </linearGradient>
                  <linearGradient
                    v-for="arc in compositionArcs"
                    :id="arc.highlightId"
                    :key="arc.highlightId"
                    x1="0"
                    x2="1"
                    y1="0"
                    y2="0"
                  >
                    <stop offset="0%" stop-color="#fff" stop-opacity="0" />
                    <stop offset="50%" stop-color="#fff" stop-opacity="0.56" />
                    <stop offset="100%" stop-color="#fff" stop-opacity="0" />
                  </linearGradient>
                </defs>
                <circle cx="110" cy="110" fill="none" r="94" stroke="rgba(0,212,255,0.1)" stroke-width="16" />
                <circle class="composition-scan-arc" cx="110" cy="110" fill="none" r="103" stroke="#7af7ff" stroke-dasharray="42 606" stroke-linecap="round" stroke-width="3" />
                <circle cx="110" cy="110" fill="none" r="70" stroke="rgba(122,247,255,0.11)" stroke-dasharray="5 9" stroke-width="4" />
                <g
                  v-for="arc in compositionArcs"
                  :key="arc.label"
                  class="composition-segment"
                  :data-dimmed="hoveredComposition !== null && hoveredComposition !== arc.label"
                  :data-hovered="hoveredComposition === arc.label"
                  :style="{ '--lift-x': `${arc.liftX}px`, '--lift-y': `${arc.liftY}px` }"
                  v-bind="tooltipAttrs(compositionTooltip(arc, arc.ratio))"
                  @pointerenter="hoveredComposition = arc.label"
                  @pointerleave="hoveredComposition = null"
                >
                  <path
                    :d="describeDonutArc(110, 110, 92, 62, arc.startAngle, arc.endAngle)"
                    :fill="`url(#${arc.gradientId})`"
                    filter="url(#compositionSegmentGlowVue)"
                    stroke="rgba(221,251,255,0.42)"
                    stroke-width="1.2"
                  />
                  <path
                    class="composition-highlight"
                    :d="describeDonutArc(110, 110, 94, 88, arc.startAngle + 3, arc.endAngle - 3)"
                    :fill="`url(#${arc.highlightId})`"
                  />
                  <path
                    :d="describeDonutArc(110, 110, 56, 47, arc.startAngle, arc.endAngle)"
                    :fill="arc.color"
                    opacity="0.34"
                    :stroke="arc.color"
                    stroke-opacity="0.52"
                  />
                </g>
                <circle class="composition-core-pulse" cx="110" cy="110" fill="url(#compositionCoreGlowVue)" r="49" stroke="rgba(122,247,255,0.32)" stroke-width="1.4" />
                <circle cx="110" cy="110" fill="none" r="38" stroke="rgba(0,212,255,0.26)" stroke-dasharray="3 7" stroke-width="1" />
              </svg>
              <div
                class="composition-center"
                v-bind="
                  tooltipAttrs({
                    rows: [
                      { label: '统计周期', value: '本月累计' },
                      { label: '同比', tone: 'cyan', value: '+5.2%' },
                      { label: '环比', tone: 'amber', value: '+1.8%' },
                    ],
                    title: '本月总量基准',
                  })
                "
              >
                <b>{{ (hoveredComposition ? (composition.find((item) => item.label === hoveredComposition)?.value ?? compositionTotal) : compositionTotal).toFixed(0) }}</b>
                <span>{{ hoveredComposition ?? '本月累计' }}</span>
              </div>
            </div>
            <div class="composition-grid">
              <div
                v-for="item in composition"
                :key="item.label"
                class="composition-legend-item"
                :data-linked="hoveredComposition === item.label"
                v-bind="tooltipAttrs(compositionTooltip(item, ratio(item.value, compositionTotal)))"
                @pointerenter="hoveredComposition = item.label"
                @pointerleave="hoveredComposition = null"
              >
                <div class="composition-value-line">
                  <span><i :style="{ backgroundColor: item.color, color: item.color }" />{{ item.label }}</span>
                  <b>{{ item.value.toFixed(0) }}</b>
                </div>
                <div class="composition-bar-line">
                  <div><em :style="{ width: `${(item.value / compositionMaxValue) * 100}%`, backgroundColor: item.color, color: item.color }" /></div>
                  <strong>{{ ratio(item.value, compositionTotal).toFixed(1) }}%</strong>
                </div>
              </div>
            </div>
            <div class="composition-status-grid">
              <div
                v-for="bucket in statusDistribution"
                :key="bucket.label"
                class="composition-status-card"
                v-bind="tooltipAttrs(statusBucketTooltip(bucket, ratio(bucket.count, statusTotal), buildingSummaries))"
              >
                <span><i :style="{ backgroundColor: statusToneColor(bucket.tone), color: statusToneColor(bucket.tone) }" />{{ bucket.label }}</span>
                <b>{{ bucket.count }}</b>
              </div>
            </div>
          </article>
        </div>
      </section>

      <section class="scatter-grid">
        <article
          v-for="chart in scatterCharts"
          :key="chart.title"
          class="hud-drawn-card data-analysis-card-frame panel-card"
        >
          <header class="hud-panel-titlebar">
            <div class="titlebar-inner">
              <span class="hud-title-mark" />
              <span class="hud-glyph">
                <svg viewBox="0 0 40 40"><circle cx="14" cy="15" fill="currentColor" opacity="0.78" r="3" /><circle cx="26" cy="24" fill="currentColor" opacity="0.78" r="3" /><path d="M16.6 16.8 23.4 22.2" stroke="currentColor" stroke-width="2.2" /></svg>
              </span>
              <h2>{{ chart.title }}</h2>
              <span class="titlebar-deco"><i /><i /><i /></span>
            </div>
          </header>
          <div class="scatter-topline">
            <div class="pill-row">
              <span
                class="hud-pill"
                v-bind="
                  tooltipAttrs({
                    rows: [
                      { label: '> 0.7', value: '强正相关' },
                      { label: '0.4-0.7', value: '中等正相关' },
                      { label: '< 0.4', value: '弱相关' },
                    ],
                    title: '相关强度解释',
                  })
                "
              >
                {{ describeCorrelation(chart.correlation) }}
              </span>
              <span
                class="hud-pill hud-pill-neutral"
                v-bind="
                  tooltipAttrs({
                    rows: [
                      { label: '统计口径', value: '最近 48 条监测样本' },
                      { label: '当前显示', value: `${chart.points.length} 条` },
                    ],
                    title: '样本数',
                  })
                "
              >
                样本 {{ scatterPoints.length }}
              </span>
              <span
                class="hud-pill hud-pill-emerald"
                v-bind="
                  tooltipAttrs({
                    rows: [
                      { label: '统计口径', value: '预警/实验高负荷采样点' },
                      { label: '风险点数', tone: 'rose', value: `${chart.points.filter((item) => item.tone === 'amber' || item.tone === 'rose').length} 个` },
                    ],
                    title: '风险点统计',
                  })
                "
              >
                风险点 {{ chart.points.filter((item) => item.tone === 'amber' || item.tone === 'rose').length }}
              </span>
            </div>
            <div
              class="correlation-value"
              v-bind="
                tooltipAttrs({
                  rows: [
                    { label: '当前系数', value: chart.correlation.toFixed(2) },
                    { label: '解释', value: describeCorrelation(chart.correlation) },
                    { label: '样本数', value: `${chart.points.length}` },
                  ],
                  title: '相关系数说明',
                })
              "
            >
              <span>相关系数</span>
              <b>{{ chart.correlation.toFixed(2) }}</b>
            </div>
          </div>
          <div class="tech-chart-frame scatter-frame">
            <div class="scatter-floating-legend">
              <button
                v-for="legend in visibleScatterLegend"
                :key="legend.tone"
                class="scatter-legend"
                :data-hidden="hiddenTones.has(legend.tone)"
                type="button"
                v-bind="
                  tooltipAttrs({
                    rows: [
                      { label: '类型', value: legend.label },
                      { label: '可见性', value: hiddenTones.has(legend.tone) ? '隐藏' : '显示' },
                      { label: '交互', value: '点击切换该类型采样点' },
                    ],
                    title: '散点类型筛选',
                  })
                "
                @pointerenter="hoveredScatterTone = legend.tone"
                @pointerleave="hoveredScatterTone = null"
                @click="toggleTone(legend.tone)"
              >
                <i :style="{ backgroundColor: legend.color }" />{{ legend.label }}
              </button>
            </div>
            <svg class="chart-svg" :viewBox="`0 0 ${chart.width} ${chart.height}`">
              <defs>
                <pattern :id="`${chart.id}-microGridVue`" height="18" patternUnits="userSpaceOnUse" width="18">
                  <path d="M 18 0 L 0 0 0 18" fill="none" stroke="rgba(122,247,255,0.055)" stroke-width="1" />
                </pattern>
                <linearGradient :id="`${chart.id}-trendGradientVue`" x1="0" x2="1" y1="0" y2="0">
                  <stop offset="0%" stop-color="#00d4ff" stop-opacity="0.15" />
                  <stop offset="48%" stop-color="#7af7ff" />
                  <stop offset="100%" stop-color="#ffb800" />
                </linearGradient>
                <filter :id="`${chart.id}-pointGlowVue`" x="-80%" y="-80%" width="260%" height="260%">
                  <feGaussianBlur result="blur" stdDeviation="2.2" />
                  <feMerge>
                    <feMergeNode in="blur" />
                    <feMergeNode in="SourceGraphic" />
                  </feMerge>
                </filter>
              </defs>
              <rect fill="rgba(2, 8, 23, 0.32)" :height="chart.height - chart.padding * 2" rx="14" :width="chart.width - chart.padding * 2" :x="chart.padding" :y="chart.padding" />
              <rect :fill="`url(#${chart.id}-microGridVue)`" :height="chart.height - chart.padding * 2" rx="14" :width="chart.width - chart.padding * 2" :x="chart.padding" :y="chart.padding" />
              <rect fill="rgba(0,212,255,0.04)" :height="(chart.height - chart.padding * 2) / 2" :width="(chart.width - chart.padding * 2) / 2" :x="chart.padding" :y="chart.padding" />
              <rect
                class="scatter-risk-area"
                :data-hovered="hoveredScatterRiskChart === chart.title"
                :x="chart.padding + (chart.width - chart.padding * 2) / 2"
                :y="chart.padding"
                :width="(chart.width - chart.padding * 2) / 2"
                :height="(chart.height - chart.padding * 2) / 2"
                fill="rgba(255,184,0,0.055)"
                v-bind="
                  tooltipAttrs({
                    rows: [
                      { label: '定义', value: '高能耗且高人流/高温区' },
                      { label: '区内样本数', value: `${scatterRiskSamples(chart)}` },
                      { label: '风险点占比', tone: 'amber', value: `${((scatterRiskSamples(chart) / Math.max(chart.points.length, 1)) * 100).toFixed(1)}%` },
                    ],
                    title: '高风险区域',
                  })
                "
                @pointerenter="hoveredScatterRiskChart = chart.title"
                @pointerleave="hoveredScatterRiskChart = null"
              />
              <rect fill="rgba(34,211,160,0.045)" :height="(chart.height - chart.padding * 2) / 2" :width="(chart.width - chart.padding * 2) / 2" :x="chart.padding" :y="chart.padding + (chart.height - chart.padding * 2) / 2" />
              <g v-for="tick in chart.yTicks" :key="`${chart.title}-y-${tick}`">
                <line stroke="rgba(122,247,255,0.18)" stroke-dasharray="7 9" :x1="chart.padding" :x2="chart.width - chart.padding" :y1="axisY(tick, chart.yExtent.min, chart.yExtent.max, chart.height, chart.padding)" :y2="axisY(tick, chart.yExtent.min, chart.yExtent.max, chart.height, chart.padding)" />
                <text class="axis-text" dominant-baseline="middle" text-anchor="end" :x="chart.padding - 10" :y="axisY(tick, chart.yExtent.min, chart.yExtent.max, chart.height, chart.padding)">{{ axisTickLabel(tick, 1) }}</text>
              </g>
              <g v-for="tick in chart.xTicks" :key="`${chart.title}-x-${tick}`">
                <line
                  stroke="rgba(122,247,255,0.14)"
                  stroke-dasharray="7 9"
                  :x1="chart.padding + ((tick - chart.xExtent.min) / Math.max(chart.xExtent.max - chart.xExtent.min, 1)) * (chart.width - chart.padding * 2)"
                  :x2="chart.padding + ((tick - chart.xExtent.min) / Math.max(chart.xExtent.max - chart.xExtent.min, 1)) * (chart.width - chart.padding * 2)"
                  :y1="chart.padding"
                  :y2="chart.height - chart.padding"
                />
                <text class="axis-text" text-anchor="middle" :x="chart.padding + ((tick - chart.xExtent.min) / Math.max(chart.xExtent.max - chart.xExtent.min, 1)) * (chart.width - chart.padding * 2)" :y="chart.height - chart.padding + 23">{{ axisTickLabel(tick, 1) }}</text>
              </g>
              <line stroke="rgba(122,247,255,0.38)" :x1="chart.padding" :x2="chart.padding" :y1="chart.padding" :y2="chart.height - chart.padding" />
              <line stroke="rgba(122,247,255,0.38)" :x1="chart.padding" :x2="chart.width - chart.padding" :y1="chart.height - chart.padding" :y2="chart.height - chart.padding" />
              <line
                class="scatter-trend-hit"
                v-if="chart.trendLine"
                :x1="chart.trendLine.x1"
                :y1="chart.trendLine.y1"
                :x2="chart.trendLine.x2"
                :y2="chart.trendLine.y2"
                stroke="rgba(0,212,255,0.18)"
                stroke-linecap="round"
                stroke-width="12"
                v-bind="
                  tooltipAttrs({
                    rows: [
                      { label: '相关系数', value: chart.correlation.toFixed(2) },
                      { label: '拟合公式', value: 'y = ax + b' },
                      { label: '样本数', value: `${chart.points.length}` },
                    ],
                    title: '趋势线说明',
                  })
                "
                @pointerenter="hoveredScatterTrendChart = chart.title"
                @pointerleave="hoveredScatterTrendChart = null"
              />
              <line
                class="scatter-trend-line"
                :data-hovered="hoveredScatterTrendChart === chart.title"
                v-if="chart.trendLine"
                :x1="chart.trendLine.x1"
                :y1="chart.trendLine.y1"
                :x2="chart.trendLine.x2"
                :y2="chart.trendLine.y2"
                :stroke="`url(#${chart.id}-trendGradientVue)`"
                stroke-linecap="round"
                :stroke-width="hoveredScatterTrendChart === chart.title ? 6 : 4"
              />
              <circle v-if="chart.trendLine" class="scatter-trend-traveler" fill="#7af7ff" r="5">
                <animate
                  attributeName="cx"
                  dur="4s"
                  repeatCount="indefinite"
                  :values="`${chart.trendLine.x1};${chart.trendLine.x2}`"
                />
                <animate
                  attributeName="cy"
                  dur="4s"
                  repeatCount="indefinite"
                  :values="`${chart.trendLine.y1};${chart.trendLine.y2}`"
                />
              </circle>
              <g
                v-for="point in chart.points"
                :key="`${chart.title}-${point.id}`"
                class="scatter-point-group"
                :data-dimmed="scatterPointDimmed(point, chart.title)"
                :data-highlighted="scatterPointHighlighted(point, chart.title)"
                :data-sampled="sampledPointId === point.id"
                :data-tone="point.tone"
                v-bind="tooltipAttrs(scatterTooltip(point, chart.xLabel))"
                @pointerenter="hoveredScatterTone = point.tone"
                @pointerleave="hoveredScatterTone = null"
              >
                <circle :cx="point.cx" :cy="point.cy" :fill="scatterPointColor(point.tone)" :opacity="point.tone === 'rose' ? 0.18 : 0.1" :r="point.tone === 'rose' ? 15 : 11" />
                <circle
                  class="scatter-point-core"
                  :cx="point.cx"
                  :cy="point.cy"
                  :fill="scatterPointColor(point.tone)"
                  :filter="`url(#${chart.id}-pointGlowVue)`"
                  opacity="0.9"
                  :r="scatterPointHighlighted(point, chart.title) ? 9.4 : point.tone === 'rose' ? 6.8 : point.tone === 'amber' ? 5.9 : 5.2"
                  stroke="rgba(221,251,255,0.9)"
                  stroke-width="1.35"
                />
              </g>
              <text class="axis-title" :x="chart.padding" :y="chart.padding - 14">{{ chart.yLabel }}</text>
              <text class="axis-title" text-anchor="middle" :x="chart.width / 2" :y="chart.height - 10">{{ chart.xLabel }}</text>
            </svg>
            <div class="scatter-stat-cards">
              <div
                class="scatter-stat-card"
                v-bind="
                  tooltipAttrs({
                    rows: [
                      { label: '样本数', value: `${chart.points.length}` },
                      { label: '筛选状态', value: hiddenTones.size > 0 ? '已隐藏部分类型' : '全部显示' },
                    ],
                    title: '样本统计',
                  })
                "
              ><span>样本数</span><b>{{ chart.points.length }}</b></div>
              <div
                class="scatter-stat-card"
                v-bind="
                  tooltipAttrs({
                    rows: [
                      { label: '风险点', tone: 'rose', value: `${chart.points.filter((item) => item.tone === 'rose').length}` },
                      { label: '判断规则', value: '异常楼栋 / 高负荷采样点' },
                    ],
                    title: '风险点统计',
                  })
                "
              ><span>风险点</span><b class="text-rose">{{ chart.points.filter((item) => item.tone === 'rose').length }}</b></div>
            </div>
          </div>
        </article>
      </section>

      <section class="hud-drawn-card data-analysis-card-frame panel-card detail-panel">
        <header class="hud-panel-titlebar">
          <div class="titlebar-inner">
            <span class="hud-title-mark" />
            <span class="hud-glyph">
              <svg viewBox="0 0 40 40"><path d="M13 11.5h14v17H13zM16 16h8M16 20h8M16 24h5" fill="none" stroke="currentColor" stroke-width="2" /></svg>
            </span>
            <h2>近期监测明细</h2>
            <span class="titlebar-deco"><i /><i /><i /></span>
          </div>
        </header>
        <div class="detail-toolbar">
          <div><span class="table-live-dot" />实时采样</div>
          <div class="detail-live-counter">
            今日已采集 <b>{{ sampleCount }}</b> 条 · 当前速率 <b>{{ sampleRate }}</b> 条/分钟
          </div>
        </div>
        <div class="detail-table-wrap">
          <table>
            <colgroup>
              <col v-for="header in detailHeaders" :key="header.label" :class="header.colClass" />
            </colgroup>
            <thead>
              <tr>
                <th
                  v-for="header in detailHeaders"
                  :key="header.label"
                  class="detail-table-head"
                  v-bind="tooltipAttrs(fieldHeaderTooltip(header))"
                >
                  <span>{{ header.label }}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(record, index) in records"
                :key="record.id"
                class="detail-table-row"
                :data-new="index === 0"
                :data-warning="record.device_status === 'warning'"
                v-bind="tooltipAttrs(tableRowTooltip(record))"
              >
                <td
                  v-bind="
                    tooltipAttrs({
                      rows: [
                        { label: '楼栋编号', value: record.building_id },
                        { label: '楼栋类型', value: buildingTypeReadable(record.building_type) },
                      ],
                      title: '楼栋信息',
                    })
                  "
                >
                  {{ record.building_id }}
                </td>
                <td
                  v-bind="
                    tooltipAttrs({
                      rows: [
                        { label: '采样时间', value: record.monitor_time },
                        { label: '新数据', value: index === 0 ? '是' : '否' },
                      ],
                      title: '采样时间',
                    })
                  "
                >
                  <span class="detail-time-cell">
                    {{ record.monitor_time }}
                    <span v-if="index === 0" class="new-data-badge" />
                  </span>
                </td>
                <td
                  v-for="cell in detailMetricCells(record)"
                  :key="cell.label"
                  class="detail-table-value"
                  v-bind="
                    tooltipAttrs({
                      rows: cell.rows,
                      title: `${cell.label}指标`,
                    })
                  "
                >
                  {{ formatNumber(cell.value, cell.decimals) }}{{ cell.suffix }}
                </td>
                <td
                  v-bind="
                    tooltipAttrs({
                      rows: [
                        { label: '设备 ID', value: record.device_id },
                        { label: '楼栋前缀', value: record.building_id },
                        { label: '状态', value: statusText(record.device_status) },
                      ],
                      title: '关联设备',
                    })
                  "
                >
                  {{ record.device_id }}
                </td>
                <td>
                  <span
                    class="detail-status-badge"
                    :data-status="record.device_status"
                    v-bind="
                      tooltipAttrs({
                        rows: [
                          { label: '状态详情', value: statusText(record.device_status) },
                          { label: '持续时长', value: record.device_status === 'normal' ? '18 分钟' : '42 分钟' },
                          { label: '责任人', value: record.device_status === 'warning' ? '运维一组' : '值班人员' },
                          { label: '处理建议', value: record.device_status === 'warning' ? '创建工单并复核阈值' : '持续监测' },
                        ],
                        title: '状态说明',
                      })
                    "
                  >
                    {{ statusText(record.device_status) }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>

    <button
      class="issue-pill"
      type="button"
      v-bind="
        tooltipAttrs({
          actions: ['立即处理', '忽略'],
          rows: [
            { label: '问题类型', value: '暖通能耗异常' },
            { label: '涉及楼栋', value: 'BLDG-C-07' },
            { label: '触发时间', value: '最近 24 小时' },
            { label: '严重等级', tone: 'rose', value: '高' },
            { label: '建议动作', value: '检查冷站策略与阀门开度' },
          ],
          title: '未处理问题详情',
        })
      "
    >
      <span class="issue-pill-badge">1</span>
      <span>Issue</span>
    </button>

    <div
      v-if="tooltipContent"
      class="dashboard-tooltip"
      :data-visible="tooltipVisible"
      :style="{ left: `${tooltipPosition.x}px`, top: `${tooltipPosition.y}px` }"
    >
      <div class="dashboard-tooltip-title">{{ tooltipContent.title }}</div>
      <div v-if="tooltipContent.body" class="dashboard-tooltip-body">{{ tooltipContent.body }}</div>
      <div v-if="tooltipContent.rows?.length" class="dashboard-tooltip-rows">
        <div
          v-for="row in tooltipContent.rows"
          :key="`${row.label}-${row.value}`"
          class="dashboard-tooltip-row"
        >
          <span class="dashboard-tooltip-label">{{ row.label }}</span>
          <span class="dashboard-tooltip-value" :data-tone="row.tone ?? 'cyan'">{{ row.value }}</span>
        </div>
      </div>
      <div v-if="tooltipContent.actions?.length" class="dashboard-tooltip-actions">
        <span v-for="action in tooltipContent.actions" :key="action" class="dashboard-tooltip-action">
          {{ action }}
        </span>
      </div>
    </div>
  </main>
</template>

<style scoped lang="scss">
.data-page {
  --color-cyan: #00d4ff;
  --color-cyan-bright: #7af7ff;
  --color-amber: #ffb800;
  --color-emerald: #22d3a0;
  --color-rose: #ff4d6d;
  --color-text: #e8f4ff;
  --color-muted: #8da8c5;
  --font-cn: 'AlimamaShuHeiTi', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  --font-title: 'DOUYUFont', 'AlimamaShuHeiTi', 'PingFang SC', sans-serif;
  --font-num: 'Rajdhani', 'DINPro', 'AlimamaShuHeiTi', sans-serif;

  position: relative;
  height: 100vh;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  color: var(--color-text);
  background: #020817;
  font-family: var(--font-cn);
}

.data-video-bg {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
  background: #020817;

  video {
    width: 100%;
    height: 100%;
    object-fit: cover;
    opacity: 0.8;
  }
}

.data-video-overlay {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(120% 80% at 50% 0%, rgba(0, 212, 255, 0.18) 0%, transparent 55%),
    radial-gradient(100% 70% at 50% 100%, rgba(10, 37, 64, 0.48) 0%, transparent 62%),
    linear-gradient(180deg, rgba(0, 8, 18, 0.34) 0%, rgba(0, 8, 18, 0.48) 100%);
}

.cockpit-atmosphere {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background:
    radial-gradient(ellipse at center, rgba(0, 245, 255, 0.05) 0%, transparent 62%),
    radial-gradient(ellipse at 20% 20%, rgba(0, 245, 255, 0.06), transparent 46%),
    radial-gradient(ellipse at 80% 80%, rgba(255, 179, 0, 0.03), transparent 46%),
    radial-gradient(ellipse at 120% 0%, rgba(3, 7, 18, 0.54), transparent 50%),
    radial-gradient(ellipse at -20% 100%, rgba(3, 7, 18, 0.54), transparent 50%);
  mix-blend-mode: screen;

  &::before {
    content: '';
    position: absolute;
    inset: 0;
    background:
      linear-gradient(90deg, transparent, rgba(0, 212, 255, 0.09), transparent),
      radial-gradient(circle, rgba(122, 247, 255, 0.22) 0 1px, transparent 1.3px);
    background-size: 58% 100%, 34px 34px;
    opacity: 0.62;
    animation: cockpit-flow-band 5s linear infinite, cockpit-particle-rise 7s linear infinite;
  }

  &::after {
    content: '0101 1100 0011 1010 0110 1001 0101 0010 1110 0101 1001 0011';
    position: absolute;
    inset: 0 0 0 auto;
    width: 180px;
    color: rgba(122, 247, 255, 0.18);
    font-family: var(--font-num), monospace;
    font-size: 12px;
    line-height: 1.9;
    letter-spacing: 0.18em;
    writing-mode: vertical-rl;
    opacity: 0.18;
    animation: data-rain-fall 8s linear infinite;
  }
}

.data-header {
  position: sticky;
  top: 0;
  z-index: 35;
  display: flex;
  min-height: 73px;
  align-items: center;
  gap: 16px;
  padding: 8px 16px;
  border-bottom: 1px solid rgba(0, 212, 255, 0.1);
  background: linear-gradient(180deg, rgba(2, 8, 23, 0.18), rgba(2, 8, 23, 0));
  box-shadow: 0 8px 34px rgba(0, 212, 255, 0.08);
  backdrop-filter: blur(12px);
}

.cyber-nav-shell {
  position: relative;
  display: flex;
  flex-wrap: nowrap;
  gap: 8px;
  padding: 5px;
  border: 1px solid rgba(0, 212, 255, 0.26);
  background:
    linear-gradient(180deg, rgba(7, 30, 54, 0.86), rgba(2, 8, 23, 0.44)),
    linear-gradient(90deg, rgba(0, 212, 255, 0.14), transparent 55%);
  box-shadow: inset 0 0 18px rgba(0, 212, 255, 0.08), 0 0 20px rgba(0, 212, 255, 0.08);
  clip-path: polygon(12px 0, 100% 0, calc(100% - 12px) 100%, 0 100%);
  backdrop-filter: blur(14px);
}

.cyber-tab {
  display: inline-flex;
  min-width: 118px;
  height: 46px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid transparent;
  color: rgba(255, 255, 255, 0.58);
  background: transparent;
  font-size: 16px;
  font-weight: 800;
  text-decoration: none;
  transition: all 180ms ease;
  clip-path: polygon(10px 0, 100% 0, calc(100% - 10px) 100%, 0 100%);

  &:hover {
    color: #dff9ff;
    border-color: rgba(0, 212, 255, 0.24);
    background: rgba(0, 212, 255, 0.08);
  }
}

.cyber-tab-active {
  color: var(--color-cyan-bright);
  border-color: rgba(122, 247, 255, 0.72);
  background:
    linear-gradient(180deg, rgba(0, 212, 255, 0.22), rgba(0, 212, 255, 0.08)),
    rgba(0, 212, 255, 0.08);
  box-shadow: 0 0 18px rgba(0, 212, 255, 0.22), inset 0 0 12px rgba(122, 247, 255, 0.1);
}

.title-plate {
  pointer-events: none;
  position: absolute;
  left: 50%;
  top: 50%;
  width: min(42vw, 620px);
  height: 58px;
  transform: translate(-50%, -50%);

  svg {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
  }
}

.title-plate-text {
  position: relative;
  display: flex;
  height: 100%;
  align-items: center;
  justify-content: center;
  color: #f0fbff;
  font-family: var(--font-title);
  font-size: clamp(20px, 1.8vw, 30px);
  letter-spacing: 0.08em;
  text-shadow: 0 0 8px rgba(122, 247, 255, 0.68), 0 0 22px rgba(0, 212, 255, 0.34);
}

.data-header-status {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;

  &::before {
    content: '';
    position: absolute;
    left: 12px;
    right: 12px;
    top: -7px;
    height: 1px;
    background: linear-gradient(90deg, transparent, rgba(122, 247, 255, 0.78), transparent);
  }
}

.data-status-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.data-status-kicker {
  color: rgba(184, 230, 255, 0.58);
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
}

.data-status-value,
.status-time {
  margin-top: 4px;
  color: #e8f4ff;
  font-weight: 800;
  line-height: 1;
}

.status-time {
  color: var(--color-cyan-bright);
  font-family: var(--font-num);
  font-size: 20px;
}

.data-status-divider {
  width: 1px;
  height: 28px;
  background: linear-gradient(180deg, transparent, rgba(0, 212, 255, 0.68), transparent);
}

.data-status-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  background: var(--color-emerald);
  box-shadow: 0 0 0 4px rgba(34, 211, 160, 0.1), 0 0 14px rgba(34, 211, 160, 0.85);
  animation: status-dot-breathe 1.8s ease-in-out infinite;
}

.weather-glyph {
  position: relative;
  width: 22px;
  height: 14px;
  border-radius: 999px;
  background: rgba(122, 247, 255, 0.28);
  box-shadow: inset 0 0 8px rgba(122, 247, 255, 0.22), 0 0 12px rgba(0, 212, 255, 0.26);

  &::before {
    content: '';
    position: absolute;
    left: 3px;
    top: -7px;
    width: 13px;
    height: 13px;
    border-radius: 999px;
    background: var(--color-amber);
    box-shadow: 0 0 14px rgba(255, 184, 0, 0.62);
  }

  &::after {
    content: '';
    position: absolute;
    right: -3px;
    bottom: 0;
    width: 14px;
    height: 11px;
    border-radius: 999px;
    background: var(--color-cyan-bright);
    box-shadow: 0 0 12px rgba(122, 247, 255, 0.52);
  }
}

.data-status-icon-btn {
  position: relative;
  display: flex;
  width: 30px;
  height: 30px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(122, 247, 255, 0.36);
  color: rgba(232, 244, 255, 0.86);
  background: rgba(0, 212, 255, 0.08);
  clip-path: polygon(7px 0, 100% 0, 100% calc(100% - 7px), calc(100% - 7px) 100%, 0 100%, 0 7px);
  transition: color 180ms ease, background 180ms ease, box-shadow 180ms ease;

  svg {
    width: 16px;
    height: 16px;
    transform-origin: center;
  }

  &:hover {
    color: var(--color-cyan-bright);
    background: rgba(0, 212, 255, 0.16);
    box-shadow: 0 0 14px rgba(0, 212, 255, 0.22);
  }
}

.data-status-online:hover .data-status-dot {
  filter: brightness(1.25);
  box-shadow: 0 0 0 5px rgba(34, 211, 160, 0.16), 0 0 20px rgba(34, 211, 160, 0.95);
}

.data-status-bell:hover svg {
  animation: status-bell-wiggle 520ms ease;
}

.data-status-gear:hover svg {
  animation: status-gear-spin 1.4s linear infinite;
}

.data-content {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: calc(100vh - 73px);
  overflow: visible;
  padding: 16px 20px 24px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 16px;
}

.hud-drawn-card {
  --hud-cut: 12px;
  --hud-border: rgba(0, 212, 255, 0.58);
  --hud-border-soft: rgba(122, 247, 255, 0.2);

  position: relative;
  isolation: isolate;
  overflow: hidden;
  clip-path: polygon(
    var(--hud-cut) 0,
    calc(100% - var(--hud-cut)) 0,
    100% var(--hud-cut),
    100% calc(100% - var(--hud-cut)),
    calc(100% - var(--hud-cut)) 100%,
    var(--hud-cut) 100%,
    0 calc(100% - var(--hud-cut)),
    0 var(--hud-cut)
  );
  background:
    linear-gradient(135deg, rgba(122, 247, 255, 0.1) 0 1px, transparent 1px 42%),
    radial-gradient(circle at 18% 0%, rgba(0, 212, 255, 0.16), transparent 34%),
    radial-gradient(circle at 100% 100%, rgba(58, 143, 255, 0.12), transparent 38%),
    linear-gradient(180deg, rgba(10, 37, 64, 0.54), rgba(2, 8, 23, 0.7));
  box-shadow: inset 0 0 22px rgba(0, 212, 255, 0.08), inset 0 1px 0 rgba(221, 251, 255, 0.1), 0 0 22px rgba(0, 212, 255, 0.16);

  &::before,
  &::after {
    content: '';
    position: absolute;
    pointer-events: none;
    clip-path: inherit;
  }

  &::before {
    inset: 0;
    z-index: -2;
    background:
      linear-gradient(90deg, transparent 0 10px, var(--hud-border) 10px calc(100% - 10px), transparent calc(100% - 10px)),
      linear-gradient(90deg, transparent 0 10px, var(--hud-border) 10px calc(100% - 10px), transparent calc(100% - 10px)),
      linear-gradient(180deg, transparent 0 10px, var(--hud-border) 10px calc(100% - 10px), transparent calc(100% - 10px)),
      linear-gradient(180deg, transparent 0 10px, var(--hud-border) 10px calc(100% - 10px), transparent calc(100% - 10px));
    background-position: 0 0, 0 100%, 0 0, 100% 0;
    background-repeat: no-repeat;
    background-size: 100% 1.8px, 100% 1.8px, 1.8px 100%, 1.8px 100%;
    filter: drop-shadow(0 0 8px rgba(0, 212, 255, 0.5));
  }

  &::after {
    inset: 3px;
    z-index: -1;
    border: 1px solid var(--hud-border-soft);
    background:
      linear-gradient(90deg, rgba(0, 212, 255, 0.16), transparent 22%, transparent 78%, rgba(0, 212, 255, 0.13)),
      repeating-linear-gradient(135deg, rgba(122, 247, 255, 0.045) 0 1px, transparent 1px 14px);
    opacity: 0.86;
  }
}

.hud-drawn-card-kpi {
  --hud-cut: 10px;
  min-height: 112px;
  padding: 12px 16px;
  background:
    radial-gradient(circle at 16% 50%, rgba(0, 212, 255, 0.18), transparent 26%),
    linear-gradient(90deg, rgba(4, 21, 39, 0.82), rgba(7, 32, 57, 0.54) 52%, rgba(3, 14, 26, 0.82));
}

.data-analysis-card-frame {
  contain: layout paint style;
  filter: drop-shadow(0 0 12px rgba(0, 212, 255, 0.2));
}

.metric-card-interactive,
.health-bar-item,
.health-ratio-card,
.daily-date-card,
.hourly-slot-card,
.hourly-summary-card,
.heatmap-metric-card,
.heatmap-axis-label,
.risk-stat-card,
.risk-row,
.peak-device-metric,
.peak-device-related,
.composition-legend-item,
.scatter-legend,
.scatter-stat-card,
.detail-table-value,
.detail-status-badge,
.detail-table-head,
.issue-pill {
  transition: transform 200ms ease, border-color 200ms ease, background-color 200ms ease, box-shadow 200ms ease, filter 200ms ease, opacity 200ms ease;
}

.metric-card-interactive:hover {
  --hud-border: rgba(0, 212, 255, 0.92);
  transform: translateY(-2px);
  filter: drop-shadow(0 0 18px rgba(0, 212, 255, 0.38));
}

.metric-card-content {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 12px;
}

.metric-card-icon {
  display: flex;
  width: 48px;
  height: 48px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  transition: transform 200ms ease;
}

.metric-card-interactive:hover .metric-card-icon {
  transform: scale(1.1);
}

.metric-copy {
  min-width: 0;
}

.metric-value {
  color: var(--color-cyan);
  font-family: var(--font-num);
  font-size: 34px;
  font-weight: 800;
  line-height: 1;
  filter: drop-shadow(0 0 12px rgba(0, 212, 255, 0.35));
}

.metric-label {
  margin-top: 8px;
  overflow: hidden;
  color: rgba(240, 251, 255, 0.88);
  font-size: 17px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-mini-trend {
  width: 128px;
  height: 24px;
  min-width: 100px;
  margin-top: 8px;
  overflow: visible;
  filter: drop-shadow(0 0 7px rgba(0, 212, 255, 0.35));
  animation: mini-trend-slide 2s ease-in-out infinite;
}

.live-number {
  position: relative;
  display: inline-flex;
  align-items: baseline;
  gap: 0.08em;
  white-space: nowrap;
  transition: color 200ms ease, filter 200ms ease, text-shadow 200ms ease;

  &[data-flash='up'] {
    animation: number-flash-cyan 560ms ease;
  }

  &[data-flash='down'] {
    animation: number-flash-amber 560ms ease;
  }
}

.live-number-unit {
  margin-left: 0.12em;
  font-size: 0.44em;
}

.live-number-delta {
  position: absolute;
  left: calc(100% + 0.4em);
  top: -0.45em;
  z-index: 2;
  color: var(--color-cyan-bright);
  font-size: 0.42em;
  font-weight: 800;
  white-space: nowrap;
  text-shadow: 0 0 10px rgba(0, 212, 255, 0.82);
  animation: live-delta-pop 1s ease-out forwards;

  &[data-tone='down'] {
    color: var(--color-amber);
    text-shadow: 0 0 10px rgba(255, 184, 0, 0.78);
  }
}

.data-analysis-main-grid {
  display: grid;
  grid-template-columns: minmax(0, 24fr) minmax(0, 48fr) minmax(0, 28fr);
  gap: 16px;
  align-items: stretch;
}

.data-analysis-column {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 16px;
}

.panel-card {
  padding: 16px;
}

.panel-card-large {
  min-height: 600px;
}

.hud-panel-titlebar {
  position: relative;
  min-height: 56px;
  margin-bottom: 18px;
  overflow: hidden;

  &::before {
    content: '';
    position: absolute;
    left: 0;
    right: 0;
    top: 6px;
    height: 38px;
    background:
      linear-gradient(90deg, rgba(0, 212, 255, 0.28), rgba(58, 143, 255, 0.1) 48%, rgba(0, 212, 255, 0.06) 78%, transparent),
      linear-gradient(180deg, rgba(8, 45, 78, 0.7), rgba(4, 20, 37, 0.12));
    clip-path: polygon(10px 0, 100% 0, calc(100% - 16px) 100%, 0 100%);
    box-shadow: inset 0 1px 0 rgba(221, 251, 255, 0.16), inset 0 -1px 0 rgba(0, 212, 255, 0.48), 0 0 16px rgba(0, 212, 255, 0.14);
  }

  &::after {
    content: '';
    position: absolute;
    left: 14px;
    right: 12px;
    top: 43px;
    height: 1px;
    background: linear-gradient(90deg, transparent, rgba(122, 247, 255, 0.7), rgba(0, 212, 255, 0.08), transparent);
  }
}

.titlebar-inner {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px 0;

  h2 {
    color: #f0fbff;
    font-size: clamp(22px, 1.45vw, 27px);
    font-weight: 900;
    letter-spacing: 0.03em;
    line-height: 1;
    text-shadow: 0 0 14px rgba(0, 212, 255, 0.52);
    white-space: nowrap;
  }
}

.hud-title-mark {
  display: inline-block;
  width: 8px;
  height: 34px;
  flex: 0 0 auto;
  transform: skewX(-18deg);
  background: linear-gradient(180deg, #7af7ff 0%, #00d4ff 48%, #034d7a 100%);
  box-shadow: 0 0 16px rgba(0, 212, 255, 0.9);
}

.hud-glyph {
  display: inline-flex;
  width: 40px;
  height: 40px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  color: var(--color-cyan);
  filter: drop-shadow(0 0 10px rgba(0, 212, 255, 0.55));

  svg {
    width: 100%;
    height: 100%;
  }
}

.titlebar-deco {
  display: flex;
  gap: 4px;
  margin-left: auto;

  i {
    display: block;
    width: 6px;
    height: 12px;
    transform: skewX(-22deg);
    background: rgba(122, 247, 255, 0.7);

    &:nth-child(2) {
      background: rgba(0, 212, 255, 0.45);
    }

    &:nth-child(3) {
      background: rgba(0, 136, 179, 0.25);
    }
  }
}

.health-layout {
  display: grid;
  grid-template-columns: 150px 1fr;
  gap: 16px;
}

.health-gauge-interactive {
  position: relative;
  display: flex;
  width: 144px;
  height: 144px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(0, 212, 255, 0.2);
  border-radius: 50%;
  box-shadow: 0 0 32px rgba(0, 212, 255, 0.2);
  transition: filter 200ms ease, transform 200ms ease, box-shadow 200ms ease;

  &:hover {
    filter: brightness(1.18);
    transform: translateY(-1px);
    box-shadow: 0 0 36px rgba(0, 212, 255, 0.36), inset 0 0 18px rgba(0, 212, 255, 0.12);
  }
}

.health-gauge-orbit {
  position: absolute;
  inset: -3px;
  border-radius: 999px;
  background: conic-gradient(from 0deg, transparent 0 82%, rgba(122, 247, 255, 0.95) 88%, transparent 94%);
  filter: drop-shadow(0 0 9px rgba(0, 212, 255, 0.8));
  mask: radial-gradient(circle, transparent 0 67%, #000 69% 72%, transparent 74%);
  animation: health-orbit-spin 3s linear infinite;
}

.gauge-inner {
  position: absolute;
  inset: 12px;
  border: 1px solid rgba(0, 212, 255, 0.1);
  border-radius: inherit;
  background: rgba(6, 24, 41, 0.85);
}

.gauge-value {
  z-index: 1;
  color: #f0fbff;
  font-family: var(--font-num);
  font-size: 48px;
  font-weight: 800;
}

.health-bars {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.health-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 5px;
  color: rgba(224, 240, 255, 0.82);
  font-size: 15px;

  span {
    display: inline-flex;
    align-items: center;
    gap: 8px;
  }

  i {
    width: 8px;
    height: 8px;
    border-radius: 999px;
    box-shadow: 0 0 10px currentColor;
  }

  b {
    font-family: var(--font-num);
  }
}

.health-bar-track,
.risk-progress {
  height: 8px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(8, 47, 73, 0.7);
}

.health-bar-fill,
.risk-row-progress {
  position: relative;
  height: 100%;
  border-radius: inherit;
  overflow: hidden;

  &::after {
    content: '';
    position: absolute;
    inset: 0;
    background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.42), transparent);
    transform: translateX(-110%);
    animation: bar-flow-light 2s linear infinite;
  }
}

.health-summary-grid,
.health-ratio-grid,
.risk-stat-grid,
.peak-grid,
.hourly-summary-grid,
.heatmap-metrics {
  display: grid;
  gap: 8px;
}

.health-summary-grid,
.health-ratio-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin-top: 12px;
}

.health-summary-grid > div,
.health-ratio-card,
.risk-stat-card,
.peak-device-metric,
.hourly-summary-card,
.heatmap-metric-card,
.scatter-stat-card {
  border: 1px solid rgba(0, 212, 255, 0.24);
  background: rgba(8, 47, 73, 0.22);
  padding: 10px 12px;

  span {
    display: block;
    color: rgba(207, 250, 254, 0.72);
    font-size: 14px;
    font-weight: 700;
  }

  b {
    display: block;
    margin-top: 5px;
    color: #f0fbff;
    font-family: var(--font-num);
    font-size: 22px;
    line-height: 1;
  }

  &:hover {
    border-color: rgba(0, 212, 255, 0.72);
    background-color: rgba(0, 212, 255, 0.09);
    box-shadow: 0 0 16px rgba(0, 212, 255, 0.18);
  }
}

.peak-card {
  border: 1px solid rgba(0, 212, 255, 0.28);
  background: rgba(8, 47, 73, 0.24);
  padding: 12px 14px;
  box-shadow: 0 0 12px rgba(0, 212, 255, 0.08);

  > span {
    color: rgba(207, 250, 254, 0.72);
    font-size: 15px;
    font-weight: 700;
  }
}

.peak-device-id {
  display: block;
  margin-top: 6px;
  color: var(--color-amber);
  font-family: var(--font-num);
  font-size: 25px;
  line-height: 1.1;
}

.peak-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-top: 10px;
}

.related-list,
.risk-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}

.peak-device-related {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border: 1px solid rgba(0, 212, 255, 0.24);
  background: rgba(8, 47, 73, 0.2);
  padding: 10px 12px;

  b,
  span {
    display: block;
  }

  b {
    color: #f0fbff;
    font-size: 14px;
  }

  span {
    margin-top: 3px;
    color: rgba(207, 250, 254, 0.7);
    font-size: 13px;
  }

  strong {
    color: var(--color-rose);
    font-family: var(--font-num);
    font-size: 20px;
  }
}

.risk-stat-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.risk-row {
  border: 1px solid rgba(0, 212, 255, 0.24);
  background: rgba(8, 47, 73, 0.2);
  padding: 10px 12px;

  &[data-rank='1'] {
    animation: top-risk-gold-pulse 2s ease-in-out infinite;
  }

  &:hover {
    transform: translateX(2px);
    border-color: rgba(0, 212, 255, 0.7);
    background-color: rgba(0, 212, 255, 0.1);
    box-shadow: 0 0 16px rgba(0, 212, 255, 0.16);
  }
}

.risk-row-head,
.risk-row-head > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.risk-rank {
  display: flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(0, 212, 255, 0.32);
  background: rgba(34, 211, 238, 0.1);
  color: #f0fbff;
  font-family: var(--font-num);
  font-weight: 800;
}

.risk-row b {
  color: #f0fbff;
  font-size: 15px;
}

.risk-row small {
  display: block;
  margin-top: 2px;
  color: rgba(207, 250, 254, 0.7);
  font-size: 13px;
}

.risk-row strong {
  color: var(--color-rose);
  font-family: var(--font-num);
  font-size: 22px;
}

.risk-progress {
  margin-top: 9px;
}

.risk-row-progress {
  background: linear-gradient(90deg, #ff4d6d 0%, #ffb800 100%);
  box-shadow: 0 0 14px rgba(255, 77, 109, 0.35);
}

.pill-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.hud-pill {
  display: inline-flex;
  align-items: center;
  padding: 6px 12px;
  border: 1px solid rgba(0, 212, 255, 0.35);
  color: var(--color-cyan);
  background: rgba(10, 37, 64, 0.5);
  font-size: 12px;
  font-weight: 800;
  clip-path: polygon(8px 0, 100% 0, 100% calc(100% - 8px), calc(100% - 8px) 100%, 0 100%, 0 8px);
}

.hud-pill-emerald {
  border-color: rgba(34, 211, 160, 0.35);
  color: var(--color-emerald);
}

.hud-pill-neutral {
  border-color: rgba(141, 168, 197, 0.35);
  color: var(--color-muted);
}

.tech-chart-frame {
  position: relative;
  overflow: hidden;
  padding: 12px;
  border: 1.5px solid rgba(0, 212, 255, 0.42);
  background: rgba(6, 15, 38, 0.2);
  box-shadow: inset 0 0 0 1px rgba(122, 247, 255, 0.12), 0 0 18px rgba(0, 212, 255, 0.12);
  clip-path: polygon(5px 0, calc(100% - 5px) 0, 100% 5px, 100% calc(100% - 5px), calc(100% - 5px) 100%, 5px 100%, 0 calc(100% - 5px), 0 5px);
}

.chart-svg {
  display: block;
  width: 100%;
  height: auto;
  overflow: visible;
}

.axis-text {
  dominant-baseline: middle;
  fill: rgba(207, 250, 254, 0.72);
  font-size: 12px;
  font-weight: 700;
}

.axis-title {
  fill: rgba(207, 250, 254, 0.82);
  font-size: 12px;
  font-weight: 800;
}

.daily-chart-bar,
.hourly-chart-bar,
.daily-line-node,
.hourly-line-node,
.scatter-point-group,
.scatter-point-core,
.scatter-trend-line,
.scatter-risk-area {
  transition: opacity 200ms ease, filter 200ms ease, r 200ms ease, stroke-width 200ms ease, fill 200ms ease;
}

.daily-chart-bar,
.hourly-chart-bar {
  opacity: 0.9;

  &[data-hovered='true'] {
    filter: drop-shadow(0 0 12px rgba(122, 247, 255, 0.9));
  }
}

.daily-chart-bar[data-scan='true'] {
  opacity: 1;
  filter: drop-shadow(0 0 14px rgba(122, 247, 255, 0.95)) brightness(1.25);
}

.daily-chart-bar[data-dimmed='true'],
.hourly-chart-bar[data-dimmed='true'] {
  opacity: 0.42;
  filter: saturate(0.4);
}

.daily-bar-flow-line {
  fill: rgba(232, 244, 255, 0.72);
  opacity: 0.72;
  filter: drop-shadow(0 0 7px rgba(122, 247, 255, 0.86));
  animation: daily-bar-flow 3s linear infinite;
}

.daily-bar-top-glow {
  opacity: 0.68;
  filter: drop-shadow(0 0 9px rgba(122, 247, 255, 0.85));
  animation: chart-top-glow-breathe 2s ease-in-out infinite;
}

.daily-line-node[data-hovered='true'],
.hourly-line-node[data-hovered='true'] {
  filter: drop-shadow(0 0 10px rgba(0, 212, 255, 0.95));
}

.hourly-chart-bar[data-current='true'] {
  animation: current-period-pulse 2s ease-in-out infinite;
  filter: drop-shadow(0 0 10px rgba(0, 212, 255, 0.78));
}

.daily-card-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 8px;
  margin-top: 12px;
}

.daily-date-card,
.hourly-slot-card {
  border: 1px solid rgba(0, 212, 255, 0.24);
  background: rgba(8, 47, 73, 0.24);
  padding: 12px;

  span,
  small {
    display: block;
    color: rgba(207, 250, 254, 0.72);
    font-size: 13px;
    font-weight: 700;
  }

  b {
    display: block;
    margin-top: 5px;
    color: #f0fbff;
    font-family: var(--font-num);
    font-size: 20px;
  }

  &:hover,
  &[data-linked='true'],
  &[data-current='true'] {
    border-color: rgba(0, 212, 255, 0.7);
    background-color: rgba(0, 212, 255, 0.1);
    box-shadow: inset 0 0 18px rgba(0, 212, 255, 0.12);
  }
}

.data-analysis-heatmap-panel,
.data-analysis-composition-panel {
  min-height: 720px;
}

.heatmap-metrics {
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin-bottom: 12px;
}

.heatmap-frame {
  min-height: 590px;
}

.heatmap-grid {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  grid-template-rows: 28px minmax(0, 1fr) 22px;
  gap: 8px;
  height: 520px;
}

.heatmap-hour-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.heatmap-date-col {
  display: grid;
  grid-template-rows: repeat(7, minmax(0, 1fr));
  gap: 8px;
}

.heatmap-axis-label {
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(207, 250, 254, 0.7);
  font-size: 13px;
  font-weight: 800;
}

.heatmap-grid-body {
  position: relative;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  grid-template-rows: repeat(7, minmax(0, 1fr));
  gap: 8px;
  overflow: hidden;

  &::before {
    content: '';
    position: absolute;
    left: 0;
    right: 0;
    top: -10%;
    z-index: 12;
    height: 18px;
    pointer-events: none;
    background: linear-gradient(180deg, transparent, rgba(122, 247, 255, 0.28), transparent);
    filter: drop-shadow(0 0 9px rgba(0, 212, 255, 0.64));
    animation: heatmap-scanline 5s linear infinite;
  }
}

.heatmap-cell {
  position: relative;
  display: flex;
  min-height: 44px;
  flex-direction: column;
  justify-content: space-between;
  border: 1px solid rgba(0, 212, 255, 0.24);
  color: rgba(240, 251, 255, 0.86);
  padding: 9px;
  text-align: left;
  transform-origin: center;
  overflow: hidden;
  background: rgba(6, 24, 41, 0.88);

  &::before {
    content: '';
    position: absolute;
    inset: 0;
    opacity: 0.9;
    background: var(--cell-fill);
  }

  i {
    position: absolute;
    inset: auto 6px 6px 6px;
    height: 4px;
    background: rgba(8, 47, 73, 0.76);
    box-shadow: inset var(--cell-meter) 0 0 var(--cell-tone);
  }

  span {
    position: relative;
    font-size: 13px;
    font-weight: 800;
  }

  b {
    position: relative;
    align-self: flex-end;
    font-family: var(--font-num);
    font-size: 18px;
  }

  &[data-hovered='true'] {
    z-index: 2;
    transform: scale(1.08);
    border-color: rgba(0, 212, 255, 0.9);
    box-shadow: 0 0 18px rgba(0, 212, 255, 0.38), inset 0 0 10px rgba(122, 247, 255, 0.18);
  }

  &[data-peak='true'] {
    animation: peak-cell-gold-pulse 1.8s ease-in-out infinite;
  }

  &[data-related='true'] {
    filter: brightness(1.18) saturate(1.16);
  }

  &[data-dimmed='true'] {
    opacity: 0.72;
    filter: saturate(0.5) brightness(0.78);
  }

  &[data-sampled='true'] {
    filter: brightness(1.35) saturate(1.3);
    box-shadow: 0 0 18px rgba(0, 212, 255, 0.36), inset 0 0 18px rgba(122, 247, 255, 0.22);
  }
}

.heatmap-scale-row {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 12px;
  color: rgba(207, 250, 254, 0.72);
  font-size: 13px;
  font-weight: 800;
}

.heatmap-scale {
  position: relative;
  display: block;
  width: 144px;
  height: 8px;
  background: linear-gradient(90deg, rgba(0, 212, 255, 0.2), rgba(0, 212, 255, 0.72), rgba(34, 211, 160, 0.78), rgba(255, 184, 0, 0.9));
  box-shadow: 0 0 10px rgba(0, 212, 255, 0.18);

  &::after {
    content: '';
    position: absolute;
    top: -4px;
    width: 5px;
    height: 14px;
    background: #e8f4ff;
    box-shadow: 0 0 10px rgba(122, 247, 255, 0.85);
    animation: heatmap-scale-pointer 2s ease-in-out infinite;
  }
}

.hourly-summary-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.hourly-frame {
  margin-top: 12px;
}

.hourly-slot-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-top: 12px;
}

.composition-donut {
  position: relative;
  display: flex;
  justify-content: center;
  padding: 4px 0 14px;

  svg {
    width: 220px;
    height: 220px;
    overflow: visible;
    filter: drop-shadow(0 0 22px rgba(0, 212, 255, 0.24));
  }
}

.composition-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;

  b {
    color: #f0fbff;
    font-family: var(--font-num);
    font-size: 34px;
    line-height: 1;
    text-shadow: 0 0 14px rgba(122, 247, 255, 0.58);
  }

  span {
    margin-top: 6px;
    color: rgba(207, 250, 254, 0.74);
    font-size: 13px;
    font-weight: 800;
    letter-spacing: 0.06em;
  }
}

.composition-segment {
  transform: translate(0, 0);
  transform-origin: center;
  animation: composition-segment-breathe 3s ease-in-out infinite;

  &[data-hovered='true'] {
    transform: translate(var(--lift-x), var(--lift-y));
    filter: saturate(1.22) brightness(1.12);
  }

  &[data-dimmed='true'] {
    opacity: 0.62;
    filter: saturate(0.5) brightness(0.74);
  }
}

.composition-highlight {
  opacity: 0.45;
  transition: opacity 200ms ease;
}

.composition-segment:hover .composition-highlight {
  opacity: 0.95;
}

.composition-core-pulse {
  transform-box: fill-box;
  transform-origin: center;
  animation: composition-core-pulse 2.8s ease-in-out infinite;
}

.composition-scan-arc {
  transform-origin: center;
  animation: composition-scan-spin 4s linear infinite;
  filter: drop-shadow(0 0 9px rgba(122, 247, 255, 0.85));
}

.composition-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.composition-legend-item {
  min-width: 0;
  border: 1px solid rgba(0, 212, 255, 0.24);
  background: rgba(8, 47, 73, 0.2);
  padding: 10px;

  &:hover,
  &[data-linked='true'] {
    border-color: rgba(0, 212, 255, 0.7);
    background-color: rgba(0, 212, 255, 0.08);
    box-shadow: 0 0 14px rgba(0, 212, 255, 0.14);
  }

  span {
    display: flex;
    min-width: 0;
    align-items: center;
    gap: 7px;
    color: rgba(240, 251, 255, 0.86);
    font-size: 14px;
    font-weight: 700;
  }

  i {
    width: 8px;
    height: 8px;
    flex: 0 0 auto;
    border-radius: 999px;
    box-shadow: 0 0 10px currentColor;
  }

  b {
    display: block;
    margin-top: 8px;
    color: #f0fbff;
    font-family: var(--font-num);
  }

  div {
    height: 6px;
    margin-top: 7px;
    overflow: hidden;
    background: rgba(8, 47, 73, 0.8);
  }

  em {
    display: block;
    height: 100%;
    box-shadow: 0 0 14px currentColor;
  }
}

.composition-value-line,
.composition-bar-line {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.composition-value-line b {
  margin-top: 0;
  flex: 0 0 auto;
  font-size: 14px;
}

.composition-bar-line {
  margin-top: 8px;

  div {
    height: 6px;
    margin-top: 0;
    flex: 1 1 auto;
  }

  strong {
    width: 48px;
    flex: 0 0 auto;
    color: rgba(207, 250, 254, 0.76);
    font-family: var(--font-num);
    font-size: 14px;
    text-align: right;
  }
}

.composition-status-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.composition-status-card {
  border: 1px solid rgba(0, 212, 255, 0.24);
  background: rgba(8, 47, 73, 0.2);
  padding: 8px 12px;

  span {
    display: flex;
    align-items: center;
    gap: 8px;
    color: rgba(207, 250, 254, 0.74);
    font-size: 14px;
  }

  i {
    width: 8px;
    height: 8px;
    border-radius: 999px;
    box-shadow: 0 0 10px currentColor;
  }

  b {
    display: block;
    margin-top: 4px;
    color: #f0fbff;
    font-family: var(--font-num);
    font-size: 18px;
    line-height: 1;
  }
}

.scatter-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.scatter-topline {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: start;
}

.scatter-legend {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid rgba(0, 212, 255, 0.24);
  color: rgba(207, 250, 254, 0.82);
  background: rgba(4, 21, 39, 0.84);
  padding: 7px 10px;
  font-size: 14px;
  font-weight: 800;
  backdrop-filter: blur(8px);

  i {
    width: 10px;
    height: 10px;
    border-radius: 999px;
  }

  &[data-hidden='true'] {
    opacity: 0.38;
    filter: saturate(0.35);
  }
}

.correlation-value {
  text-align: right;

  span {
    display: block;
    color: rgba(207, 250, 254, 0.72);
    font-size: 13px;
    font-weight: 800;
  }

  b {
    display: block;
    margin-top: 3px;
    color: #f0fbff;
    font-family: var(--font-num);
    font-size: 30px;
    line-height: 1;
  }
}

.scatter-frame {
  margin-top: 10px;
  padding: 48px 12px 12px;
}

.scatter-floating-legend {
  position: absolute;
  right: 16px;
  top: 12px;
  z-index: 10;
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  color: rgba(207, 250, 254, 0.82);
  font-size: 14px;
  font-weight: 800;
}

.scatter-point-group[data-dimmed='true'] {
  opacity: 0.2;
  filter: saturate(0.4);
}

.scatter-point-group[data-highlighted='true'] {
  opacity: 1;
  filter: saturate(1.25) brightness(1.16) drop-shadow(0 0 10px rgba(122, 247, 255, 0.58));
}

.scatter-point-group[data-highlighted='true'] .scatter-point-core {
  filter: drop-shadow(0 0 10px rgba(122, 247, 255, 0.95)) drop-shadow(0 0 18px rgba(0, 212, 255, 0.55));
}

.scatter-point-group[data-tone='rose'] .scatter-point-core {
  animation: scatter-warning-pulse 1.5s ease-in-out infinite;
}

.scatter-point-group[data-sampled='true'] .scatter-point-core {
  transform-origin: center;
  animation: scatter-sample-pop 500ms ease-out;
}

.scatter-risk-area {
  animation: scatter-risk-border-pulse 3s ease-in-out infinite;
  stroke: rgba(255, 184, 0, 0.28);
  stroke-width: 1.6;

  &[data-hovered='true'] {
    fill: rgba(255, 184, 0, 0.11);
    stroke: rgba(255, 184, 0, 0.72);
    filter: drop-shadow(0 0 14px rgba(255, 184, 0, 0.32));
  }
}

.scatter-trend-hit {
  opacity: 0;
  pointer-events: stroke;
}

.scatter-trend-line {
  filter: drop-shadow(0 0 10px rgba(122, 247, 255, 0.45));

  &[data-hovered='true'] {
    filter: drop-shadow(0 0 12px rgba(122, 247, 255, 0.88)) drop-shadow(0 0 20px rgba(255, 184, 0, 0.4));
  }
}

.scatter-trend-traveler {
  filter: drop-shadow(0 0 7px rgba(122, 247, 255, 0.95)) drop-shadow(0 0 16px rgba(0, 212, 255, 0.68));
}

.scatter-stat-cards {
  position: absolute;
  right: 16px;
  bottom: 16px;
  display: grid;
  grid-template-columns: repeat(2, minmax(92px, 1fr));
  gap: 8px;
  text-align: right;
}

.scatter-stat-card {
  background: rgba(4, 21, 39, 0.76);
  backdrop-filter: blur(8px);
}

.detail-panel {
  min-height: 520px;
}

.detail-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  color: rgba(207, 250, 254, 0.78);
  font-size: 14px;
  font-weight: 800;

  > div:first-child {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    border: 1px solid rgba(34, 211, 160, 0.3);
    background: rgba(34, 211, 160, 0.1);
    color: #d1fae5;
    padding: 7px 12px;
  }
}

.table-live-dot,
.new-data-badge {
  display: inline-block;
  border-radius: 999px;
  background: var(--color-emerald);
  box-shadow: 0 0 10px rgba(34, 211, 160, 0.9);
  animation: status-dot-breathe 1s ease-in-out infinite;
}

.table-live-dot {
  width: 8px;
  height: 8px;
}

.new-data-badge {
  width: 7px;
  height: 7px;
  background: #ff4d8d;
  box-shadow: 0 0 10px rgba(255, 77, 141, 0.9);
}

.detail-time-cell {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  gap: 7px;
}

.detail-live-counter {
  text-shadow: 0 0 10px rgba(0, 212, 255, 0.18);
}

.detail-table-wrap {
  overflow: hidden;
  border: 1px solid rgba(0, 212, 255, 0.36);
  background: rgba(8, 47, 73, 0.2);
  box-shadow: 0 0 16px rgba(0, 212, 255, 0.12);
}

table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  color: rgba(240, 251, 255, 0.88);
  font-size: 17px;
}

th {
  background: rgba(8, 47, 73, 0.8);
  color: #f0fbff;
  font-size: 16px;
  font-weight: 900;
  letter-spacing: 0.06em;
  text-align: left;
}

th,
td {
  overflow: hidden;
  padding: 14px 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

td {
  border-top: 1px solid rgba(0, 212, 255, 0.18);
}

.detail-table-row {
  position: relative;

  &:hover {
    background-color: rgba(0, 212, 255, 0.09);
    box-shadow: inset 3px 0 0 #00d4ff;
  }

  &[data-new='true'] {
    animation: detail-row-slide-in 200ms ease-out, detail-row-cyan-pulse 1s ease-out;
  }

  &[data-warning='true'] {
    animation: detail-row-warning-pulse 2s ease-out;
  }
}

.detail-table-value {
  color: #f0fbff;
  font-family: var(--font-num);
  text-align: right;

  &:hover {
    color: var(--color-cyan-bright);
    text-shadow: 0 0 10px rgba(0, 212, 255, 0.7);
  }
}

.detail-table-head span::after {
  content: '⇅';
  margin-left: 6px;
  color: var(--color-cyan);
  opacity: 0;
  transition: opacity 200ms ease;
}

.detail-table-head:hover span::after {
  opacity: 0.85;
}

.detail-status-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 48px;
  border: 1px solid rgba(34, 211, 160, 0.4);
  color: #d1fae5;
  background: rgba(34, 211, 160, 0.08);
  padding: 4px 8px;
  font-size: 12px;
  font-weight: 800;

  &[data-status='warning'] {
    border-color: rgba(255, 77, 109, 0.4);
    color: #fecdd3;
    background: rgba(255, 77, 109, 0.08);
  }

  &[data-status='maintenance'] {
    border-color: rgba(255, 184, 0, 0.4);
    color: #fde68a;
    background: rgba(255, 184, 0, 0.08);
  }
}

.col-building {
  width: 12%;
}

.col-time {
  width: 15%;
}

.col-num {
  width: 7%;
}

.col-device {
  width: 20%;
}

.col-status {
  width: 9%;
}

.realtime-sync-widget {
  position: fixed;
  right: 20px;
  top: 96px;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 9px;
  border: 1px solid rgba(0, 212, 255, 0.36);
  color: #f0fbff;
  background: rgba(4, 21, 39, 0.86);
  padding: 7px 12px;
  box-shadow: 0 0 18px rgba(0, 212, 255, 0.18);
  backdrop-filter: blur(10px);
  clip-path: polygon(9px 0, 100% 0, 100% calc(100% - 9px), calc(100% - 9px) 100%, 0 100%, 0 9px);
}

.sync-radar {
  position: relative;
  display: inline-flex;
  width: 22px;
  height: 22px;
  border: 1px solid rgba(122, 247, 255, 0.62);
  border-radius: 999px;
  box-shadow: 0 0 12px rgba(0, 212, 255, 0.28);

  &::before {
    content: '';
    position: absolute;
    inset: 3px;
    border-radius: inherit;
    background: conic-gradient(from 0deg, rgba(122, 247, 255, 0.88), transparent 42%);
    animation: sync-radar-spin 1.2s linear infinite;
  }

  &::after {
    content: '';
    position: absolute;
    left: 50%;
    top: 50%;
    width: 4px;
    height: 4px;
    border-radius: 999px;
    background: #7af7ff;
    box-shadow: 0 0 9px rgba(122, 247, 255, 0.95);
    transform: translate(-50%, -50%);
  }
}

.sync-copy {
  display: flex;
  flex-direction: column;
  line-height: 1.15;

  span {
    font-size: 14px;
    font-weight: 900;
  }

  small {
    margin-top: 3px;
    color: rgba(207, 250, 254, 0.74);
    font-size: 12px;
    font-weight: 800;
  }
}

.dashboard-toast-stack {
  position: fixed;
  right: 20px;
  top: 138px;
  z-index: 28;
  display: flex;
  width: 330px;
  flex-direction: column;
  gap: 8px;
}

.dashboard-toast {
  position: relative;
  overflow: hidden;
  border: 1px solid rgba(0, 212, 255, 0.56);
  color: #e8f4ff;
  background: rgba(8, 20, 40, 0.92);
  padding: 11px 13px 12px 14px;
  box-shadow: 0 0 20px rgba(0, 212, 255, 0.2);
  backdrop-filter: blur(10px);
  animation: dashboard-toast-life 3.2s ease forwards;

  &::before {
    content: '';
    position: absolute;
    inset: 0 auto 0 0;
    width: 3px;
    background: var(--color-cyan);
    box-shadow: 0 0 12px rgba(0, 212, 255, 0.7);
  }

  &[data-tone='rose'] {
    border-color: rgba(255, 77, 141, 0.68);
    box-shadow: 0 0 22px rgba(255, 77, 141, 0.22);

    &::before {
      background: #ff4d8d;
      box-shadow: 0 0 12px rgba(255, 77, 141, 0.78);
    }
  }

  &[data-tone='emerald']::before {
    background: var(--color-emerald);
    box-shadow: 0 0 12px rgba(34, 211, 160, 0.76);
  }
}

.dashboard-toast-title {
  color: var(--color-cyan-bright);
  font-size: 14px;
  font-weight: 900;
}

.dashboard-toast-body {
  margin-top: 4px;
  color: rgba(224, 240, 255, 0.82);
  font-size: 13px;
  line-height: 1.45;
}

.dashboard-tooltip {
  position: fixed;
  z-index: 9999;
  min-width: 200px;
  max-width: 320px;
  padding: 12px 16px;
  pointer-events: none;
  color: #e0f0ff;
  font-size: 13px;
  line-height: 1.6;
  text-align: left;
  background: rgba(8, 20, 40, 0.95);
  border: 1px solid var(--color-cyan);
  border-radius: 6px;
  box-shadow: 0 0 20px rgba(0, 212, 255, 0.3);
  opacity: 0;
  transform: translateY(4px);
  backdrop-filter: blur(8px);
  transition: opacity 200ms ease, transform 200ms ease;

  &[data-visible='true'] {
    opacity: 1;
    transform: translateY(0);
  }
}

.dashboard-tooltip-title {
  margin-bottom: 6px;
  padding-bottom: 4px;
  color: var(--color-cyan);
  font-size: 14px;
  font-weight: 800;
  border-bottom: 1px solid rgba(0, 212, 255, 0.3);
}

.dashboard-tooltip-body {
  margin-bottom: 6px;
  color: #b8d8f4;
}

.dashboard-tooltip-row {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  margin: 4px 0;
}

.dashboard-tooltip-label {
  color: #8aa8c8;
  white-space: nowrap;
}

.dashboard-tooltip-value {
  color: #fff;
  font-weight: 600;
  text-align: right;

  &[data-tone='amber'] {
    color: var(--color-amber);
  }

  &[data-tone='emerald'] {
    color: var(--color-emerald);
  }

  &[data-tone='rose'] {
    color: #ff4d8d;
  }
}

.dashboard-tooltip-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid rgba(0, 212, 255, 0.18);
}

.dashboard-tooltip-action {
  border: 1px solid rgba(0, 212, 255, 0.42);
  color: var(--color-cyan-bright);
  background: rgba(0, 212, 255, 0.12);
  padding: 4px 8px;
  font-size: 12px;
  font-weight: 700;
}

.issue-pill {
  position: fixed;
  left: 16px;
  bottom: 16px;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid rgba(255, 77, 141, 0.55);
  border-radius: 999px;
  color: #ffd6e5;
  background: rgba(25, 9, 26, 0.88);
  padding: 8px 12px;
  font-size: 14px;
  font-weight: 900;
  box-shadow: 0 0 18px rgba(255, 77, 141, 0.22);
  backdrop-filter: blur(10px);

  &:hover {
    transform: translateY(-1px);
    border-color: rgba(255, 77, 141, 0.9);
    box-shadow: 0 0 22px rgba(255, 77, 141, 0.38);
  }
}

.issue-pill-badge {
  display: flex;
  min-width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #020817;
  background: #ff4d8d;
  padding: 0 6px;
}

.text-cyan {
  color: var(--color-cyan-bright) !important;
}

.text-amber {
  color: var(--color-amber) !important;
}

.text-emerald {
  color: var(--color-emerald) !important;
}

.text-rose {
  color: var(--color-rose) !important;
}

@media (max-width: 1280px) {
  .data-header {
    align-items: flex-start;
  }

  .title-plate {
    display: none;
  }

  .data-header-status {
    display: none;
  }

  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .data-analysis-main-grid,
  .scatter-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .data-header {
    position: relative;
  }

  .cyber-nav-shell {
    width: 100%;
    overflow-x: auto;
  }

  .metric-grid,
  .daily-card-grid,
  .composition-grid,
  .heatmap-metrics,
  .hourly-summary-grid {
    grid-template-columns: 1fr;
  }

  .health-layout {
    grid-template-columns: 1fr;
    justify-items: center;
  }

  .realtime-sync-widget,
  .dashboard-toast-stack {
    display: none;
  }
}

@keyframes status-dot-breathe {
  0%, 100% {
    box-shadow: 0 0 0 4px rgba(34, 211, 160, 0.1), 0 0 14px rgba(34, 211, 160, 0.85);
  }
  50% {
    box-shadow: 0 0 0 7px rgba(34, 211, 160, 0.05), 0 0 22px rgba(34, 211, 160, 1);
  }
}

@keyframes cockpit-flow-band {
  from { background-position: -60% 0, 0 0; }
  to { background-position: 160% 0, 0 0; }
}

@keyframes cockpit-particle-rise {
  from { background-position: -60% 0, 0 0; }
  to { background-position: 160% 0, 0 -180px; }
}

@keyframes data-rain-fall {
  from { transform: translateY(-18%); }
  to { transform: translateY(18%); }
}

@keyframes mini-trend-slide {
  0%, 100% { opacity: 0.74; transform: translateX(0); }
  50% { opacity: 1; transform: translateX(2px); }
}

@keyframes status-bell-wiggle {
  0%, 100% { transform: rotate(0deg); }
  25% { transform: rotate(-9deg); }
  55% { transform: rotate(7deg); }
  75% { transform: rotate(-4deg); }
}

@keyframes status-gear-spin {
  to { transform: rotate(360deg); }
}

@keyframes number-flash-cyan {
  0% { text-shadow: 0 0 0 rgba(0, 212, 255, 0); }
  35% {
    color: var(--color-cyan-bright);
    text-shadow: 0 0 16px rgba(0, 212, 255, 0.95);
  }
  100% { text-shadow: 0 0 0 rgba(0, 212, 255, 0); }
}

@keyframes number-flash-amber {
  0% { text-shadow: 0 0 0 rgba(255, 184, 0, 0); }
  35% {
    color: var(--color-amber);
    text-shadow: 0 0 16px rgba(255, 184, 0, 0.9);
  }
  100% { text-shadow: 0 0 0 rgba(255, 184, 0, 0); }
}

@keyframes live-delta-pop {
  0% {
    opacity: 0;
    transform: translateY(4px) scale(0.82);
  }
  18% {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
  100% {
    opacity: 0;
    transform: translateY(-10px) scale(0.94);
  }
}

@keyframes health-orbit-spin {
  to { transform: rotate(360deg); }
}

@keyframes bar-flow-light {
  from { transform: translateX(-110%); }
  to { transform: translateX(110%); }
}

@keyframes top-risk-gold-pulse {
  0%, 100% { box-shadow: 0 0 0 rgba(255, 184, 0, 0); }
  50% { box-shadow: 0 0 18px rgba(255, 184, 0, 0.24); }
}

@keyframes daily-bar-flow {
  0% { opacity: 0; transform: translateY(26px); }
  18%, 70% { opacity: 0.78; }
  100% { opacity: 0; transform: translateY(-64px); }
}

@keyframes chart-top-glow-breathe {
  0%, 100% { opacity: 0.5; transform: scale(0.88); }
  50% { opacity: 1; transform: scale(1.22); }
}

@keyframes current-period-pulse {
  0%, 100% { opacity: 0.9; }
  50% { opacity: 1; }
}

@keyframes heatmap-scanline {
  from { transform: translateY(-16px); }
  to { transform: translateY(1200%); }
}

@keyframes heatmap-scale-pointer {
  0%, 100% { left: 4%; }
  50% { left: 92%; }
}

@keyframes peak-cell-gold-pulse {
  0%, 100% {
    box-shadow: 0 0 14px rgba(255, 184, 0, 0.22), inset 0 0 8px rgba(255, 184, 0, 0.08);
  }
  50% {
    box-shadow: 0 0 24px rgba(255, 184, 0, 0.55), inset 0 0 16px rgba(255, 184, 0, 0.2);
  }
}

@keyframes composition-scan-spin {
  to { transform: rotate(360deg); }
}

@keyframes composition-segment-breathe {
  0%, 100% { opacity: 0.86; filter: brightness(1); }
  50% { opacity: 1; filter: brightness(1.08); }
}

@keyframes composition-core-pulse {
  0%, 100% {
    opacity: 0.82;
    filter: drop-shadow(0 0 8px rgba(122, 247, 255, 0.32));
    transform: scale(1);
  }
  50% {
    opacity: 1;
    filter: drop-shadow(0 0 18px rgba(122, 247, 255, 0.68));
    transform: scale(1.045);
  }
}

@keyframes scatter-warning-pulse {
  0%, 100% { filter: drop-shadow(0 0 7px rgba(255, 77, 141, 0.5)); }
  50% { filter: drop-shadow(0 0 16px rgba(255, 77, 141, 0.95)); }
}

@keyframes scatter-sample-pop {
  0% { transform: scale(1); }
  42% { transform: scale(1.6); }
  100% { transform: scale(1); }
}

@keyframes scatter-risk-border-pulse {
  0%, 100% { stroke-opacity: 0.18; }
  50% { stroke-opacity: 0.78; }
}

@keyframes sync-radar-spin {
  to { transform: rotate(360deg); }
}

@keyframes dashboard-toast-life {
  0% { opacity: 0; transform: translateX(18px); }
  10%, 82% { opacity: 1; transform: translateX(0); }
  100% { opacity: 0; transform: translateX(10px); }
}

@keyframes detail-row-slide-in {
  from { opacity: 0; transform: translateY(-8px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes detail-row-cyan-pulse {
  0% { background-color: rgba(0, 212, 255, 0.3); }
  100% { background-color: transparent; }
}

@keyframes detail-row-warning-pulse {
  0%, 100% { background-color: transparent; }
  18%, 52% {
    background-color: rgba(255, 77, 141, 0.18);
    box-shadow: inset 3px 0 0 #ff4d8d, 0 0 18px rgba(255, 77, 141, 0.24);
  }
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
