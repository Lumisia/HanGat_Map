import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest, clearBackendSession } from './backendClient.js'

describe('public Course API request', () => {
  beforeEach(() => clearBackendSession())

  afterEach(() => vi.unstubAllGlobals())

  it('accepts a successful empty response from recovery decisions', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
    await expect(apiRequest('/auth/withdrawal/cancel', { method: 'POST' })).resolves.toBeNull()
  })

  it('does not request token reissue or send Authorization for anonymous generation', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: vi.fn().mockResolvedValue({
        success: true,
        code: 1000,
        message: '요청에 성공하였습니다.',
        result: { id: 101, status: 'READY' },
      }),
    })
    vi.stubGlobal('fetch', fetchMock)

    const result = await apiRequest('/courses', {
      method: 'POST',
      body: { start_date: '2026-08-28', end_date: '2026-08-29' },
    })

    expect(result).toEqual({ id: 101, status: 'READY' })
    expect(fetchMock).toHaveBeenCalledOnce()
    expect(fetchMock.mock.calls[0][0]).toBe('http://localhost:8080/courses')
    expect(fetchMock.mock.calls[0][0]).not.toContain('/auth/reissue')
    expect(fetchMock.mock.calls[0][1].headers).not.toHaveProperty('Authorization')
  })
})
