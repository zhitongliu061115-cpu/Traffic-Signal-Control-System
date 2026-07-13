// @vitest-environment jsdom

import { afterEach, describe, expect, it, vi } from 'vitest'

import { stopSimulation } from '../simulation'

describe('simulation api', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('uses a keepalive request when stopping a simulation during page cleanup', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      text: () => Promise.resolve(JSON.stringify({ success: true, message: 'ok', data: null })),
    } as Response)

    await stopSimulation('run-1')

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/simulations/run-1/stop'),
      expect.objectContaining({
        method: 'POST',
        body: '{}',
        keepalive: true,
      }),
    )
  })
})
