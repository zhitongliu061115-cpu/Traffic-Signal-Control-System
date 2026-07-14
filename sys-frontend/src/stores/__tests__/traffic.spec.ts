import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useTrafficStore } from '../traffic'
import type { SimFrameData, SimRoadnetResponse } from '@/types/traffic'

const roadnets: Record<string, SimRoadnetResponse> = {
  jinan_3x4: {
    sceneId: 'jinan_3x4',
    intersections: [],
    roads: [],
    roadLinks: [],
    phases: [],
  },
  hangzhou_4_4: {
    sceneId: 'hangzhou_4_4',
    intersections: [
      { id: 'intersection_1_1', x: 0, y: 0, virtual: false },
      { id: 'intersection_2_1', x: 100, y: 0, virtual: false },
    ],
    roads: [{
      id: 'hangzhou-road',
      from: 'intersection_1_1',
      to: 'intersection_2_1',
      points: [{ x: 0, y: 0 }, { x: 50, y: 10 }, { x: 100, y: 0 }],
      laneCount: 2,
    }],
    roadLinks: [],
    phases: [],
  },
}

vi.mock('@/api/simulation', () => ({
  createSimulation: vi.fn(async (request: { sceneId: string; controllerType?: string }) => ({
    sid: 'simulation-test-sid',
    sceneId: request.sceneId,
    status: 'paused',
    controllerType: request.controllerType ?? 'fixed-time',
  })),
  startSimulation: vi.fn(async () => undefined),
  pauseSimulation: vi.fn(async () => undefined),
  stopSimulation: vi.fn(async () => undefined),
  fetchRoadnet: vi.fn(async (sceneId: string) => structuredClone(roadnets[sceneId]!)),
  dispatchEmergency: vi.fn(async () => {
    throw new Error('not used in traffic store tests')
  }),
}))

describe('traffic simulation frame storage', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('keeps CityFlow vehicles in the shallow realtime array without duplicating them', () => {
    const store = useTrafficStore()
    const demoVehicleIds = store.vehicles.map((vehicle) => vehicle.id)
    const frame: SimFrameData = {
      simTime: 1,
      vehicles: [{
        id: 'cityflow-vehicle', roadId: 'road-1', lane: 0,
        x: 10, y: 20, angle: 0, speed: 8,
      }],
      roads: [], intersections: [], signals: [],
      metrics: { vehicleCount: 1, queueCount: 0, avgSpeed: 8, avgWait: 0, throughput: 0 },
    }

    store.handleSimFrame(frame)

    expect(store.simulationVehicles).toEqual(frame.vehicles)
    expect(store.vehicles.map((vehicle) => vehicle.id)).toEqual(demoVehicleIds)
  })

  it('keeps planned Jinan map paths when simulation creation reloads the roadnet', async () => {
    const store = useTrafficStore()

    store.simulationSceneId = 'hangzhou_4_4'
    expect(await store.loadSceneRoadnet()).toBe(true)
    expect(store.roads.map((road) => road.id)).toContain('hangzhou-road')
    expect(store.roads.some((road) => road.id.startsWith('HZRX'))).toBe(true)
    expect(store.intersections.some((intersection) => intersection.id.startsWith('HZVX'))).toBe(true)
    expect(store.vehicles).toEqual([])

    store.simulationSceneId = 'jinan_3x4'
    expect(await store.loadSceneRoadnet()).toBe(true)
    const plannedPath: [number, number][] = [
      [121.4701, 31.2311],
      [121.4688, 31.2324],
      [121.4662, 31.2320],
      [121.4635, 31.2317],
    ]
    store.roads[0]!.path = plannedPath

    const result = await store.initSimulationSession()

    expect(result?.sceneId).toBe('jinan_3x4')
    expect(store.roads[0]!.path).toEqual(plannedPath)
  })

  it('keeps planned Hangzhou display paths when simulation creation reloads the roadnet', async () => {
    const store = useTrafficStore()
    store.simulationSceneId = 'hangzhou_4_4'
    expect(await store.loadSceneRoadnet()).toBe(true)

    const road = store.roads.find((item) => item.id === 'HZRX01')
    expect(road).toBeTruthy()
    const plannedPath: [number, number][] = [
      [120.111, 30.301],
      [120.126, 30.303],
      [120.144, 30.304],
    ]
    road!.path = plannedPath

    const result = await store.initSimulationSession()

    expect(result?.sceneId).toBe('hangzhou_4_4')
    expect(store.roads.find((item) => item.id === 'HZRX01')?.path).toEqual(plannedPath)
    expect(store.vehicles).toEqual([])
  })
})
