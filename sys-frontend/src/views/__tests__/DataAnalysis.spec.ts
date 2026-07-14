// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia } from 'pinia'

import DataAnalysis from '../DataAnalysis.vue'
import {
  fetchDataAnalysisBootstrap,
  fetchDataAnalysisForecast,
  fetchNextDataAnalysisUpdate,
  type DataAnalysisBootstrapData,
  type DataAnalysisForecastData,
} from '@/api/dataAnalysis'

vi.mock('@/api/dataAnalysis', () => ({
  fetchDataAnalysisBootstrap: vi.fn(),
  fetchDataAnalysisForecast: vi.fn(),
  fetchNextDataAnalysisUpdate: vi.fn(),
}))

const forecastData: DataAnalysisForecastData = {
  available: true,
  dataUntil: '2026-07-13T09:59:00',
  generatedAt: '2026-07-13T10:00:00Z',
  message: 'ok',
  modelType: 'LightGBM direct multi-horizon regression',
  modelVersion: 'lgbm-test',
  trainedSource: 'SYNTHETIC:10000',
  intersections: Array.from({ length: 12 }, (_, index) => ({
    flow: 600 + index * 20,
    id: `intersection_${Math.floor(index / 4) + 1}_${(index % 4) + 1}`,
    label: `路口 ${Math.floor(index / 4) + 1}-${(index % 4) + 1}`,
    queue: 5 + index / 2,
    risk: index > 9 ? '缓行' : '畅通',
    riskLevel: index > 9 ? 'slow' : 'free',
    wait: 24 + index,
  })),
  timeline: [2, 4, 6, 8, 10].map((horizon) => ({
    flow: 700 + horizon * 10,
    horizonMinutes: horizon,
    minute: `+${horizon}分钟`,
    queue: 6 + horizon / 5,
    risk: '畅通',
    riskLevel: 'free',
    wait: 28 + horizon,
  })),
}

const unavailableForecastData: DataAnalysisForecastData = {
  available: false,
  dataUntil: null,
  generatedAt: null,
  message: '预测历史数据不足',
  modelType: null,
  modelVersion: null,
  trainedSource: null,
  intersections: [],
  timeline: [],
}

