// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { shallowMount } from '@vue/test-utils'

import Dashboard from '../Dashboard.vue'

const storeMock = {
  dataSourceStatus: 'mock',
  simulationStatus: 'finished',
  simulationSid: null as string | null,
  loadDashboardData: vi.fn(async () => false),
  initSimulationSession: vi.fn(async () => null),
  resumeSimulation: vi.fn(),
  updateVehiclePositions: vi.fn(),
  updateTrafficIndicators: vi.fn(),
  addCongestionTrendPoint: vi.fn(),
  handleSimFrame: vi.fn(),
  handleControlDecision: vi.fn(),
  resetSimulationState: vi.fn(),
}

const wsMock = {
  connect: vi.fn(),
  disconnect: vi.fn(),
}

vi.mock('@/stores/traffic', () => ({
  useTrafficStore: () => storeMock,
}))

vi.mock('@/composables/useSimulationWebSocket', async () => {
  const { ref } = await vi.importActual<typeof import('vue')>('vue')

  return {
    useSimulationWebSocket: () => ({
      status: ref('disconnected'),
      lastFrameData: ref(null),
      lastControlDecision: ref(null),
      connect: wsMock.connect,
      disconnect: wsMock.disconnect,
    }),
  }
})

describe('Dashboard layout', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    storeMock.dataSourceStatus = 'mock'
    storeMock.simulationStatus = 'finished'
    storeMock.simulationSid = null
  })

  it('places AI control effect in the left column and removes alerts and footer network', () => {
    const wrapper = shallowMount(Dashboard, {
      global: {
        stubs: {
          SystemWorkbenchHeader: { name: 'SystemWorkbenchHeader', template: '<header data-test="workbench-header" />' },
          TrafficStats: { name: 'TrafficStats', template: '<section data-test="traffic-stats" />' },
          CompareCharts: { name: 'CompareCharts', template: '<section data-test="compare-charts" />' },
          MapRoadNetwork: { name: 'MapRoadNetwork', template: '<section data-test="map-road-network" />' },
          SignalControlPanel: { name: 'SignalControlPanel', template: '<section data-test="signal-control-panel" />' },
          EmergencyPanel: { name: 'EmergencyPanel', template: '<section data-test="emergency-panel" />' },
          AiAssistant: { name: 'AiAssistant', template: '<aside data-test="ai-assistant" />' },
          AlertPanel: { name: 'AlertPanel', template: '<section data-test="alert-panel" />' },
          RoadNetwork: { name: 'RoadNetwork', template: '<section data-test="road-network" />' },
        },
      },
    })

    const leftItems = wrapper.find('.ts-col--left').findAll('[data-test]').map((node) => node.attributes('data-test'))
    const centerItems = wrapper.find('.ts-col--center').findAll('[data-test]').map((node) => node.attributes('data-test'))
    const rightItems = wrapper.find('.ts-col--right').findAll('[data-test]').map((node) => node.attributes('data-test'))

    expect(leftItems).toEqual(['traffic-stats', 'compare-charts'])
    expect(centerItems).toEqual(['map-road-network'])
    expect(rightItems).toEqual(['signal-control-panel', 'emergency-panel'])
    expect(wrapper.find('[data-test="alert-panel"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="road-network"]').exists()).toBe(false)
    expect(wrapper.find('.ts-footer').exists()).toBe(false)

    wrapper.unmount()
  })
})
