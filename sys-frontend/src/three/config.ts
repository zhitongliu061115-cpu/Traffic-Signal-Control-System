// ================================================================
// Three.js 数字孪生 — 全局配置与坐标映射
// ================================================================
import { Color } from 'three'

/** 世界尺寸：归一化坐标 (0-1) 映射到该尺寸的平面 */
export const WORLD = {
  /** 平面宽度（X 轴范围 -SIZE/2 ~ SIZE/2） */
  SIZE_X: 800,
  /** 平面深度（Z 轴范围 -SIZE/2 ~ SIZE/2） */
  SIZE_Z: 500,
  /** 路口节点半径 */
  NODE_RADIUS: 14,
  /** 道路宽度 */
  ROAD_WIDTH: 8,
  /** 道路高度（BoxGeometry 厚度） */
  ROAD_HEIGHT: 2,
  /** 车辆尺寸 */
  VEHICLE_SIZE: 6,
  /** 车辆最大实例数（预留 InstancedMesh 容量，支持 1000+） */
  MAX_VEHICLES: 2000,
} as const

/** 拥堵配色阈值（与 SVG 版规则一致） */
export const CONGESTION_COLORS = {
  smooth: '#22D3A0', // 0-30 畅通
  slow: '#FFB800', // 30-60 缓行
  busy: '#FF7A45', // 60-80 拥堵
  jam: '#FF4D6D', // 80-100 严重拥堵
} as const

/** 主题色 */
export const THEME = {
  bg: '#020817',
  nodeNormal: '#00D4FF',
  nodeSelected: '#FFD24A',
  deviceOnline: '#22D3A0',
  deviceOffline: '#5A7595',
  deviceFault: '#FF4D6D',
  signalGreen: '#22D3A0',
  signalYellow: '#FFB800',
  signalRed: '#FF4D6D',
  emergency: '#00E5FF',
  grid: '#0a2540',
} as const

/**
 * 归一化坐标 (0-1) → Three.js 世界坐标。
 * x → X 轴，y → Z 轴（俯视时 Z 为屏幕纵向）。
 */
export function toWorldX(nx: number): number {
  return (nx - 0.5) * WORLD.SIZE_X
}

export function toWorldZ(ny: number): number {
  return (ny - 0.5) * WORLD.SIZE_Z
}

/** 根据拥堵指数返回颜色字符串 */
export function congestionColorHex(ci: number): string {
  if (ci < 30) return CONGESTION_COLORS.smooth
  if (ci < 60) return CONGESTION_COLORS.slow
  if (ci < 80) return CONGESTION_COLORS.busy
  return CONGESTION_COLORS.jam
}

/** 根据拥堵指数返回 THREE.Color */
export function congestionColor(ci: number): Color {
  return new Color(congestionColorHex(ci))
}

/** 缓存 Color 实例，避免高频创建 */
const colorCache = new Map<string, Color>()
export function cachedColor(hex: string): Color {
  let c = colorCache.get(hex)
  if (!c) {
    c = new Color(hex)
    colorCache.set(hex, c)
  }
  return c
}
