// ================================================================
// roadSelector.ts — 底图矢量道路选中与高亮
// 使用 map.getStyle().layers 动态发现底图道路图层，不依赖正则猜测
// ================================================================
import type maplibregl from 'maplibre-gl'

interface SelectedRoad {
  id: string
  layerId: string
  name?: string
  roadClass?: string
  coords: [number, number][]
}

type GJFeat = {
  type: 'Feature'
  properties: Record<string, unknown>
  geometry: { type: 'LineString'; coordinates: [number, number][] }
}

const OWN_LAYERS = new Set([
  'roads-layer', 'roads-heat', 'roads-selected',
  'road-select-highlight-layer', 'intersection-dots', 'intersection-signal-ring',
])

let _discoveredLayerIds: string[] | null = null

function discoverLineLayerIds(map: maplibregl.Map): string[] {
  if (_discoveredLayerIds !== null && _discoveredLayerIds.length > 0) return _discoveredLayerIds
  try {
    const style = map.getStyle()
    if (!style?.layers) return []
    _discoveredLayerIds = style.layers
      .filter((l) => l.type === 'line' && !OWN_LAYERS.has(l.id))
      .map((l) => l.id)
  } catch { return [] }
  return _discoveredLayerIds ?? []
}

function roadName(props: Record<string, unknown>): string | undefined {
  return (props.name as string) || (props.ref as string) || (props.class as string) || undefined
}

function roadClass(props: Record<string, unknown>): string {
  return (props.class as string) || (props.type as string) || (props.highway as string) || 'unknown'
}

let nextId = 1

export function createRoadSelector(map: maplibregl.Map): {
  pick: (point: { x: number; y: number }) => SelectedRoad | null
  boxPick: (bbox: [[number, number], [number, number]]) => SelectedRoad[]
  highlight: (roads: SelectedRoad[]) => void
  clear: () => void
  dispose: () => void
} {
  let highlightInited = false

  function ensureHighlightLayer(): void {
    if (highlightInited) return
    map.addSource('road-select-highlight', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } })
    map.addLayer({
      id: 'road-select-highlight-layer', type: 'line', source: 'road-select-highlight',
      paint: {
        'line-width': ['interpolate', ['linear'], ['zoom'], 10, 3, 14, 6, 18, 10],
        'line-color': '#FF4444',
        'line-opacity': 0.9,
      },
    } as never)
    highlightInited = true
  }

  function lineFeaturesAt(bbox: [maplibregl.PointLike, maplibregl.PointLike]): maplibregl.MapGeoJSONFeature[] {
    const layers = discoverLineLayerIds(map)
    if (layers.length === 0) return []
    return map.queryRenderedFeatures(bbox, { layers }) as maplibregl.MapGeoJSONFeature[]
  }

  function pick(point: { x: number; y: number }): SelectedRoad | null {
    const features = lineFeaturesAt([
      [point.x - 4, point.y - 4] as [number, number],
      [point.x + 4, point.y + 4] as [number, number],
    ])
    for (const f of features) {
      const geom = f.geometry as { type: string; coordinates?: unknown }
      if (geom.type !== 'LineString' || !Array.isArray(geom.coordinates)) continue
      return {
        id: `road-${nextId++}`,
        layerId: f.layer?.id ?? 'unknown',
        name: roadName(f.properties as Record<string, unknown> ?? {}),
        roadClass: roadClass(f.properties as Record<string, unknown> ?? {}),
        coords: geom.coordinates as [number, number][],
      }
    }
    return null
  }

  function boxPick(bbox: [[number, number], [number, number]]): SelectedRoad[] {
    const features = lineFeaturesAt([bbox[0] as [number, number], bbox[1] as [number, number]])
    const seen = new Set<string>()
    const result: SelectedRoad[] = []

    for (const f of features) {
      const geom = f.geometry as { type: string; coordinates?: unknown }
      if (geom.type !== 'LineString' || !Array.isArray(geom.coordinates)) continue
      const coordKey = JSON.stringify(geom.coordinates).slice(0, 80)
      if (seen.has(coordKey)) continue
      seen.add(coordKey)

      result.push({
        id: `road-${nextId++}`,
        layerId: f.layer?.id ?? 'unknown',
        name: roadName(f.properties as Record<string, unknown> ?? {}),
        roadClass: roadClass(f.properties as Record<string, unknown> ?? {}),
        coords: geom.coordinates as [number, number][],
      })
    }
    return result
  }

  function highlight(roads: SelectedRoad[]): void {
    ensureHighlightLayer()
    const features: GJFeat[] = roads.map((r) => ({
      type: 'Feature' as const,
      properties: { id: r.id, name: r.name, class: r.roadClass },
      geometry: { type: 'LineString' as const, coordinates: r.coords },
    }))
    const src = map.getSource('road-select-highlight') as maplibregl.GeoJSONSource
    src?.setData({ type: 'FeatureCollection', features } as never)
  }

  function clear(): void {
    const src = map.getSource('road-select-highlight') as maplibregl.GeoJSONSource | undefined
    src?.setData({ type: 'FeatureCollection', features: [] } as never)
  }

  function dispose(): void {
    try { map.removeLayer('road-select-highlight-layer') } catch { /* */ }
    try { map.removeSource('road-select-highlight') } catch { /* */ }
  }

  return { pick, boxPick, highlight, clear, dispose }
}
