// @vitest-environment jsdom

import { describe, expect, it } from 'vitest'
import { mount, RouterLinkStub } from '@vue/test-utils'
import { nextTick } from 'vue'

import DataAnalysis from '../DataAnalysis.vue'

describe('DataAnalysis', () => {
  it('renders the replicated analytics cockpit', () => {
    const wrapper = mount(DataAnalysis, {
      global: {
        stubs: {
          RouterLink: RouterLinkStub,
        },
      },
    })

    expect(wrapper.text()).toContain('路网大屏')
    expect(wrapper.text()).toContain('数据分析')
    expect(wrapper.text()).toContain('信号灯配时控制与应急通行信控系统')
    expect(wrapper.text()).toContain('运行健康评分')
    expect(wrapper.text()).toContain('每日能耗与人流走势')
    expect(wrapper.text()).toContain('近期监测明细')
    expect(wrapper.findAll('thead th')).toHaveLength(10)
    expect(wrapper.findAll('.heatmap-cell')).toHaveLength(28)
    expect(wrapper.findAll('.scatter-point-group')).toHaveLength(96)
    expect(wrapper.findAll('.composition-status-card')).toHaveLength(4)
    expect(wrapper.findAll('.detail-table-row')).toHaveLength(12)

    wrapper.unmount()
  })

  it('shows dashboard tooltip content on hover', async () => {
    const wrapper = mount(DataAnalysis, {
      attachTo: document.body,
      global: {
        stubs: {
          RouterLink: RouterLinkStub,
        },
      },
    })

    wrapper.find('.metric-card-interactive').element.dispatchEvent(
      new MouseEvent('pointerover', {
        bubbles: true,
        clientX: 120,
        clientY: 120,
      }),
    )
    await nextTick()

    expect(wrapper.find('.dashboard-tooltip').exists()).toBe(true)
    expect(wrapper.find('.dashboard-tooltip').text()).toContain('今日累计明细')

    wrapper.unmount()
  })
})
