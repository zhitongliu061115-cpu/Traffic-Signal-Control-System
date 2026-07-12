// ================================================================
// Simulation API — 仿真会话 REST 操作 + 路网获取
// ================================================================
import type {
  CreateSimulationRequest,
  CreateSimulationResponse,
  DispatchRequest,
  DispatchResponse,
  SimRoadnetResponse,
} from '@/types/traffic'

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080')
  .trim()
  .replace(/\/$/, '')

interface ApiResponse<T> {
  success: boolean
  message: string
  data: T
}

/** 通用 JSON 解析 + 业务错误处理 */
async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options,
  })

  const text = await response.text()
  const json: ApiResponse<T> | T = text ? JSON.parse(text) : null

  if (!response.ok) {
    const msg =
      json && typeof json === 'object' && 'message' in json
        ? (json as ApiResponse<T>).message
        : `${response.status} ${response.statusText}`
    throw new Error(msg)
  }

  // Spring Boot 统一 ApiResponse 封装 → 自动解包
  if (json && typeof json === 'object' && 'success' in json && 'data' in json) {
    const wrapped = json as ApiResponse<T>
    if (!wrapped.success) throw new Error(wrapped.message || '后端返回失败')
    return wrapped.data
  }

  return json as T
}

// ================================================================
// 路网
// ================================================================

/** 获取场景静态路网（路口、道路、相位定义） */
export function fetchRoadnet(sceneId: string): Promise<SimRoadnetResponse> {
  return request(`/api/v1/scenes/${sceneId}/roadnet`)
}

// ================================================================
// 仿真会话
// ================================================================

/** 创建仿真会话 */
export function createSimulation(
  params: CreateSimulationRequest,
): Promise<CreateSimulationResponse> {
  return request('/api/v1/simulations', {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

/** 启动仿真 */
export function startSimulation(sid: string): Promise<void> {
  return request(`/api/v1/simulations/${sid}/start`, { method: 'POST', body: '{}' })
}

/** 暂停仿真 */
export function pauseSimulation(sid: string): Promise<void> {
  return request(`/api/v1/simulations/${sid}/pause`, { method: 'POST', body: '{}' })
}

/** 停止仿真 */
export function stopSimulation(sid: string): Promise<void> {
  return request(`/api/v1/simulations/${sid}/stop`, { method: 'POST', body: '{}' })
}

// ================================================================
// 应急调度
// ================================================================

/** 调度应急车辆进入仿真 */
export function dispatchEmergency(
  sid: string,
  params: DispatchRequest,
): Promise<DispatchResponse> {
  return request(`/api/v1/simulations/${sid}/dispatch`, {
    method: 'POST',
    body: JSON.stringify(params),
  })
}
