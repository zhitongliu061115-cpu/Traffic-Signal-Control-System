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

  it('starts a new conversation and clears the previous session id', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        success: true,
        message: 'ok',
        data: {
          reply: '百炼建议：待人工确认。',
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
    await wrapper.find('.ai-input').setValue('生成调度建议')
    await wrapper.find('.ai-send').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('百炼建议')

    await wrapper.find('.ai-new-chat').trigger('click')

    expect(wrapper.findAll('.ai-chat-msg--user')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('百炼建议')
    expect(wrapper.text()).toContain('城市交通信号调度辅助决策智能体')

    await wrapper.find('.ai-input').setValue('解释 Traffic-R1')
    await wrapper.find('.ai-send').trigger('click')
    await flushPromises()

    const secondCall = fetchMock.mock.calls[1]
    expect(secondCall).toBeDefined()
    const requestInit = secondCall![1] as RequestInit
    expect(JSON.parse(String(requestInit.body)).sessionId).toBeNull()

    wrapper.unmount()
  })

  it('shows the backend Bailian failure reason when using local fallback', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({
        success: false,
        message: '百炼调用失败：HTTP 401。请检查 BAILIAN_API_KEY。',
        data: null,
      }),
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AiAssistant, {
      global: {
        plugins: [createPinia()],
      },
    })

    await wrapper.find('.ai-float-trigger').trigger('click')
    await wrapper.find('.ai-input').setValue('为什么要延长绿灯？')
    await wrapper.find('.ai-send').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('本地兜底')
    expect(wrapper.text()).toContain('百炼调用失败：HTTP 401')
    expect(wrapper.text()).toContain('已使用本地兜底分析')

    wrapper.unmount()
  })
})
