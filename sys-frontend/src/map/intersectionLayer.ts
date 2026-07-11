// ================================================================
// intersectionLayer.ts — MapLibre 原生 circle layer 画路口设备状态点
// 替代 Three.js IntersectionNodeManager，全 zoom 可见
// ================================================================
import type maplibregl from 'maplibre-gl'
import type { Intersection } from '@/types/traffic'
import { signalStatus, signalColorHex } from './signalDerive'

type GJPointFeat = {
  type: 'Feature'
  properties: Record<string, unknown>
  geometry: { type: 'Point'; coordinates: [number, number] }
}

function buildGeoJSON(intersections: Intersection[]) {
  const features: GJPointFeat[] = []
  for (const it of intersections) {
    features.push({
      type: 'Feature',
      properties: {
        id: it.id,
        name: it.name,
        deviceStatus: it.deviceStatus,
        signalColor: signalColorHex(signalStatus(it)),
      },
      geometry: { type: 'Point', coordinates: [it.lng, it.lat] },
    })
  }
  return { type: 'FeatureCollection' as const, features }
}

/**
 * 添加路口设备状态点层（全 zoom 可见，circle + symbol 两层）
 * 返回 { update, setVisibility } 供 LOD 控制
 */
export function addIntersectionLayer(
  map: maplibregl.Map,
  intersections: Intersection[],
  onSelect: (id: string) => void,
): { update: (its: Intersection[]) => void; dispose: () => void } {
  map.addSource('intersection-source', {
    type: 'geojson',
    data: buildGeoJSON(intersections) as never,
  })

  // 设备状态圆圈
  map.addLayer({
    id: 'intersection-dots',
    type: 'circle',
    source: 'intersection-source',
    paint: {
      'circle-radius': ['interpolate', ['linear'], ['zoom'], 10, 3, 13, 6, 16, 10],
      'circle-color': [
        'match',
        ['get', 'deviceStatus'],
        'online', '#22D3A0',
        'fault', '#FF4D6D',
        '#5A7595',
      ],
      'circle-stroke-width': 1.5,
      'circle-stroke-color': 'rgba(4,21,39,0.8)',
      'circle-opacity': 0.9,
    },
  } as never)

  // 信号灯色外环
  map.addLayer({
    id: 'intersection-signal-ring',
    type: 'circle',
    source: 'intersection-source',
    paint: {
      'circle-radius': ['interpolate', ['linear'], ['zoom'], 10, 4, 13, 8, 16, 13],
      'circle-color': 'transparent',
      'circle-stroke-width': 2.5,
      'circle-stroke-color': ['get', 'signalColor'],
      'circle-opacity': 0.85,
    },
  } as never)

  // 点击处理
  map.on('click', 'intersection-dots', (e) => {
    if (e.features && e.features[0]) {
      const id = e.features[0].properties?.id as string | undefined
      if (id) onSelect(id)
    }
  })
  map.on('mouseenter', 'intersection-dots', () => { map.getCanvas().style.cursor = 'pointer' })
  map.on('mouseleave', 'intersection-dots', () => { map.getCanvas().style.cursor = '' })

  return {
    update(its: Intersection[]) {
      const src = map.getSource('intersection-source') as maplibregl.GeoJSONSource | undefined
      if (src) src.setData(buildGeoJSON(its) as never)
    },
    dispose() {
      try { map.removeLayer('intersection-signal-ring') } catch { /* */ }
      try { map.removeLayer('intersection-dots') } catch { /* */ }
      try { map.removeSource('intersection-source') } catch { /* */ }
    },
  }
}
