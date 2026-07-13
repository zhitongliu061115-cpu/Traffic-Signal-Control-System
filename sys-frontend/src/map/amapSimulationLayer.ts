import type { SimRoadState, SimRoadnetResponse } from '@/types/traffic'

interface SimulationRoadEntry {
  id: string
  polyline: AMap.Polyline
}

export interface SimulationMapLayer {
  update: (roads: SimRoadState[]) => void
  fitView: () => void
  dispose: () => void
}

function roadColor(level: SimRoadState['level'] | undefined): string {
  if (level === 'jammed') return '#ff4d5a'
  if (level === 'slow') return '#f5a623'
  return '#22d3a0'
}

function validPath(path: [number, number][]): boolean {
  if (path.length < 2) return false
  const [firstLng, firstLat] = path[0]!
  return path.some(([lng, lat]) => Math.abs(lng - firstLng) > 1e-10 || Math.abs(lat - firstLat) > 1e-10)
}

export function createSimulationMapLayer(
  map: AMap.Map,
  roadnet: SimRoadnetResponse,
): SimulationMapLayer {
  const roadEntries = new Map<string, SimulationRoadEntry>()
  const overlays: AMap.Polyline[] = []

  for (const road of roadnet.roads) {
    const path = road.points
      .filter((point) => Number.isFinite(point.lng) && Number.isFinite(point.lat))
      .map((point) => [point.lng!, point.lat!] as [number, number])
    if (!validPath(path)) continue
    const polyline = new AMap.Polyline({
      path,
      strokeColor: roadColor(undefined),
      strokeWeight: Math.max(5, road.laneCount * 2.2),
      strokeOpacity: 0.86,
      lineJoin: 'round',
      lineCap: 'round',
      zIndex: 35,
    })
    polyline.setMap(map)
    roadEntries.set(road.id, { id: road.id, polyline })
    overlays.push(polyline)
  }

  return {
    update(roads: SimRoadState[]): void {
      for (const road of roads) {
        roadEntries.get(road.id)?.polyline.setOptions({ strokeColor: roadColor(road.level) })
      }
    },
    fitView(): void {
      if (overlays.length > 0) map.setFitView(overlays, false, [50, 50, 50, 50], 16)
    },
    dispose(): void {
      for (const overlay of overlays) overlay.setMap(null)
      overlays.length = 0
      roadEntries.clear()
    },
  }
}
