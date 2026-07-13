// ================================================================
// amapRoads.ts — 高德 Polyline 道路热力线
// 每条 mock 道路对应一条 Polyline，颜色由 congestionIndex 决定
// ================================================================
import type { Intersection, Road } from '@/types/traffic'

// 高德风格拥堵配色
const AMAP_GREEN = '#5ebf49'
const AMAP_AMBER = '#f2b23d'
const AMAP_RED   = '#e65c4c'
const AMAP_DARK  = '#cc0000'

function ciColor(ci: number): string {
  if (ci <= 25) return '#5ebf49'
  if (ci <= 50) return AMAP_AMBER
  if (ci <= 70) return AMAP_RED
  return AMAP_DARK
}

interface RoadEntry {
  id: string
  polyline: AMap.Polyline
  pathKey: string
}

function pathKey(path: [number, number][]): string {
  const first = path[0]
  const last = path[path.length - 1]
  return `${path.length}:${first?.[0]},${first?.[1]}:${last?.[0]},${last?.[1]}`
}

/** 地图点击道路时回调 road id */
export type RoadClickCallback = (roadId: string | null, lngLat: [number, number]) => void

export function addAMapRoadLayer(
  map: AMap.Map,
  intersections: Intersection[],
  roads: Road[],
  onRoadClick?: RoadClickCallback,
): {
  update: (its: Intersection[], rds: Road[]) => void
  setPaths: (pathMap: Map<string, [number, number][]>) => void
  dispose: () => void
} {
  const entries: RoadEntry[] = []
  const lookup = new Map<string, RoadEntry>()
  /** 缓存上次颜色，避免 congestionIndex 微量变化触发无意义 setOptions */
  const lastColor = new Map<string, string>()

  for (const r of roads) {
    const from = intersections.find((i) => i.id === r.from)
    const to = intersections.find((i) => i.id === r.to)
    if (!from || !to) continue

    const path: [number, number][] = r.path?.length >= 2
      ? r.path
      : [[from.lng, from.lat], [to.lng, to.lat]]

    const poly = new AMap.Polyline({
      path,
      strokeColor: ciColor(r.congestionIndex),
      strokeWeight: 6,
      strokeOpacity: 0.8,
      lineJoin: 'round',
      lineCap: 'round',
      zIndex: 10,
    })
    poly.setMap(map)

    // 点击道路 → 回调
    poly.on('click', (e: any) => {
      if (onRoadClick) {
        onRoadClick(r.id, [e.lnglat.lng, e.lnglat.lat])
      }
    })

    const entry: RoadEntry = { id: r.id, polyline: poly, pathKey: pathKey(path) }
    entries.push(entry)
    lookup.set(r.id, entry)
    lastColor.set(r.id, ciColor(r.congestionIndex))
  }

  return {
    update(its: Intersection[], rds: Road[]): void {
      for (const r of rds) {
        const e = lookup.get(r.id)
        if (!e) continue
        const from = its.find((i) => i.id === r.from)
        const to = its.find((i) => i.id === r.to)
        if (!from || !to) continue
        const path: [number, number][] = r.path?.length >= 2
          ? r.path
          : [[from.lng, from.lat], [to.lng, to.lat]]
        const nextPathKey = pathKey(path)
        if (e.pathKey !== nextPathKey) {
          e.pathKey = nextPathKey
          e.polyline.setPath(path)
        }
        // 脏检查：只在颜色实际变化时才调 setOptions
        const newColor = ciColor(r.congestionIndex)
        if (lastColor.get(r.id) !== newColor) {
          lastColor.set(r.id, newColor)
          e.polyline.setOptions({ strokeColor: newColor })
        }
      }
    },
    /** 用真实道路 path 替换手写 mock path */
    setPaths(pathMap: Map<string, [number, number][]>): void {
      entries.forEach((e) => {
        const real = pathMap.get(e.id)
        if (real && real.length >= 2) {
          e.pathKey = pathKey(real)
          e.polyline.setPath(real)
        }
      })
    },
    dispose(): void {
      entries.forEach((e) => e.polyline.setMap(null))
    },
  }
}
