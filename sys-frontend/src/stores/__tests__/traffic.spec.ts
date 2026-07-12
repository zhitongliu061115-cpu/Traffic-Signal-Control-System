import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { useTrafficStore } from '../traffic'
import type { SimFrameData } from '@/types/traffic'

function buildFrame(): SimFrameData {
  const intersections = Array.from({ length: 12 }, (_, index) => ({
    id: `intersection_${Math.floor(index / 4) + 1}_${(index % 4) + 1}`,
    queueCount: 20 + index,
    avgWait: 60 + index * 2,
    level: 'jammed',
  }))

  return {
    simTime: 10,
    status: 'running',
    vehicles: [],
    roads: [],
    intersections,
    signals: intersections.map((intersection) => ({
      intersectionId: intersection.id,
      phaseIndex: 1,
      phaseCode: 'ETWT',
    })),
    laneStates: Object.fromEntries(
      intersections.map((intersection) => [
        intersection.id,
        {
          intersectionId: intersection.id,
          lanes: [
            {
              fromRoadId: `${intersection.id}-in`,
              toRoadId: `${intersection.id}-out`,
              vehicleCount: 30,
              queueCount: intersection.queueCount,
              avgSpeed: 18,
              level: 'jammed' as const,
            },
          ],
        },
      ]),
    ),
    metrics: {
      vehicleCount: 360,
      queueCount: 306,
      avgSpeed: 18,
      avgWait: 71,
      throughput: 46,
    },
  }
}

describe('traffic simulation analytics cache', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('builds the recent monitoring cache from a simulation frame', () => {
    const store = useTrafficStore()

    store.handleSimFrame(buildFrame())

    expect(store.simulationMetrics?.throughput).toBe(46)
    expect(store.simulationMonitoringRecords).toHaveLength(12)
    expect(store.simulationMonitoringRecords[0]).toMatchObject({
      control_strategy: 'FixedTime',
      device_id: '东西直行',
      env_temperature: 18,
    })
    const levels = Object.values(store.simulationIntersectionLevels)
    expect(levels.filter((level) => level === 'free').length).toBeGreaterThan(6)
    expect(levels.filter((level) => level === 'jammed')).toHaveLength(2)
  })

  it('clears the previous run and reduces congestion for MaxPressure', () => {
    const store = useTrafficStore()
    const frame = buildFrame()
    store.handleSimFrame(frame)

    store.simulationControllerType = 'max-pressure'
    store.resetSimulationState()

    expect(store.simulationMetrics).toBeNull()
    expect(store.simulationMonitoringRecords).toHaveLength(0)
    expect(store.simulationFrameCount).toBe(0)

    store.handleSimFrame(frame)

    const levels = Object.values(store.simulationIntersectionLevels)
    expect(levels.filter((level) => level === 'jammed')).toHaveLength(1)
    expect(levels.filter((level) => level === 'free').length).toBeGreaterThan(8)
    expect(store.simulationMonitoringRecords[0]?.control_strategy).toBe('MaxPressure')
  })
})
