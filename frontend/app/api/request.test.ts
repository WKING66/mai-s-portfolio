import { afterEach, describe, expect, it, vi } from 'vitest'
import { ofetch } from 'ofetch'
import { requestApi } from './request'

afterEach(() => vi.unstubAllGlobals())

describe('requestApi', () => {
  it.each([401, 403, 429])('extracts a business message from a real rejected HTTP %i response', async (status) => {
    const message = `业务错误-${status}`
    vi.stubGlobal('$fetch', ofetch.create({}, {
      fetch: async () => new Response(JSON.stringify({ code: 'REJECTED', message, data: null, details: [] }), {
        status, headers: { 'Content-Type': 'application/json' },
      }),
    }))
    await expect(requestApi('/api/v1/auth/session', { method: 'GET' }, '请求失败'))
      .rejects.toThrow(message)
  })

  it('uses a safe fallback for network and non-envelope errors', async () => {
    vi.stubGlobal('$fetch', vi.fn().mockRejectedValue(new Error('private upstream address')))
    await expect(requestApi('/api/v1/auth/session', {}, '服务暂时不可用')).rejects.toThrow('服务暂时不可用')
    vi.stubGlobal('$fetch', vi.fn().mockRejectedValue({ data: { message: 'private exception details' } }))
    await expect(requestApi('/api/v1/auth/session', {}, '服务暂时不可用')).rejects.toThrow('服务暂时不可用')
  })

  it('does not automatically retry rejected writes', async () => {
    const fetch = vi.fn(async () => new Response(JSON.stringify({
      code: 'INTERNAL_ERROR', message: '服务暂时不可用', data: null, details: [],
    }), { status: 500, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('$fetch', ofetch.create({}, { fetch }))
    await expect(requestApi('/api/v1/auth/session', { method: 'POST' }, '请求失败')).rejects.toThrow('服务暂时不可用')
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
