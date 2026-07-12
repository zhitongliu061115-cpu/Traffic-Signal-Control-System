// =========================================================
// amapVehicleLayer 鈥?楂樺痉 CircleMarker 杞﹁締鍥惧眰锛坧rogress 鏄犲皠锛?
// CityFlow 杞﹁締 (roadId, x, y) 鈫?閫愯矾 progress 鈫?涓婃捣寮洸 path 鈫?lng/lat
// 涓嶆敼 roadnet锛屼笉鏀瑰悗绔紝涓嶆敼 store
// =========================================================
import type { Road, Intersection, SimVehicleState, SimRoadnetResponse } from '@/types/traffic'

// ---- 甯搁噺 ----
const MARKER_RADIUS = 4.5
const NORMAL_FILL = '#2fd7ff'
const STOPPED_FILL = '#f5a623'
const EMERGENCY_FILL = '#ff2d4d'
const NORMAL_STROKE = '#0a3d5c'
const MAX_MARKERS = 120

/** 鍗曟潯璺殑鏄犲皠缂撳瓨 */
interface RoadMapping {
  shanghaiRoad: Road
  cityFlowPoints: Array<{ x: number; y: number }>
  totalLength: number
  /** CityFlow from→to 与上海 from→to 方向相反时需翻转 progress */
  flipped: boolean
}

export interface VehicleLayer {
  update: (vehicles: SimVehicleState[], evIds?: Set<string>) => void
  dispose: () => void
}

// ---- 宸ュ叿锛氫笂娴疯矾鍙?鈫?CityFlow 杞疆閿?----
function toCityFlowKey(it: Intersection): string {
  return `${it.col}_${it.row}`
}
function simEndpointKey(id: string): string | null {
  const m = id.match(/^intersection_(\d+)_(\d+)$/)
  return m ? `${m[1]}_${m[2]}` : null
}

// ---- 宸ュ叿锛氫袱鐐硅窛绂?/ 鎶曞奖 / polyline 闀垮害 ----
function dist2(a: { x: number; y: number }, b: { x: number; y: number }): number {
  const dx = a.x - b.x
  const dy = a.y - b.y
  return Math.sqrt(dx * dx + dy * dy)
}
function projectPoint(
  px: number, py: number,
  ax: number, ay: number,
  bx: number, by: number,
): { t: number; dist: number } {
  const dx = bx - ax
  const dy = by - ay
  const lenSq = dx * dx + dy * dy
  if (lenSq < 1e-9) return { t: 0, dist: Math.sqrt((px - ax) ** 2 + (py - ay) ** 2) }
  let t = ((px - ax) * dx + (py - ay) * dy) / lenSq
  t = Math.max(0, Math.min(1, t))
  const projX = ax + t * dx
  const projY = ay + t * dy
  return { t, dist: Math.sqrt((px - projX) ** 2 + (py - projY) ** 2) }
}
function polylineLength(pts: Array<{ x: number; y: number }>): number {
  let total = 0
  for (let i = 1; i < pts.length; i++) total += dist2(pts[i - 1]!, pts[i]!)
  return total
}

/** 鐐?(px,py) 鍦ㄦ姌绾夸笂鐨勮繘搴?0~1锛堟姇褰辨渶杩戠偣锛?*/
function progressOnPolyline(
  px: number, py: number,
  pts: Array<{ x: number; y: number }>,
  totalLen: number,
): number {
  if (pts.length < 2 || totalLen < 1e-6) return 0
  let bestT = 0
  let bestDist = Infinity
  let accum = 0
  for (let i = 1; i < pts.length; i++) {
    const a = pts[i - 1]!
    const b = pts[i]!
    const segLen = dist2(a, b)
    const proj = projectPoint(px, py, a.x, a.y, b.x, b.y)
    if (proj.dist < bestDist) {
      bestDist = proj.dist
      bestT = (accum + proj.t * segLen) / totalLen
    }
    accum += segLen
  }
  return Math.max(0, Math.min(1, bestT))
}

/** 娌?lng/lat 鎶樼嚎鎸?progress 鎻掑€?*/
function interpolateLngLat(
  path: [number, number][],
  progress: number,
): [number, number] {
  if (path.length < 2) return path[0] ?? [0, 0]
  let accum = 0
  const segLen: number[] = []
  for (let i = 1; i < path.length; i++) {
    const d = dist2(
      { x: path[i - 1]![0], y: path[i - 1]![1] },
      { x: path[i]![0], y: path[i]![1] },
    )
    segLen.push(d)
    accum += d
  }
  const total = accum || 1
  const target = progress * total
  let acc = 0
  for (let i = 0; i < segLen.length; i++) {
    const seg = segLen[i]!
    if (target <= acc + seg || i === segLen.length - 1) {
      const local = seg > 0 ? (target - acc) / seg : 0
      const t = Math.max(0, Math.min(1, local))
      const a = path[i]!
      const b = path[i + 1]!
      return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]
    }
    acc += seg
  }
  return path[path.length - 1]!
}

