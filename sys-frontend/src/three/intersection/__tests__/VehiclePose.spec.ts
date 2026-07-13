import { describe, expect, it } from 'vitest'
import type { SimRoadnetResponse, SimVehicleState } from '@/types/traffic'
import { createLocalVehiclePoses, samplePolylineAtDistance } from '../VehiclePose'

const roadnet: SimRoadnetResponse = {
  sceneId: 'test-scene',
  intersections: [
    { id: 'center', x: 100, y: 200, virtual: false },
    { id: 'east', x: 300, y: 200, virtual: false },
    { id: 'north', x: 100, y: 400, virtual: false },
    { id: 'other', x: 500, y: 500, virtual: false },
  ],
  roads: [
    { id: 'eastbound', from: 'center', to: 'east', points: [{ x: 100, y: 200 }, { x: 300, y: 200 }], laneCount: 3 },
    { id: 'northbound', from: 'center', to: 'north', points: [{ x: 100, y: 200 }, { x: 100, y: 400 }], laneCount: 3 },
    { id: 'unrelated', from: 'north', to: 'other', points: [{ x: 100, y: 400 }, { x: 500, y: 500 }], laneCount: 1 },
  ],
  roadLinks: [{
    intersectionId: 'center',
    index: 0,
    fromRoadId: 'eastbound',
    toRoadId: 'northbound',
    type: 'turn_left',
    laneLinks: [{
      id: 'eastbound_1_TO_northbound_0',
      startLaneIndex: 1,
      endLaneIndex: 0,
      points: [{ x: 120, y: 200 }, { x: 130, y: 200 }, { x: 130, y: 210 }],
    }],
  }],
  phases: [],
}

function vehicle(overrides: Partial<SimVehicleState>): SimVehicleState {
  return {
    id: 'vehicle-1',
    roadId: 'eastbound',
    lane: 1,
    x: 120,
    y: 200,
    angle: 0,
    speed: 8,
    ...overrides,
  }
}

describe('createLocalVehiclePoses', () => {
  it('maps an eastbound vehicle and applies the directed-road center offset', () => {
    const [pose] = createLocalVehiclePoses([vehicle({})], roadnet, 'center')

    expect(pose?.position.toArray()).toEqual([20, 0.8, 11])
    expect(pose?.rotationY).toBeCloseTo(Math.PI / 2)
  })

  it('uses real roadnet lane widths when available', () => {
    const withLaneWidths: SimRoadnetResponse = {
      ...roadnet,
      roads: roadnet.roads.map((road) => road.id === 'eastbound'
        ? { ...road, lanes: [
          { index: 0, width: 4 },
          { index: 1, width: 4 },
          { index: 2, width: 4 },
        ] }
        : road),
    }
    const poses = createLocalVehiclePoses([
      vehicle({ id: 'lane-0', lane: 0 }),
      vehicle({ id: 'lane-2', lane: 2 }),
    ], withLaneWidths, 'center')

    expect(poses.map((pose) => pose.position.z)).toEqual([2, 10])
  })

  it('separates lanes laterally with compatibility dimensions', () => {
    const poses = createLocalVehiclePoses([
      vehicle({ id: 'lane-0', lane: 0 }),
      vehicle({ id: 'lane-2', lane: 2 }),
    ], roadnet, 'center')

    expect(poses.map((pose) => pose.position.z)).toEqual([5, 17])
  })

  it('uses the nearest road segment direction instead of trusting vehicle angle', () => {
    const [pose] = createLocalVehiclePoses([
      vehicle({ roadId: 'northbound', x: 100, y: 220, angle: 0 }),
    ], roadnet, 'center')

    expect(pose?.position.x).toBe(11)
    expect(pose?.position.z).toBe(-20)
    expect(Math.abs(pose?.rotationY ?? 0)).toBeCloseTo(Math.PI)
  })

  it('derives each approach-lane movement from its roadLink', () => {
    const threeMovements: SimRoadnetResponse = {
      ...roadnet,
      roadLinks: [
        { ...roadnet.roadLinks[0]!, type: 'turn_left', laneLinks: [{ id: 'left', startLaneIndex: 0, endLaneIndex: 0, points: [] }] },
        { ...roadnet.roadLinks[0]!, index: 1, type: 'go_straight', laneLinks: [{ id: 'straight', startLaneIndex: 1, endLaneIndex: 1, points: [] }] },
        { ...roadnet.roadLinks[0]!, index: 2, type: 'turn_right', laneLinks: [{ id: 'right', startLaneIndex: 2, endLaneIndex: 2, points: [] }] },
      ],
    }

    const poses = createLocalVehiclePoses([
      vehicle({ id: 'left', lane: 0 }),
      vehicle({ id: 'straight', lane: 1 }),
      vehicle({ id: 'right', lane: 2 }),
    ], threeMovements, 'center')

    expect(poses.map((pose) => pose.movement)).toEqual(['left_turn', 'straight', 'right_turn'])
  })

  it('samples laneLink geometry without applying a second lane offset', () => {
    const [pose] = createLocalVehiclePoses([vehicle({
      drivableId: 'eastbound_1_TO_northbound_0',
      drivableType: 'lane_link',
      distance: 15,
      x: 0,
      y: 0,
    })], roadnet, 'center')

    expect(pose?.position.toArray()).toEqual([30, 0.8, -5])
    expect(Math.abs(pose?.rotationY ?? 0)).toBeCloseTo(Math.PI)
    expect(pose?.movement).toBe('left_turn')
  })

  it('samples multi-segment polylines and clamps past their end', () => {
    const points = [{ x: 0, y: 0 }, { x: 0, y: 0 }, { x: 10, y: 0 }, { x: 10, y: 10 }]

    expect(samplePolylineAtDistance(points, 15)).toEqual({
      point: { x: 10, y: 5 },
      direction: { x: 0, y: 1 },
    })
    expect(samplePolylineAtDistance(points, 30)).toEqual({
      point: { x: 10, y: 10 },
      direction: { x: 0, y: 1 },
    })
  })

  it('filters laneLinks from other intersections', () => {
    const otherRoadnet: SimRoadnetResponse = {
      ...roadnet,
      roadLinks: roadnet.roadLinks.map((roadLink) => ({ ...roadLink, intersectionId: 'other' })),
    }
    const poses = createLocalVehiclePoses([vehicle({
      drivableId: 'eastbound_1_TO_northbound_0',
      drivableType: 'lane_link',
      distance: 5,
    })], otherRoadnet, 'center')

    expect(poses).toEqual([])
  })

  it('filters unrelated and out-of-range vehicles', () => {
    const poses = createLocalVehiclePoses([
      vehicle({ id: 'unrelated', roadId: 'unrelated', x: 120, y: 200 }),
      vehicle({ id: 'far', x: 250, y: 200 }),
    ], roadnet, 'center')

    expect(poses).toEqual([])
  })
})
