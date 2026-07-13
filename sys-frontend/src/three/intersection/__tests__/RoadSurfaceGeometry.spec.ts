import { describe, expect, it } from 'vitest'
import type { SimRoadnetResponse } from '@/types/traffic'
import { createLocalLaneLinkPaths, createLocalRoadSurfaceSegments } from '../RoadSurfaceGeometry'

const lanes = [
  { index: 0, width: 4 },
  { index: 1, width: 4 },
  { index: 2, width: 4 },
]

const roadnet: SimRoadnetResponse = {
  sceneId: 'test-scene',
  intersections: [
    { id: 'center', x: 100, y: 200, virtual: false },
    { id: 'east', x: 300, y: 200, virtual: false },
  ],
  roads: [
    { id: 'eastbound', from: 'center', to: 'east', points: [{ x: 100, y: 200 }, { x: 300, y: 200 }], laneCount: 3, lanes },
    { id: 'westbound', from: 'east', to: 'center', points: [{ x: 300, y: 200 }, { x: 100, y: 200 }], laneCount: 3, lanes },
    { id: 'degenerate', from: 'center', to: 'east', points: [{ x: 100, y: 200 }, { x: 100, y: 200 }], laneCount: 1 },
  ],
  roadLinks: [{
    intersectionId: 'center',
    index: 0,
    fromRoadId: 'westbound',
    toRoadId: 'eastbound',
    type: 'go_straight',
    laneLinks: [{
      id: 'westbound_1_TO_eastbound_1',
      startLaneIndex: 1,
      endLaneIndex: 1,
      points: [{ x: 90, y: 194 }, { x: 100, y: 194 }, { x: 110, y: 194 }],
    }],
  }],
  phases: [],
}

describe('roadnet surface geometry', () => {
  it('places opposite directed roads on opposite sides of the shared centerline', () => {
    const segments = createLocalRoadSurfaceSegments(roadnet, 'center', 120)
    const eastbound = segments.find((segment) => segment.roadId === 'eastbound')
    const westbound = segments.find((segment) => segment.roadId === 'westbound')

    expect(eastbound?.width).toBe(12)
    expect(eastbound?.center.z).toBeCloseTo(6)
    expect(eastbound?.length).toBeCloseTo(120)
    expect(eastbound?.rotationY).toBeCloseTo(Math.PI / 2)
    expect(westbound?.center.z).toBeCloseTo(-6)
    expect(westbound?.rotationY).toBeCloseTo(-Math.PI / 2)
  })

  it('maps laneLink points directly from CityFlow coordinates', () => {
    const [path] = createLocalLaneLinkPaths(roadnet, 'center')

    expect(path?.points.map((point) => point.toArray())).toEqual([
      [-10, 0.4, 6],
      [0, 0.4, 6],
      [10, 0.4, 6],
    ])
  })

  it('skips absent intersections and degenerate road segments safely', () => {
    expect(createLocalRoadSurfaceSegments(roadnet, 'missing')).toEqual([])
    expect(createLocalRoadSurfaceSegments(roadnet, 'center').some((segment) => segment.roadId === 'degenerate')).toBe(false)
  })
})
