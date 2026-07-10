// ================================================================
// roadLayer.ts — 道路线层 + 热力采样点
// 高德地图风格配色，线层表达方向，circle 采样点增强热力覆盖
// ================================================================
import type maplibregl from 'maplibre-gl'
import type { Intersection, Road } from '@/types/traffic'

// 高德地图风格拥堵配色
const AMAP_GREEN  = '#5ebf49'
const AMAP_AMBER  = '#f2b23d'
const AMAP_RED    = '#e65c4c'
const AMAP_DARK   = '#cc0000'

function amapColor(ci: number): string {
  if (ci <= 30) return AMAP_GREEN
  if (ci <= 60) return AMAP_AMBER
  if (ci <= 80) return AMAP_RED
  return AMAP_DARK
}

type GJLineFeat = {
  type: 'Feature'
  properties: Record<string, unknown>
  geometry: { type: 'LineString'; coordinates: [number, number][] }
}
type GJPointFeat = {
  type: 'Feature'
  properties: Record<string, unknown>
  geometry: { type: 'Point'; coordinates: [number, number] }
}
type GJLineFC = { type: 'FeatureCollection'; features: GJLineFeat[] }
type GJPointFC = { type: 'FeatureCollection'; features: GJPointFeat[] }

function buildLines(roads: Road[], intersections: Intersection[]): GJLineFC {
  const features: GJLineFeat[] = []
  for (const r of roads) {
    const coords: [number, number][] =
      r.path?.length >= 2 ? r.path
      : (() => {
          const from = intersections.find((i) => i.id === r.from)
          const to = intersections.find((i) => i.id === r.to)
          return from && to ? [[from.lng, from.lat], [to.lng, to.lat]] as [number, number][] : []
        })()
    if (coords.length < 2) continue
    features.push({
      type: 'Feature', geometry: { type: 'LineString', coordinates: coords },
      properties: { id: r.id, name: r.name, ci: r.congestionIndex },
    })
  }
  return { type: 'FeatureCollection', features }
}

const SAMPLE_INTERVAL = 4 // 每 4 个 path 点采 1 个

function buildHeatPoints(roads: Road[]): GJPointFC {
  const features: GJPointFeat[] = []
  for (const r of roads) {
    const path = r.path
    if (!path || path.length === 0) continue
    for (let i = 0; i < path.length; i += SAMPLE_INTERVAL) {
      features.push({
        type: 'Feature', geometry: { type: 'Point', coordinates: path[i]! },
        properties: { roadId: r.id, ci: r.congestionIndex },
      })
    }
  }
  return { type: 'FeatureCollection', features }
}

export function addRoadLayer(
  map: maplibregl.Map,
  intersections: Intersection[],
  roads: Road[],
): {
  update: (its: Intersection[], rds: Road[]) => void
  dispose: () => void
} {
  map.addSource('roads-source', { type: 'geojson', data: buildLines(roads, intersections) as never })
  map.addSource('heat-source', { type: 'geojson', data: buildHeatPoints(roads) as never })

  // ---- 道路线层（半透明朦胧感，高德风格） ----
  map.addLayer({
    id: 'roads-layer', type: 'line', source: 'roads-source',
    layout: { 'line-join': 'round', 'line-cap': 'round' } as never,
    paint: {
      'line-width': ['interpolate', ['linear'], ['zoom'], 10, 3, 13, 7, 16, 12],
      'line-color': [
        'case',
        ['<=', ['get', 'ci'], 30], AMAP_GREEN,
        ['<=', ['get', 'ci'], 60], AMAP_AMBER,
        ['<=', ['get', 'ci'], 80], AMAP_RED,
        AMAP_DARK,
      ],
      'line-opacity': 0.65,
      'line-blur': ['interpolate', ['linear'], ['zoom'], 10, 2.0, 16, 0.5],
    } as never,
  })

  // ---- 采样点热力层（弱叠加，增强覆盖感） ----
  map.addLayer({
    id: 'roads-heat', type: 'circle', source: 'heat-source',
    paint: {
      'circle-radius': ['interpolate', ['linear'], ['zoom'], 10, 4, 13, 7, 16, 12],
      'circle-color': [
        'case',
        ['<=', ['get', 'ci'], 30], AMAP_GREEN,
        ['<=', ['get', 'ci'], 60], AMAP_AMBER,
        ['<=', ['get', 'ci'], 80], AMAP_RED,
        AMAP_DARK,
      ],
      'circle-opacity': 0.35,
      'circle-blur': 1.2,
      'circle-stroke-width': 0,
    } as never,
  })

  return {
    update(its: Intersection[], rds: Road[]): void {
      const lineSrc = map.getSource('roads-source') as maplibregl.GeoJSONSource
      const heatSrc = map.getSource('heat-source') as maplibregl.GeoJSONSource
      if (lineSrc) lineSrc.setData(buildLines(rds, its) as never)
      if (heatSrc) heatSrc.setData(buildHeatPoints(rds) as never)
    },
    dispose(): void {
      try { map.removeLayer('roads-heat') } catch { /* */ }
      try { map.removeLayer('roads-layer') } catch { /* */ }
      try { map.removeSource('heat-source') } catch { /* */ }
      try { map.removeSource('roads-source') } catch { /* */ }
    },
  }
}
