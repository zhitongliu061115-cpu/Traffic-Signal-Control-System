const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

type Tone = 'amber' | 'emerald' | 'rose' | 'sky'
type StatusTone = 'amber' | 'emerald' | 'rose' | 'slate'

interface ApiResponse<T> {
  success: boolean
  message: string
  data: T
}

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
  control_strategy: 'FixedTime' | 'MaxPressure' | 'RL' | 'Traffic-R1' | '应急绿波'
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

interface DashboardToast {
  body: string
  id: number
  title: string
  tone: 'cyan' | 'emerald' | 'rose'
}

export interface DataAnalysisBootstrapData {
  sampleCount: number
  sampleRate: number
  healthScore: number
  sampledPointId: string
  metrics: MonitoringMetric[]
  statusDistribution: StatusBucket[]
  dailySeries: DailyPoint[]
  hourlySeries: HourlyPoint[]
  buildingSummaries: BuildingSummary[]
  heatmap: HeatmapCell[]
  composition: CompositionItem[]
  scatterPoints: ScatterPoint[]
  records: MonitoringRecord[]
  toasts: DashboardToast[]
}

export async function fetchDataAnalysisBootstrap(): Promise<DataAnalysisBootstrapData> {
  const response = await fetch(`${API_BASE_URL}/api/v1/data-analysis/bootstrap`)
  if (!response.ok) {
    throw new Error(`data analysis bootstrap failed: ${response.status}`)
  }

  const body = (await response.json()) as ApiResponse<DataAnalysisBootstrapData>
  if (!body.success) {
    throw new Error(body.message || 'data analysis bootstrap failed')
  }

  return body.data
}
