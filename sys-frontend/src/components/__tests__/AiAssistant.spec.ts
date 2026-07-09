// @vitest-environment jsdom

import { describe, expect, it, vi, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'

import AiAssistant from '../AiAssistant.vue'

async function flushPromises() {
  await Promise.resolve()
  await Promise.resolve()
}

describe('AiAssistant', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('opens the floating assistant panel from the trigger button', async () => {
    const wrapper = mount(AiAssistant, {
      global: {
        plugins: [createPinia()],
      },
    })

    expect(wrapper.find('#ai-assistant-panel').exists()).toBe(false)

    await wrapper.find('.ai-float-trigger').trigger('click')

    expect(wrapper.find('#ai-assistant-panel').exists()).toBe(true)
    expect(wrapper.text()).toContain('智能体辅助决策')

    wrapper.unmount()
  })

  it('sends chat requests through the backend Bailian proxy', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        message: 'ok',
        data: {
          reply: '百炼建议：当前为待人工确认方案。',
          sessionId: 'session-1',
          source: 'bailian',
          fallback: false,
        },
      }),
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AiAssistant, {
      global: {
        plugins: [createPinia()],
      },
    })

    await wrapper.find('.ai-float-trigger').trigger('click')
    await wrapper.find('.ai-input').setValue('当前路网状态如何？')
    await wrapper.find('.ai-send').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/v1/agent/chat',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    const firstCall = fetchMock.mock.calls[0]
    expect(firstCall).toBeDefined()
    const requestInit = firstCall![1] as RequestInit
    expect(JSON.parse(String(requestInit.body)).message).toBe('当前路网状态如何？')
    expect(wrapper.text()).toContain('百炼建议')

    wrapper.unmount()
  })
})
