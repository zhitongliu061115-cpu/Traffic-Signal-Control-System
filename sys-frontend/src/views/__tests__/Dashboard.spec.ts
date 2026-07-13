// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { shallowMount } from '@vue/test-utils'

import Dashboard from '../Dashboard.vue'

const { storeMock, wsMock } = vi.hoisted(() => ({
  storeMock: {
    dataSourceStatus: 'mock',
    simulationStatus: 'finished',
    simulationSid: null as string | null,
    loadDashboardData: vi.fn(async () => false),
    initSimulationSession: vi.fn(async () => null as { sid: string } | null),
    resumeSimulation: vi.fn(async () => {}),
    pauseSimulationSession: vi.fn(async () => {}),
    stopSimulationSession: vi.fn(async (_sid?: string | null) => {}),
    checkSimulationFrameTimeout: vi.fn(),
    updateVehiclePositions: vi.fn(),
    updateTrafficIndicators: vi.fn(),
    addCongestionTrendPoint: vi.fn(),
    handleSimFrame: vi.fn(),
    handleControlDecision: vi.fn(),
    resetSimulationState: vi.fn(),
  },
  wsMock: {
    connect: vi.fn(),
    disconnect: vi.fn(),
  },
}))

vi.mock('@/stores/traffic', () => ({
  useTrafficStore: () => storeMock,
}))

vi.mock('@/composables/useSimulationWebSocket', async () => {
  const { ref } = await vi.importActual<typeof import('vue')>('vue')
  const status = ref('connected')

  return {
    useSimulationWebSocket: () => ({
      status,
      lastFrameData: ref(null),
      lastControlDecision: ref(null),
      connect: wsMock.connect,
      disconnect: wsMock.disconnect,
    }),
  }
})

function mountDashboard() {
  return shallowMount(Dashboard, {
    global: {
      stubs: {
        SystemWorkbenchHeader: { name: 'SystemWorkbenchHeader', template: '<header data-test="workbench-header" />' },
        TrafficStats: { name: 'TrafficStats', template: '<section data-test="traffic-stats" />' },
        CompareCharts: { name: 'CompareCharts', template: '<section data-test="compare-charts" />' },
        MapRoadNetwork: { name: 'MapRoadNetwork', template: '<section data-test="map-road-network" />' },
        SignalControlPanel: {
          name: 'SignalControlPanel',
          emits: ['startSimulation', 'pauseSimulation', 'stopSimulation', 'simulationRecreated'],
          template: `
            <section data-test="signal-control-panel">
              <button data-action="start-simulation" @click="$emit('startSimulation')" />
              <button data-action="simulation-recreated" @click="$emit('simulationRecreated', 'recreated-sid')" />
            </section>
          `,
        },
        EmergencyPanel: { name: 'EmergencyPanel', template: '<section data-test="emergency-panel" />' },
        AiAssistant: { name: 'AiAssistant', template: '<aside data-test="ai-assistant" />' },
        AlertPanel: { name: 'AlertPanel', template: '<section data-test="alert-panel" />' },
        RoadNetwork: { name: 'RoadNetwork', template: '<section data-test="road-network" />' },
      },
    },
  })
}

describe('Dashboard layout', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    storeMock.dataSourceStatus = 'mock'
    storeMock.simulationStatus = 'finished'
    storeMock.simulationSid = null
  })

  it('places AI control effect in the left column and removes alerts and footer network', () => {
    const wrapper = mountDashboard()

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

  it('stops and resets a simulation session created by this dashboard', async () => {
    storeMock.initSimulationSession.mockImplementationOnce(async () => {
      storeMock.simulationSid = 'owned-sid'
      storeMock.simulationStatus = 'paused'
      return { sid: 'owned-sid' }
    })
    storeMock.resumeSimulation.mockImplementationOnce(async () => {
      storeMock.simulationStatus = 'running'
    })
    const wrapper = mountDashboard()

    await wrapper.find('[data-action="start-simulation"]').trigger('click')
    await vi.waitFor(() => expect(storeMock.resumeSimulation).toHaveBeenCalled())
    wrapper.unmount()

    expect(storeMock.stopSimulationSession).toHaveBeenCalledWith('owned-sid')
    expect(storeMock.resetSimulationState).toHaveBeenCalled()
  })

  it('does not stop or reset a session that the dashboard only reused', async () => {
    storeMock.simulationSid = 'shared-sid'
    storeMock.simulationStatus = 'paused'
    const wrapper = mountDashboard()

    await wrapper.find('[data-action="start-simulation"]').trigger('click')
    await vi.waitFor(() => expect(storeMock.resumeSimulation).toHaveBeenCalled())
    wrapper.unmount()

    expect(storeMock.initSimulationSession).not.toHaveBeenCalled()
    expect(storeMock.stopSimulationSession).not.toHaveBeenCalled()
    expect(storeMock.resetSimulationState).not.toHaveBeenCalled()
  })

  it('owns and reconnects a session recreated by the controller panel', async () => {
    const wrapper = mountDashboard()
    storeMock.simulationSid = 'recreated-sid'
    storeMock.simulationStatus = 'running'

    await wrapper.find('[data-action="simulation-recreated"]').trigger('click')
    wrapper.unmount()

    expect(wsMock.connect).toHaveBeenCalledWith('recreated-sid')
    expect(storeMock.stopSimulationSession).toHaveBeenCalledWith('recreated-sid')
    expect(storeMock.resetSimulationState).toHaveBeenCalled()
  })
})