const bootstrapData: DataAnalysisBootstrapData = {
  sampleCount: 34752,
  sampleRate: 96,
  healthScore: 91,
  sampledPointId: 'intersection_3_4-48',
  liveCursor: 0,
  livePollIntervalMs: 5000,
  scatterCorrelation: 0.82,
  metrics: [
    { detail: '数据库累计通行量', label: '今日累计通行量', tone: 'sky', value: '87,645 辆' },
    { detail: '数据库平均排队', label: '当前平均排队长度', tone: 'emerald', value: '6.4 辆' },
    { detail: '数据库平均等待', label: '当前平均等待时间', tone: 'amber', value: '39 秒' },
    { detail: '数据库策略覆盖', label: '自适应控制覆盖率', tone: 'sky', value: '91.7%' },
    { detail: '数据库事件统计', label: '今日拥堵/事件告警', tone: 'rose', value: '3 条' },
  ],
  metricTrends: [],
  statusDistribution: [
    { count: 8, label: '畅通', tone: 'emerald' },
    { count: 2, label: '缓行', tone: 'amber' },
    { count: 1, label: '拥堵', tone: 'rose' },
    { count: 1, label: '离线', tone: 'slate' },
  ],
  dailySeries: Array.from({ length: 12 }, (_, index) => ({
    date: `07-${String(index + 1).padStart(2, '0')}`,
    electricity: 50000 + index * 2500,
    hvac: 0,
    occupancy: 30 + index * 2,
    water: 0,
  })),
  hourlySeries: ['00:00', '06:00', '12:00', '18:00'].map((hour, index) => ({
    electricity: 500 + index * 500,
    hour,
    hvac: 0,
    occupancy: 40 + index * 15,
    temperature: 5 + index * 6,
  })),
  buildingSummaries: Array.from({ length: 4 }, (_, index) => ({
    averageOccupancy: 50 + index,
    buildingId: `intersection_${index + 1}_1`,
    buildingType: 'arterial',
    efficiencyScore: 80 - index,
    electricity: 70,
    hvac: 10,
    statusLabel: '运行平稳',
    warningCount: 6 + index,
    water: 1,
  })),
  heatmap: Array.from({ length: 28 }, (_, index) => ({
    date: `07-${String(6 + Math.floor(index / 4)).padStart(2, '0')}`,
    electricity: 6 + index,
    hour: ['00:00', '06:00', '12:00', '18:00'][index % 4]!,
    intensity: index / 28,
    occupancy: 40 + index,
  })),
  composition: ['东西直行', '南北直行', '东西左转', '南北左转', '应急优先', '其他'].map((label, index) => ({
    color: ['#3b82f6', '#22c55e', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4'][index]!,
    label,
    value: 1000 - index * 100,
  })),
  scatterPoints: Array.from({ length: 48 }, (_, index) => ({
    buildingId: `intersection_${Math.floor(index / 4) + 1}_1`,
    electricity: 5 + index / 2,
    hour: ['00:00', '06:00', '12:00', '18:00'][index % 4]!,
    id: `point-${index + 1}`,
    occupancy: 300 + index * 20,
    temperature: 20 + index,
    tone: (['sky', 'emerald', 'amber', 'rose'] as const)[Math.floor(index / 12)]!,
  })),
  strategyMetrics: [
    { baseline: 18, label: '平均排队长度', maxPressure: 12.4, trafficR1: 9.7, unit: '辆', lowerBetter: true },
    { baseline: 1260, label: '累计排队车辆数', maxPressure: 880, trafficR1: 690, unit: '辆', lowerBetter: true },
    { baseline: 52, label: '平均等待时间', maxPressure: 38, trafficR1: 31, unit: '秒', lowerBetter: true },
    { baseline: 238, label: '平均旅行时间', maxPressure: 209, trafficR1: 196, unit: '秒', lowerBetter: true },
    { baseline: 7200, label: '通行量', maxPressure: 7900, trafficR1: 8350, unit: '辆/h', lowerBetter: false },
  ],
  records: Array.from({ length: 12 }, (_, index) => ({
    building_id: `路口 ${index + 1}`,
    building_type: `intersection_${index + 1}`,
    chilled_water_return_temp: 8,
    chilled_water_supply_temp: 70,
    control_strategy: 'Traffic-R1' as const,
    device_id: '东西直行',
    device_status: 'normal' as const,
    electricity_kwh: 800,
    env_humidity: 70,
    env_temperature: 35,
    hvac_kwh: 8,
    id: 3000 + index,
    monitor_time: '2026-07-13 08:00:00',
    occupancy_density: 45,
    water_m3: 25,
  })),
  toasts: [],
}

describe('DataAnalysis', () => {
  beforeEach(() => {
    vi.mocked(fetchDataAnalysisBootstrap).mockResolvedValue(bootstrapData)
    vi.mocked(fetchDataAnalysisForecast).mockResolvedValue(forecastData)
    vi.mocked(fetchNextDataAnalysisUpdate).mockResolvedValue(null)
  })

  it('renders the replicated analytics cockpit', async () => {
    const wrapper = mount(DataAnalysis, {
      global: {
        plugins: [createPinia()],
        stubs: {
          RouterLink: RouterLinkStub,
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('路网大屏')
    expect(wrapper.text()).toContain('数据分析')
    expect(wrapper.text()).toContain('信号灯配时控制与应急通行信控系统')
    expect(wrapper.text()).toContain('路网运行健康评分')
    expect(wrapper.text()).toContain('每日通行量与延误走势')
    expect(wrapper.text()).toContain('AI 控制前后效果对比')
    expect(wrapper.text()).toContain('近期路口监测明细')
    expect(wrapper.text()).toContain('lgbm-test')
    expect(wrapper.findAll('thead th')).toHaveLength(9)
    expect(wrapper.findAll('.heatmap-cell')).toHaveLength(28)
    expect(wrapper.findAll('.scatter-point-group')).toHaveLength(48)
    expect(wrapper.findAll('.composition-status-card')).toHaveLength(4)
    expect(wrapper.findAll('.forecast-intersection-card')).toHaveLength(12)
    expect(wrapper.text()).toContain('今日累计通行量')
    expect(wrapper.text()).toContain('昨日累计')
    expect(wrapper.text()).toContain('排队改善46%')
    expect(wrapper.findAll('.detail-table-row')).toHaveLength(12)
    expect(wrapper.find('.ai-float-trigger').exists()).toBe(true)

    const dateCards = wrapper.findAll('.daily-date-card')
    expect(dateCards).toHaveLength(12)
    expect(dateCards[dateCards.length - 1]?.text()).toContain('07-12')

    wrapper.unmount()
  })

  it('renders local demo forecast when backend forecast is unavailable', async () => {
    vi.mocked(fetchDataAnalysisForecast).mockResolvedValue(unavailableForecastData)

    const wrapper = mount(DataAnalysis, {
      global: {
        plugins: [createPinia()],
        stubs: {
          RouterLink: RouterLinkStub,
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain('lgbm-local-demo')
    expect(wrapper.text()).not.toContain('本地演示数据')
    expect(wrapper.findAll('.forecast-intersection-card')).toHaveLength(12)
    expect(wrapper.findAll('.forecast-unavailable')).toHaveLength(0)

    wrapper.unmount()
  })

  it('shows dashboard tooltip content on hover', async () => {
    const wrapper = mount(DataAnalysis, {
      attachTo: document.body,
      global: {
        plugins: [createPinia()],
        stubs: {
          RouterLink: RouterLinkStub,
        },
      },
    })
    await flushPromises()

    wrapper.find('.metric-card-interactive').element.dispatchEvent(
      new MouseEvent('pointerover', {
        bubbles: true,
        clientX: 120,
        clientY: 120,
      }),
    )
    await nextTick()

    expect(wrapper.find('.dashboard-tooltip').exists()).toBe(true)
    expect(wrapper.find('.dashboard-tooltip').text()).toContain('今日累计通行量明细')

    wrapper.unmount()
  })
})
