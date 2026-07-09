// ================================================================
// geoSnap.ts — 地理吸附：把 mock 坐标吸附到底图真实矢量道路
//
// 核心思路：
//   1. 不猜图层名（正则不可靠），用 map.getStyle().layers 动态发现
//      所有 type==='line' 的底图矢量层，拿它们的真实 layer id 列表。
//   2. 所有 queryRenderedFeatures 调用都传入这个列表作为 { layers: [...] }，
//      让 MapLibre 原生在 GPU 侧过滤——比 JS 正则快且 100% 准确。
//   3. 用 turf 做最近点/最近线匹配和交叉点查找。
//
// 降级：任何一步找不到附近真实道路，返回 null / 保留原始坐标，
// 不抛错、不中断渲染。
// ================================================================
import type maplibregl from 'maplibre-gl'
import { lineString as turfLineString, point as turfPoint } from '@turf/helpers'
import nearestPointOnLine from '@turf/nearest-point-on-line'
import lineIntersect from '@turf/line-intersect'
import distance from '@turf/distance'

type LngLat = [number, number]

/** 搜索半径（像素） */
const SEARCH_RADIUS_PX = 60
/** 吸附最大距离（米），超过视为"附近无路" */
const MAX_SNAP_DISTANCE_M = 120

/** 我们自己的 overlay 图层（永远不参与吸附） */
const OWN_LAYERS = new Set([
  'roads-layer', 'roads-heat', 'roads-selected',
  'road-select-highlight-layer', 'intersection-dots', 'intersection-signal-ring',
])

// ---- 动态发现底图道路图层 ----
let _discoveredLayerIds: string[] | null = null

function discoverLineLayerIds(map: maplibregl.Map): string[] {
  // 缓存只在一轮会话里有效；为空说明还没成功发现，继续重试
  if (_discoveredLayerIds !== null && _discoveredLayerIds.length > 0) return _discoveredLayerIds

  try {
    const style = map.getStyle()
    if (!style?.layers) return []
    _discoveredLayerIds = style.layers
      .filter((l) => l.type === 'line' && !OWN_LAYERS.has(l.id))
      .map((l) => l.id)
  } catch {
    // 样式还没加载完，返回空等待下次重试
    return []
  }

  return _discoveredLayerIds ?? []
}

export function resetDiscoveryCache(): void {
  _discoveredLayerIds = null
}

// ---- 查询 ----

/** 查询目标像素点附近的真实道路 LineString 坐标 */
export function queryNearbyRoadLines(
  map: maplibregl.Map,
  lngLat: LngLat,
  pixelRadius = SEARCH_RADIUS_PX,
): LngLat[][] {
  const layers = discoverLineLayerIds(map)
  if (layers.length === 0) return []

  const px = map.project(lngLat)
  const bbox: [maplibregl.PointLike, maplibregl.PointLike] = [
    [px.x - pixelRadius, px.y - pixelRadius],
    [px.x + pixelRadius, px.y + pixelRadius],
  ]
  const features = map.queryRenderedFeatures(bbox, { layers })
  const lines: LngLat[][] = []

  for (const f of features) {
    const geom = f.geometry as { type: string; coordinates?: unknown }
    if (geom.type === 'LineString' && Array.isArray(geom.coordinates)) {
      lines.push(geom.coordinates as LngLat[])
    } else if (geom.type === 'MultiLineString' && Array.isArray(geom.coordinates)) {
      for (const part of geom.coordinates as LngLat[][]) lines.push(part)
    }
  }
  return lines
}

// ---- 吸附 ----

export function snapPointToNearestRoad(
  map: maplibregl.Map,
  lngLat: LngLat,
  maxDistanceMeters = MAX_SNAP_DISTANCE_M,
): LngLat | null {
  const candidates = queryNearbyRoadLines(map, lngLat)
  if (candidates.length === 0) return null

  const pt = turfPoint(lngLat)
  let best: LngLat | null = null
  let bestDist = Infinity

  for (const coords of candidates) {
    if (coords.length < 2) continue
    try {
      const snapped = nearestPointOnLine(turfLineString(coords), pt, { units: 'meters' })
      const d = (snapped.properties?.dist as number | undefined) ?? Infinity
      if (d < bestDist) {
        bestDist = d
        best = snapped.geometry.coordinates as LngLat
      }
    } catch { /* 退化几何跳过 */ }
  }

  if (best && bestDist <= maxDistanceMeters && isFinite(best[0]) && isFinite(best[1])) return best
  return null
}

export function snapIntersectionToRealCrossing(
  map: maplibregl.Map,
  lngLat: LngLat,
  maxDistanceMeters = MAX_SNAP_DISTANCE_M,
): LngLat | null {
  const candidates = queryNearbyRoadLines(map, lngLat).filter((c) => c.length >= 2)
  if (candidates.length === 0) return null

  let best: LngLat | null = null
  let bestDist = Infinity

  for (let i = 0; i < candidates.length; i++) {
    for (let j = i + 1; j < candidates.length; j++) {
      try {
        const inter = lineIntersect(turfLineString(candidates[i]!), turfLineString(candidates[j]!))
        for (const feat of inter.features) {
          const coord = feat.geometry.coordinates as LngLat
          const d = distance(turfPoint(lngLat), turfPoint(coord), { units: 'meters' })
          if (d < bestDist) {
            bestDist = d
            best = coord
          }
        }
      } catch { /* 跳过 */ }
    }
  }

  if (best && bestDist <= maxDistanceMeters && isFinite(best[0]) && isFinite(best[1])) return best
  return snapPointToNearestRoad(map, lngLat, maxDistanceMeters)
}

export function snapPath(
  map: maplibregl.Map,
  path: LngLat[],
  maxDistanceMeters = MAX_SNAP_DISTANCE_M,
): LngLat[] {
  return path.map((pt) => snapPointToNearestRoad(map, pt, maxDistanceMeters) ?? pt)
}
