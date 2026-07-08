// ================================================================
// roadLayer.ts — 主干道路 GeoJSON 线层（LOD1/LOD2 使用）
// ================================================================
import type maplibregl from 'maplibre-gl'
import type { Intersection, Road } from '@/types/traffic'
import { congestionColorHex } from '@/three/config'

/** GeoJSON 行类型（避免依赖 @types/geojson） */
type GJLineFeat = {
  type: 'Feature'
  properties: Record<string, unknown>
  geometry: { type: 'LineString'; coordinates: [number, number][] }
}
type GJLineFC = { type: 'FeatureCollection'; features: GJLineFeat[] }

export function buildRoadsGeoJSON(
  intersections: Intersection[],
  roads: Road[],
): GJLineFC {
  const features: GJLineFeat[] = []
  for (const r of roads) {
    // 优先用 path 字段（真实中心线），否则降级为 from→to 直线
    const coords: [number, number][] =
      r.path && r.path.length >= 2
        ? r.path
        : (() => {
            const from = intersections.find((i) => i.id === r.from)
            const to = intersections.find((i) => i.id === r.to)
            if (!from || !to) return []
            return [[from.lng, from.lat], [to.lng, to.lat]] as [number, number][]
          })()
    if (coords.length < 2) continue
    features.push({
      type: 'Feature',
      properties: { id: r.id, name: r.name, congestionIndex: r.congestionIndex },
      geometry: { type: 'LineString', coordinates: coords },
    })
  }
  return { type: 'FeatureCollection', features }
}

/** 添加道路线层，返回 { update } 供外部调用 */
export function addRoadLayer(
  map: maplibregl.Map,
  intersections: Intersection[],
  roads: Road[],
): { update: (its: Intersection[], rds: Road[]) => void } {
  const geojson = buildRoadsGeoJSON(intersections, roads)

  map.addSource('roads-source', { type: 'geojson', data: geojson as never })

  map.addLayer({
    id: 'roads-layer',
    type: 'line',
    source: 'roads-source',
    layout: { 'line-join': 'round', 'line-cap': 'round' } as never,
    paint: {
      'line-width': ['interpolate', ['linear'], ['zoom'], 10, 2, 13, 4, 16, 8],
      'line-color': [
        'case',
        ['<=', ['get', 'congestionIndex'], 30], congestionColorHex(15),
        ['<=', ['get', 'congestionIndex'], 60], congestionColorHex(45),
        ['<=', ['get', 'congestionIndex'], 80], congestionColorHex(70),
        congestionColorHex(90),
      ],
      'line-opacity': 0.85,
      'line-blur': 1.5,
    } as never,
  })

  return {
    update(its: Intersection[], rds: Road[]): void {
      const src = map.getSource('roads-source') as maplibregl.GeoJSONSource | undefined
      if (src) src.setData(buildRoadsGeoJSON(its, rds) as never)
    },
  }
}
