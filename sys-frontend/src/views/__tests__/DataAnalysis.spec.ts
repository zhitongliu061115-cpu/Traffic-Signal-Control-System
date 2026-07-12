// @vitest-environment jsdom

import { describe, expect, it } from 'vitest'
import { mount, RouterLinkStub } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia } from 'pinia'

import DataAnalysis from '../DataAnalysis.vue'

describe('DataAnalysis', () => {
  it('renders the replicated analytics cockpit', () => {
    const wrapper = mount(DataAnalysis, {
      global: {
        plugins: [createPinia()],
        stubs: {
          RouterLink: RouterLinkStub,
        },
      },
    })

    expect(wrapper.text()).toContain('路网大屏')
    expect(wrapper.text()).toContain('数据分析')
    expect(wrapper.text()).toContain('信号灯配时控制与应急通行信控系统')
    expect(wrapper.text()).toContain('路网运行健康评分')
    expect(wrapper.text()).toContain('每日通行量与延误走势')
    expect(wrapper.text()).toContain('AI 控制前后效果对比')
    expect(wrapper.text()).toContain('近期路口监测明细')
    expect(wrapper.findAll('thead th')).toHaveLength(9)
    expect(wrapper.findAll('.heatmap-cell')).toHaveLength(28)
    expect(wrapper.findAll('.scatter-point-group')).toHaveLength(48)
    expect(wrapper.findAll('.composition-status-card')).toHaveLength(4)
    expect(wrapper.text()).toContain('今日累计通行量')
    expect(wrapper.text()).toContain('昨日累计')
    expect(wrapper.text()).toContain('排队改善46%')
    expect(wrapper.findAll('.detail-table-row')).toHaveLength(12)
    expect(wrapper.find('.ai-float-trigger').exists()).toBe(true)

    const dateCards = wrapper.findAll('.daily-date-card')
    expect(dateCards).toHaveLength(12)
    const yesterday = new Date()
    yesterday.setDate(yesterday.getDate() - 1)
    const yesterdayLabel = `${String(yesterday.getMonth() + 1).padStart(2, '0')}-${String(yesterday.getDate()).padStart(2, '0')}`
    expect(dateCards[dateCards.length - 1]?.text()).toContain(yesterdayLabel)

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
