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
  if (ci <= 30) return AMAP_GREEN
  if (ci <= 60) return AMAP_AMBER
  if (ci <= 80) return AMAP_RED
  return AMAP_DARK
}

interface RoadEntry {
  id: string
  polyline: AMap.Polyline
}

/** 地图点击道路时回调 road id */
export type RoadClickCallback = (roadId: string | null, lngLat: [number, number]) => void

export function addAMapRoadLayer(
  map: AMap.Map,
  intersections: Intersection[],
  roads: Road[],
  onRoadClick?: RoadClickCallback,
): { update: (its: Intersection[], rds: Road[]) => void; dispose: () => void } {
  const entries: RoadEntry[] = []
  const lookup = new Map<string, RoadEntry>()

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

    const entry: RoadEntry = { id: r.id, polyline: poly }
    entries.push(entry)
    lookup.set(r.id, entry)
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
        e.polyline.setPath(path)
        e.polyline.setOptions({ strokeColor: ciColor(r.congestionIndex) })
      }
    },
    dispose(): void {
      entries.forEach((e) => e.polyline.setMap(null))
    },
  }
}
