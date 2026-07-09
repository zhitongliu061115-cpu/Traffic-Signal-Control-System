import type {
  Alert,
  AssistantReplies,
  CompareMetrics,
  CongestionTrendPoint,
  EmergencyVehicle,
  GlobalStatistics,
  Intersection,
  Road,
  Vehicle,
} from '@/types/traffic'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

interface ApiResponse<T> {
  success: boolean
  message: string
  data: T
}

interface DashboardRoadResponse extends Omit<Road, 'path'> {
  pathJson: string
}

interface DashboardBootstrapResponse {
  intersections: Intersection[]
  roads: DashboardRoadResponse[]
  vehicles: Vehicle[]
  emergencyVehicle: EmergencyVehicle
  emergencyRoute: string[]
  alerts: Alert[]
  statistics: GlobalStatistics
  compareMetrics: CompareMetrics
  congestionTrend: CongestionTrendPoint[]
  assistantReplies: AssistantReplies
}

export interface DashboardBootstrapData {
  intersections: Intersection[]
  roads: Road[]
  vehicles: Vehicle[]
  emergencyVehicle: EmergencyVehicle
  emergencyRoute: string[]
  alerts: Alert[]
  statistics: GlobalStatistics
  compareMetrics: CompareMetrics
  congestionTrend: CongestionTrendPoint[]
  assistantReplies: AssistantReplies
}

function parseRoadPath(pathJson: string): [number, number][] {
  const parsed = JSON.parse(pathJson) as unknown
  if (!Array.isArray(parsed)) return []
  return parsed
    .filter((point): point is [number, number] =>
      Array.isArray(point) &&
      point.length >= 2 &&
      typeof point[0] === 'number' &&
      typeof point[1] === 'number',
    )
    .map((point) => [point[0], point[1]])
}

export async function fetchDashboardBootstrap(): Promise<DashboardBootstrapData> {
  const response = await fetch(`${API_BASE_URL}/api/v1/dashboard/bootstrap`)
  if (!response.ok) {
    throw new Error(`dashboard bootstrap failed: ${response.status}`)
  }

  const body = (await response.json()) as ApiResponse<DashboardBootstrapResponse>
  if (!body.success) {
    throw new Error(body.message || 'dashboard bootstrap failed')
  }

  return {
    ...body.data,
    roads: body.data.roads.map(({ pathJson, ...road }) => ({
      ...road,
      path: parseRoadPath(pathJson),
    })),
  }
}
