// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { addAMapRoadLayer } from '../amapRoads'
import { createTLMarkers } from '../amapMarkers'
import { createVehicleLayer } from '../amapVehicleLayer'
import type { Intersection, Road, SimRoadnetResponse, SimVehicleState } from '@/types/traffic'

const polylines: MockPolyline[] = []
const markers: MockMarker[] = []
const circleMarkers: MockCircleMarker[] = []

class MockPolyline {
  setPath = vi.fn()
  setOptions = vi.fn()
  setMap = vi.fn()
  on = vi.fn()
  hide = vi.fn()
  show = vi.fn()

  constructor(public options: unknown) {
    polylines.push(this)
  }
}

class MockMarker {
  setContent = vi.fn()
  setMap = vi.fn()
  on = vi.fn()

  constructor(public options: unknown) {
    markers.push(this)
  }
}

class MockCircleMarker {
  setCenter = vi.fn()
  setOptions = vi.fn()
  setMap = vi.fn()
  hide = vi.fn()
  show = vi.fn()

  constructor(public options: unknown) {
    circleMarkers.push(this)
  }
}

const intersectionA = {
  id: 'A', col: 1, row: 1, lng: 121, lat: 31,
  currentPhase: 'eastwest_straight', deviceStatus: 'online', greenRemain: 10,
} as Intersection
const intersectionB = {
  id: 'B', col: 2, row: 1, lng: 122, lat: 31,
  currentPhase: 'northsouth_straight', deviceStatus: 'online', greenRemain: 10,
} as Intersection
const road = {
  id: 'road-A-B', from: 'A', to: 'B', congestionIndex: 20,
  path: [[121, 31], [122, 31]],
} as Road

beforeEach(() => {
  polylines.length = 0
  markers.length = 0
  circleMarkers.length = 0
  vi.stubGlobal('AMap', {
    Polyline: MockPolyline,
    Marker: MockMarker,
    CircleMarker: MockCircleMarker,
    Pixel: class MockPixel {},
  })
})

describe('AMap layer update cost', () => {
  it('updates a road only when its path or color bucket changes', () => {
    const layer = addAMapRoadLayer({} as AMap.Map, [intersectionA, intersectionB], [road])
    const polyline = polylines[0]!

    layer.update([intersectionA, intersectionB], [road])
    expect(polyline.setPath).not.toHaveBeenCalled()
    expect(polyline.setOptions).not.toHaveBeenCalled()

    layer.update([intersectionA, intersectionB], [{ ...road, congestionIndex: 60 }])
    expect(polyline.setOptions).toHaveBeenCalledTimes(1)

    layer.update([intersectionA, intersectionB], [{ ...road, path: [[121, 31], [121.5, 31], [122, 31]] }])
    expect(polyline.setPath).toHaveBeenCalledTimes(1)
  })

  it('rebuilds a traffic-light marker only when its icon state changes', () => {
    const layer = createTLMarkers({} as AMap.Map, [intersectionA], vi.fn())
    const marker = markers[0]!

    layer.updateAll([{ ...intersectionA, greenRemain: 9 }])
    expect(marker.setContent).not.toHaveBeenCalled()

    layer.updateAll([{ ...intersectionA, currentPhase: 'northsouth_straight' }])
    expect(marker.setContent).toHaveBeenCalledTimes(1)
    layer.dispose()
  })

  it('does not repeat marker visibility and style calls for unchanged vehicles', () => {
    const roadnet: SimRoadnetResponse = {
      sceneId: 'test',
      intersections: [],
      roads: [{
        id: 'sim-road', from: 'intersection_1_1', to: 'intersection_2_1',
        points: [{ x: 0, y: 0 }, { x: 100, y: 0 }], laneCount: 1,
      }],
      roadLinks: [], phases: [],
    }
    const vehicle: SimVehicleState = {
      id: 'vehicle-1', roadId: 'sim-road', lane: 0,
      x: 50, y: 0, angle: 0, speed: 8,
    }
    const layer = createVehicleLayer(
      {} as AMap.Map,
      roadnet,
      [road],
      [intersectionA, intersectionB],
    )

    layer.update([vehicle])
    layer.update([vehicle])
    const marker = circleMarkers[0]!
    expect(marker.show).toHaveBeenCalledTimes(1)
    expect(marker.setOptions).not.toHaveBeenCalled()

    layer.update([{ ...vehicle, speed: 0 }])
    expect(marker.setOptions).toHaveBeenCalledTimes(1)
  })
})
