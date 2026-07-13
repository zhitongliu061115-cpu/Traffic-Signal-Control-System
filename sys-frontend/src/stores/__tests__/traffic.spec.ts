import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useTrafficStore } from '../traffic'
import type { SimFrameData } from '@/types/traffic'

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
})
