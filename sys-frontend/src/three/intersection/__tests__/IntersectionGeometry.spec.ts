import { describe, expect, it } from 'vitest'
import type { SimRoadnetResponse } from '@/types/traffic'
import { createLocalRoadCenterlines, getConnectedRoads } from '../IntersectionGeometry'

const roadnet: SimRoadnetResponse = {
  sceneId: 'test-scene',
  intersections: [
    { id: 'center', x: 100, y: 200, virtual: false },
    { id: 'east', x: 200, y: 200, virtual: false },
    { id: 'north', x: 100, y: 300, virtual: false },
    { id: 'other-a', x: 500, y: 500, virtual: false },
    { id: 'other-b', x: 600, y: 500, virtual: false },
  ],
  roads: [
    { id: 'center-east', from: 'center', to: 'east', points: [{ x: 100, y: 200 }, { x: 200, y: 200 }], laneCount: 3 },
    { id: 'north-center', from: 'north', to: 'center', points: [{ x: 100, y: 300 }, { x: 100, y: 200 }], laneCount: 2 },
    { id: 'unrelated', from: 'other-a', to: 'other-b', points: [{ x: 500, y: 500 }, { x: 600, y: 500 }], laneCount: 1 },
  ],
  roadLinks: [],
  phases: [],
}

describe('intersection road geometry', () => {
  it('returns only roads connected to the selected intersection', () => {
    expect(getConnectedRoads(roadnet, 'center').map((road) => road.id)).toEqual([
      'center-east',
      'north-center',
    ])
  })

  it('converts connected road points into intersection-local Three.js coordinates', () => {
    const centerlines = createLocalRoadCenterlines(roadnet, 'center')

    expect(centerlines).toHaveLength(2)
    expect(centerlines[0]?.points.map((point) => point.toArray())).toEqual([
      [0, 1.5, 0],
      [100, 1.5, 0],
    ])
    expect(centerlines[1]?.points.map((point) => point.toArray())).toEqual([
      [0, 1.5, -100],
      [0, 1.5, 0],
    ])
  })

  it('returns no geometry when the intersection is absent', () => {
    expect(createLocalRoadCenterlines(roadnet, 'missing')).toEqual([])
  })
})
