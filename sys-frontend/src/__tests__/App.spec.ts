// @vitest-environment jsdom

import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, onMounted, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'

import App from '../App.vue'

describe('App route state', () => {
  it('keeps the data analysis instance alive while switching to the dashboard', async () => {
    let analysisMountCount = 0
    const DataAnalysisStub = defineComponent({
      name: 'DataAnalysis',
      setup() {
        const value = ref(0)
        onMounted(() => {
          analysisMountCount += 1
        })
        return { value }
      },
      template: '<button class="analysis-state" @click="value += 1">{{ value }}</button>',
    })
    const DashboardStub = defineComponent({
      name: 'Dashboard',
      template: '<div class="dashboard-state">dashboard</div>',
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { component: DashboardStub, name: 'Dashboard', path: '/' },
        { component: DataAnalysisStub, name: 'DataAnalysis', path: '/data-analysis' },
      ],
    })

    await router.push('/data-analysis')
    const wrapper = mount(App, { global: { plugins: [router] } })
    await router.isReady()
    await wrapper.get('.analysis-state').trigger('click')

    await router.push('/')
    await flushPromises()
    expect(wrapper.find('.dashboard-state').exists()).toBe(true)

    await router.push('/data-analysis')
    await flushPromises()
    expect(wrapper.get('.analysis-state').text()).toBe('1')
    expect(analysisMountCount).toBe(1)

    wrapper.unmount()
  })
})
