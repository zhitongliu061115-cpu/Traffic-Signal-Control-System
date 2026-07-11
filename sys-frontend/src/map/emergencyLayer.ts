// ================================================================
// emergencyLayer.ts — 应急路线高亮图层
// 当 systemMode === 'emergency' 时激活，蓝色/青色加粗路段
// ================================================================
import type maplibregl from 'maplibre-gl'
import type { Intersection, Road } from '@/types/traffic'

/**
 * 添加应急路线高亮图层（加粗、青色、发光），初始隐藏。
 * activate(route, roads, intersections) 激活；deactivate() 隐藏。
 */
export function createEmergencyLayer(map: maplibregl.Map) {
  map.addSource('emergency-source', {
    type: 'geojson',
    data: { type: 'FeatureCollection', features: [] },
  })

  map.addLayer({
    id: 'emergency-layer',
    type: 'line',
    source: 'emergency-source',
    layout: { 'line-join': 'round', 'line-cap': 'round' },
    paint: {
      'line-width': ['interpolate', ['linear'], ['zoom'], 10, 4, 13, 6, 16, 12],
      'line-color': '#00E5FF',
      'line-opacity': 0.9,
      'line-blur': 0,
    },
  })

  return {
    activate(route: string[], allRoads: Road[], allInter: Intersection[]) {
      const features: { type: 'Feature'; properties: Record<string, unknown>; geometry: { type: 'LineString'; coordinates: [number, number][] } }[] = []
      for (let i = 0; i < route.length - 1; i++) {
        const road = allRoads.find(
          (r) => (r.from === route[i] && r.to === route[i + 1]) || (r.from === route[i + 1] && r.to === route[i]),
        )
        if (!road) continue
        // 优先使用 path 中心线，降级为端点直线
        const coords: [number, number][] =
          road.path && road.path.length >= 2
            ? road.path
            : (() => {
                const from = allInter.find((x) => x.id === route[i])
                const to = allInter.find((x) => x.id === route[i + 1])
                if (!from || !to) return []
                return [[from.lng, from.lat], [to.lng, to.lat]] as [number, number][]
              })()
        if (coords.length < 2) continue
        features.push({
          type: 'Feature',
          properties: { id: road.id },
          geometry: { type: 'LineString', coordinates: coords },
        })
      }
      const src = map.getSource('emergency-source') as maplibregl.GeoJSONSource | undefined
      src?.setData({ type: 'FeatureCollection', features })
    },
    deactivate() {
      const src = map.getSource('emergency-source') as maplibregl.GeoJSONSource | undefined
      src?.setData({ type: 'FeatureCollection', features: [] })
    },
  }
}