// =========================================================
export function createVehicleLayer(
  map: AMap.Map,
  simRoadnet: SimRoadnetResponse,
  shanghaiRoads: Road[],
  shanghaiIntersections: Intersection[],
): VehicleLayer {
  // ---- 鏋勫缓 CityFlow roadId 鈫?RoadMapping 绱㈠紩锛堜竴娆★級 ----
  const roadMapping = new Map<string, RoadMapping>()

  // 涓婃捣璺彛 ID 鈫?杞疆閿?
  const shIdToKey = new Map<string, string>()
  for (const it of shanghaiIntersections) {
    shIdToKey.set(it.id, toCityFlowKey(it))
  }
  // 鏃犲簭绔偣瀵?鈫?涓婃捣 Road
  const pairToRoad = new Map<string, Road>()
  for (const r of shanghaiRoads) {
    const a = shIdToKey.get(r.from)
    const b = shIdToKey.get(r.to)
    if (a && b) pairToRoad.set([a, b].sort().join('|'), r)
  }
  // 閬嶅巻 simRoadnet 鏋勫缓鏄犲皠
  for (const sr of simRoadnet.roads) {
    const a = simEndpointKey(sr.from)
    const b = simEndpointKey(sr.to)
    if (!a || !b) continue
    const shRoad = pairToRoad.get([a, b].sort().join('|'))
    if (!shRoad || !shRoad.path || shRoad.path.length < 2) continue
    // 判断方向：CityFlow from→to 是否与上海 from→to 一致（用转置键比较）
    const shA = shIdToKey.get(shRoad.from) // 上海 from 的转置键
    const flipped = (a === shA) ? false : true // a 对应 from 则同向，否则反向
    const cfPts = sr.points && sr.points.length >= 2 ? sr.points : [{ x: 0, y: 0 }, { x: 0, y: 0 }]
    roadMapping.set(sr.id, {
      shanghaiRoad: shRoad,
      cityFlowPoints: cfPts,
      flipped,
      totalLength: Math.max(1, polylineLength(cfPts)),
      flipped,
    })
  }

  // ---- CircleMarker 瀵硅薄姹?----
  const pool: AMap.CircleMarker[] = []
  function ensurePool(n: number): void {
    while (pool.length < n && pool.length < MAX_MARKERS) {
      const cm = new AMap.CircleMarker({
        center: [0, 0] as unknown as AMap.LngLat,
        radius: MARKER_RADIUS,
        fillColor: NORMAL_FILL,
        fillOpacity: 0.88,
        strokeColor: NORMAL_STROKE,
        strokeWeight: 1,
        zIndex: 80,
        bubble: true,
      })
      cm.setMap(map)
      cm.hide()
      pool.push(cm)
    }
  }

  // ---- 鍏紑鏂规硶 ----
  return {
    update(vehicles: SimVehicleState[], evIds?: Set<string>): void {
      ensurePool(Math.min(vehicles.length, MAX_MARKERS))
      let vi = 0

      for (const v of vehicles) {
        const mapping = roadMapping.get(v.roadId)
        if (!mapping) continue
        // 计算 CityFlow 直路上的 progress
        let prog = progressOnPolyline(v.x, v.y, mapping.cityFlowPoints, mapping.totalLength)
        // 如果 CityFlow 道路与上海道路方向相反，翻转 progress
        if (mapping.flipped) prog = 1 - prog
        // 映射到上海弯曲路径
        // 璁＄畻 CityFlow 鐩磋矾涓婄殑 progress
        let prog = progressOnPolyline(v.x, v.y, mapping.cityFlowPoints, mapping.totalLength)
        // 濡傛灉 CityFlow 閬撹矾涓庝笂娴烽亾璺柟鍚戠浉鍙嶏紝缈昏浆 progress
        if (mapping.flipped) prog = 1 - prog
        // 鏄犲皠鍒颁笂娴峰集鏇茶矾寰?
        const [lng, lat] = interpolateLngLat(mapping.shanghaiRoad.path!, prog)
        if (vi < pool.length) {
          pool[vi]!.setCenter([lng, lat] as unknown as AMap.LngLat)
          pool[vi]!.setOptions({
            fillColor: evIds?.has(v.id) ? EMERGENCY_FILL : (v.speed < 0.5 ? STOPPED_FILL : NORMAL_FILL),
          })
          pool[vi]!.show()
          vi++
        }
      }

      // 闅愯棌鍓╀綑 markers
      for (let i = vi; i < pool.length; i++) pool[i]!.hide()
    },

    dispose(): void {
      for (const m of pool) m.setMap(null)
      pool.length = 0
    },
  }
}

