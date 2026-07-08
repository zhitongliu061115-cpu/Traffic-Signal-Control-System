// ================================================================
// CoordinateHelper.ts — MercatorCoordinate 转换工具
// 全项目统一：所有 Three.js 定位必须经过此模块
// ================================================================
import maplibregl from 'maplibre-gl'
import * as THREE from 'three'
import type { Intersection, Road } from '@/types/traffic'

/** lng/lat → MercatorCoordinate */
export function toMercator(lng: number, lat: number, altitude = 0): maplibregl.MercatorCoordinate {
  return maplibregl.MercatorCoordinate.fromLngLat({ lng, lat }, altitude)
}

/** 单段道路几何信息 */
export interface RoadTransform {
  start: maplibregl.MercatorCoordinate
  end: maplibregl.MercatorCoordinate
  mid: THREE.Vector3
  length: number
  angle: number
}

/** 多段路径的道路几何（从 path 生成） */
export interface RoadPathTransform {
  segments: RoadTransform[]
  totalLength: number
  /** 整条路两端的经纬度 */
  startCoord: [number, number]
  endCoord: [number, number]
}

const roadCache = new Map<string, RoadPathTransform>()

/** 从道路 path 字段（或 from→to 直线降级）生成多段路径变换 */
export function getRoadPathTransform(
  r: Road,
  intersections: Intersection[],
): RoadPathTransform | null {
  const cached = roadCache.get(r.id)
  if (cached) return cached

  // 用 path 字段；如果为空则降级为 from→to 两点直线
  const points: [number, number][] =
    r.path && r.path.length >= 2 ? r.path : (() => {
      const from = intersections.find((i) => i.id === r.from)
      const to = intersections.find((i) => i.id === r.to)
      if (!from || !to) return []
      return [[from.lng, from.lat], [to.lng, to.lat]] as [number, number][]
    })()

  if (points.length < 2) return null

  const segments: RoadTransform[] = []
  let totalLen = 0

  for (let i = 0; i < points.length - 1; i++) {
    const s = toMercator(points[i]![0], points[i]![1], 0)
    const e = toMercator(points[i + 1]![0], points[i + 1]![1], 0)
    const sv = new THREE.Vector3(s.x, s.y, s.z)
    const ev = new THREE.Vector3(e.x, e.y, e.z)
    const len = sv.distanceTo(ev)
    totalLen += len
    segments.push({
      start: s, end: e,
      mid: sv.clone().add(ev).multiplyScalar(0.5),
      length: len,
      angle: Math.atan2(ev.x - sv.x, ev.y - sv.y),
    })
  }

  // 沿线总进度对应的 Mercator 位置（用于车辆定位）
  const cumulative: number[] = []
  let acc = 0
  for (const seg of segments) { acc += seg.length; cumulative.push(acc) }

  const result: RoadPathTransform = {
    segments, totalLength: totalLen,
    startCoord: points[0]!, endCoord: points[points.length - 1]!,
  }
  roadCache.set(r.id, result)
  return result
}

/** 根据 progress(0-1) 在多段路径上插值 World 坐标 */
export function vehiclePathPosition(
  r: Road,
  intersections: Intersection[],
  progress: number,
  laneIndex: number,
): THREE.Vector3 | null {
  const pt = getRoadPathTransform(r, intersections)
  if (!pt || pt.segments.length === 0) return null

  const p = Math.max(0, Math.min(1, progress))
  const targetDist = pt.totalLength * p
  const laneWidth = 0.00006

  // 找到 progress 落在哪一段
  let acc = 0
  for (const seg of pt.segments) {
    if (targetDist <= acc + seg.length || seg === pt.segments[pt.segments.length - 1]) {
      const localP = seg.length > 0 ? (targetDist - acc) / seg.length : 0
      const cp = Math.max(0, Math.min(1, localP))
      const alongX = seg.start.x + (seg.end.x - seg.start.x) * cp
      const alongY = seg.start.y + (seg.end.y - seg.start.y) * cp

      // 车道法向偏移
      const dx = seg.end.x - seg.start.x
      const dy = seg.end.y - seg.start.y
      const len = seg.length || 1
      const nx = -dy / len
      const ny = dx / len
      const offset = (laneIndex - (r.laneCount - 1) / 2) * laneWidth

      return new THREE.Vector3(alongX + nx * offset, alongY + ny * offset, 0)
    }
    acc += seg.length
  }
  // fallback：末段终点
  const last = pt.segments[pt.segments.length - 1]!
  return new THREE.Vector3(last.end.x, last.end.y, last.end.z)
}

/** 清除缓存 */
/** 清除缓存（道路 path 数据变化时调用） */
export function clearRoadCache(): void {
  roadCache.clear()
}
