// @vitest-environment jsdom

import { afterEach, describe, expect, it, vi } from 'vitest'

import { loginWithCaptcha } from '../auth'

describe('auth api', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('uses backend error message for failed auth requests', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      json: () => Promise.resolve({
        data: null,
        message: 'captcha 不能为空',
        success: false,
      }),
      ok: false,
      status: 400,
    } as Response)

    await expect(loginWithCaptcha({ captcha: '', email: 'operator@example.com' }))
      .rejects
      .toThrow('captcha 不能为空')
  })
})
