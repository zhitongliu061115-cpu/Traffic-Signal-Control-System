// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, RouterLinkStub } from '@vue/test-utils'

import Login from '../Login.vue'
import Register from '../Register.vue'

const pushMock = vi.fn()

vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router')
  return {
    ...actual,
    useRouter: () => ({
      push: pushMock,
    }),
  }
})

describe('Auth views', () => {
  beforeEach(() => {
    pushMock.mockReset()
  })

  it('renders password and captcha login modes', async () => {
    const wrapper = mount(Login, {
      global: {
        stubs: {
          RouterLink: RouterLinkStub,
        },
      },
    })

    expect(wrapper.text()).toContain('信号灯配时控制与应急通行信控系统')
    expect(wrapper.text()).toContain('系统登录')
    expect(wrapper.text()).toContain('账号密码登录')
    expect(wrapper.find('input[autocomplete="username"]').exists()).toBe(true)
    expect(wrapper.find('input[autocomplete="email"]').exists()).toBe(false)

    await wrapper.findAll('button').find((button) => button.text() === '验证码')?.trigger('click')

    expect(wrapper.text()).toContain('邮箱验证码登录')
    expect(wrapper.find('input[autocomplete="one-time-code"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('发送验证码')

    wrapper.unmount()
  })

  it('renders invite-only registration form', () => {
    const wrapper = mount(Register, {
      global: {
        stubs: {
          RouterLink: RouterLinkStub,
        },
      },
    })

    expect(wrapper.text()).toContain('账号注册')
    expect(wrapper.text()).toContain('仅允许邀请码注册')
    expect(wrapper.text()).not.toContain('123456')
    expect(wrapper.find('input[placeholder="请输入授权邀请码"]').exists()).toBe(true)

    wrapper.unmount()
  })
})
