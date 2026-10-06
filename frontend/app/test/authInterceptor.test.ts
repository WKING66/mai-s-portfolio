import { afterEach, describe, expect, it, vi } from 'vitest'
import { createAuthInterceptor } from '../api/authInterceptor'
import { AUTH_ACCESS, AUTH_MESSAGES, CSRF_HEADER } from '../constants/auth'
import type { SessionResponse } from '../api/session'

const owner: SessionResponse = { loggedIn: true, username: 'owner', roles: ['OWNER'], csrfToken: 'test-csrf' }
const normal: SessionResponse = { loggedIn: true, username: 'normal', roles: [], csrfToken: 'test-normal-csrf' }
const anonymous: SessionResponse = { loggedIn: false, username: null, roles: [], csrfToken: null }
const envelope = (data: unknown) => ({ code: 'OK', data, message: '成功', details: [] })
const rejected = (statusCode: number, code: string) => ({ statusCode, data: { code, message: code, details: [] } })

function fixture(initial = owner, isClient = true, initiallyReady = true) {
  let state = { ...initial }
  let ready = initiallyReady
  const navigate = vi.fn().mockResolvedValue(undefined)
  const interceptor = createAuthInterceptor({
    session: () => state,
    ready: () => ready,
    setSession: session => { state = session; ready = true },
    currentPath: () => '/admin/projects/42',
    navigate,
    isClient: () => isClient,
  })
  return { ...interceptor, state: () => state, navigate }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

afterEach(() => vi.unstubAllGlobals())

describe('auth request and session interceptor', () => {
  it.each([
    [anonymous, true, '/login?returnTo=%2Fadmin%2Fprojects%2F42'],
    [normal, true, '/forbidden'],
    [owner, false, '/login?returnTo=%2Fadmin%2Fprojects%2F42'],
  ])('blocks unauthorized dispatch before sending a request', async (session, ready, destination) => {
    const api = fixture(session, true, ready)
    const fetch = vi.fn()
    vi.stubGlobal('$fetch', fetch)
    await expect(api.request('/api/v1/projects', {}, AUTH_ACCESS.OWNER, '失败')).rejects.toMatchObject({ code: 'CLIENT_FORBIDDEN' })
    expect(fetch).not.toHaveBeenCalled()
    expect(api.navigate).toHaveBeenCalledWith(destination)
  })

  it('does not send management requests or navigate during SSR', async () => {
    const api = fixture(owner, false)
    const fetch = vi.fn()
    vi.stubGlobal('$fetch', fetch)
    await expect(api.request('/api/v1/projects', {}, AUTH_ACCESS.OWNER, '失败')).rejects.toThrow()
    expect(fetch).not.toHaveBeenCalled()
    expect(api.navigate).not.toHaveBeenCalled()
  })

  it('rejects external endpoints before leaking Cookie or CSRF', async () => {
    const api = fixture()
    const fetch = vi.fn()
    vi.stubGlobal('$fetch', fetch)
    await expect(api.request('https://other.test/api/v1/upload', { method: 'POST' }, AUTH_ACCESS.OWNER, '失败')).rejects.toThrow(AUTH_MESSAGES.invalidRequest)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('keeps Headers and tuple headers while overwriting CSRF only on writes', async () => {
    const fetch = vi.fn().mockResolvedValue(envelope({ id: 1 }))
    vi.stubGlobal('$fetch', fetch)
    const api = fixture()
    await api.request('/api/v1/projects', { headers: new Headers({ 'X-Test': 'keep' }) }, AUTH_ACCESS.OWNER, '失败')
    const read = fetch.mock.calls[0]![1]
    expect(read.headers.get('X-Test')).toBe('keep')
    expect(read.headers.has(CSRF_HEADER)).toBe(false)
    await api.request('/api/v1/admin/projects', {
      method: 'POST', headers: [['X-Test', 'keep'], [CSRF_HEADER, 'override']], body: { title: 'test' },
    }, AUTH_ACCESS.OWNER, '失败')
    const write = fetch.mock.calls[1]![1]
    expect(write.headers.get('X-Test')).toBe('keep')
    expect(write.headers.get(CSRF_HEADER)).toBe(owner.csrfToken)
    expect(write).toMatchObject({ credentials: 'same-origin', retry: 0 })
  })

  it('clears and redirects once for concurrent 401s', async () => {
    const fetch = vi.fn().mockRejectedValue(rejected(401, 'UNAUTHENTICATED'))
    vi.stubGlobal('$fetch', fetch)
    const api = fixture()
    await Promise.allSettled([
      api.request('/api/v1/projects', {}, AUTH_ACCESS.OWNER, '失败'),
      api.request('/api/v1/admin/tags', {}, AUTH_ACCESS.OWNER, '失败'),
    ])
    expect(api.state()).toEqual(anonymous)
    expect(api.navigate).toHaveBeenCalledTimes(1)
  })

  it('refreshes CSRF without unmounting allowed content, logout or replay', async () => {
    const api = fixture()
    const fetch = vi.fn()
      .mockRejectedValueOnce(rejected(403, 'CSRF_INVALID'))
      .mockImplementationOnce(async () => {
        expect(api.canAccess(AUTH_ACCESS.OWNER)).toBe(true)
        return envelope({ ...owner, csrfToken: 'refreshed-csrf' })
      })
    vi.stubGlobal('$fetch', fetch)
    await expect(api.request('/api/v1/admin/projects/42', { method: 'PATCH' }, AUTH_ACCESS.OWNER, '失败'))
      .rejects.toMatchObject({ message: AUTH_MESSAGES.csrf, code: 'CSRF_INVALID' })
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(api.state().csrfToken).toBe('refreshed-csrf')
    expect(api.navigate).not.toHaveBeenCalled()
  })

  it('refreshes a revoked role and redirects without claiming logout', async () => {
    vi.stubGlobal('$fetch', vi.fn().mockRejectedValueOnce(rejected(403, 'FORBIDDEN'))
      .mockResolvedValueOnce(envelope({ ...owner, roles: [] })))
    const api = fixture()
    await expect(api.request('/api/v1/projects', {}, AUTH_ACCESS.OWNER, '失败')).rejects.toThrow()
    expect(api.state().loggedIn).toBe(true)
    expect(api.canAccess(AUTH_ACCESS.OWNER)).toBe(false)
    expect(api.navigate).toHaveBeenCalledWith('/forbidden')
  })

  it('fails closed when permission refresh rejects a disabled account', async () => {
    vi.stubGlobal('$fetch', vi.fn().mockRejectedValue(rejected(403, 'FORBIDDEN')))
    const api = fixture()
    await expect(api.request('/api/v1/projects', {}, AUTH_ACCESS.OWNER, '失败')).rejects.toThrow()
    expect(api.state()).toEqual(anonymous)
    expect(api.navigate).toHaveBeenCalledWith('/login?returnTo=%2Fadmin%2Fprojects%2F42')
  })

  it.each([normal, owner])('does not navigate a new login when an old 403 recovery succeeds', async (newSession) => {
    const recovery = deferred<ReturnType<typeof envelope>>()
    const fetch = vi.fn().mockRejectedValueOnce(rejected(403, 'FORBIDDEN'))
      .mockReturnValueOnce(recovery.promise)
    vi.stubGlobal('$fetch', fetch)
    const api = fixture()
    const reading = api.request('/api/v1/projects', {}, AUTH_ACCESS.OWNER, '失败')
    // 首个请求的 403 已进入查询；包括相同用户名的重新登录，都必须使旧查询失效。
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
    api.accept(newSession)
    recovery.resolve(envelope({ ...owner, roles: [] }))
    await expect(reading).rejects.toMatchObject({ code: 'AUTH_SESSION_CHANGED' })
    expect(api.state()).toEqual(newSession)
    expect(api.navigate).not.toHaveBeenCalled()
  })

  it('does not navigate or restore an old account after logout during 403 recovery', async () => {
    const recovery = deferred<ReturnType<typeof envelope>>()
    const fetch = vi.fn().mockRejectedValueOnce(rejected(403, 'FORBIDDEN'))
      .mockReturnValueOnce(recovery.promise).mockResolvedValueOnce(envelope(null))
    vi.stubGlobal('$fetch', fetch)
    const api = fixture()
    const reading = api.request('/api/v1/projects', {}, AUTH_ACCESS.OWNER, '失败')
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
    await api.signOut()
    recovery.resolve(envelope(owner))
    await expect(reading).rejects.toMatchObject({ code: 'AUTH_SESSION_CHANGED' })
    expect(api.state()).toEqual(anonymous)
    expect(api.navigate).not.toHaveBeenCalled()
  })

  it('does not clear or navigate a new login when an old 403 recovery fails', async () => {
    const recovery = deferred<ReturnType<typeof envelope>>()
    const fetch = vi.fn().mockRejectedValueOnce(rejected(403, 'FORBIDDEN'))
      .mockReturnValueOnce(recovery.promise)
    vi.stubGlobal('$fetch', fetch)
    const api = fixture()
    const reading = api.request('/api/v1/projects', {}, AUTH_ACCESS.OWNER, '失败')
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
    api.accept(normal)
    recovery.reject(rejected(403, 'ACCOUNT_DISABLED'))
    await expect(reading).rejects.toMatchObject({ code: 'AUTH_SESSION_CHANGED' })
    expect(api.state()).toEqual(normal)
    expect(api.navigate).not.toHaveBeenCalled()
  })

  it('shows unrelated 403 and conflicts locally without automatic write retries', async () => {
    const fetch = vi.fn().mockRejectedValueOnce(rejected(403, 'POLICY_DENIED'))
      .mockResolvedValueOnce(envelope(owner)).mockRejectedValueOnce(rejected(409, 'DATA_CONFLICT'))
    vi.stubGlobal('$fetch', fetch)
    const api = fixture()
    await expect(api.request('/api/v1/projects', {}, AUTH_ACCESS.OWNER, '失败')).rejects.toMatchObject({ code: 'POLICY_DENIED' })
    await expect(api.request('/api/v1/admin/projects/42', { method: 'PATCH' }, AUTH_ACCESS.OWNER, '失败')).rejects.toMatchObject({ status: 409 })
    expect(api.navigate).not.toHaveBeenCalled()
    expect(fetch).toHaveBeenCalledTimes(3)
  })

  it('deduplicates concurrent session recovery', async () => {
    const response = deferred<ReturnType<typeof envelope>>()
    const fetch = vi.fn().mockReturnValue(response.promise)
    vi.stubGlobal('$fetch', fetch)
    const api = fixture(anonymous, true, false)
    const first = api.refresh()
    const second = api.refresh()
    expect(first).toBe(second)
    response.resolve(envelope(owner))
    await Promise.all([first, second])
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(api.canAccess(AUTH_ACCESS.OWNER)).toBe(true)
  })

  it.each(['before', 'after'])('does not restore an old session when it resolves %s logout', async (order) => {
    const recovery = deferred<ReturnType<typeof envelope>>()
    const deletion = deferred<ReturnType<typeof envelope>>()
    vi.stubGlobal('$fetch', vi.fn().mockReturnValueOnce(recovery.promise).mockReturnValueOnce(deletion.promise))
    const api = fixture()
    const refreshing = api.refresh()
    const signingOut = api.signOut()
    if (order === 'before') { recovery.resolve(envelope(owner)); await refreshing }
    deletion.resolve(envelope(null))
    await signingOut
    if (order === 'after') { recovery.resolve(envelope(owner)); await refreshing }
    expect(api.state()).toEqual(anonymous)
  })

  it('keeps a newer login when an old session query finishes', async () => {
    const response = deferred<ReturnType<typeof envelope>>()
    vi.stubGlobal('$fetch', vi.fn().mockReturnValue(response.promise))
    const api = fixture()
    const refreshing = api.refresh()
    api.accept(normal)
    response.resolve(envelope(owner))
    await refreshing
    expect(api.state()).toEqual(normal)
  })

  it('discards old management responses after clear', async () => {
    const response = deferred<ReturnType<typeof envelope>>()
    vi.stubGlobal('$fetch', vi.fn().mockReturnValue(response.promise))
    const api = fixture()
    const reading = api.request('/api/v1/projects', {}, AUTH_ACCESS.OWNER, '失败')
    api.clear()
    response.resolve(envelope({ privateTitle: 'must not refill' }))
    await expect(reading).rejects.toMatchObject({ code: 'AUTH_SESSION_CHANGED' })
    expect(api.state()).toEqual(anonymous)
  })

  it('does not invalidate a successful request during a same-account token refresh', async () => {
    const response = deferred<ReturnType<typeof envelope>>()
    vi.stubGlobal('$fetch', vi.fn().mockReturnValueOnce(response.promise)
      .mockResolvedValueOnce(envelope({ ...owner, csrfToken: 'renewed' })))
    const api = fixture()
    const reading = api.request('/api/v1/projects', {}, AUTH_ACCESS.OWNER, '失败')
    await api.refresh()
    response.resolve(envelope({ id: 42 }))
    await expect(reading).resolves.toEqual({ id: 42 })
  })

  it('does not clear a new login after an older request fails with 401', async () => {
    let reject!: (error: unknown) => void
    const response = new Promise((_resolve, fail) => { reject = fail })
    vi.stubGlobal('$fetch', vi.fn().mockReturnValue(response))
    const api = fixture()
    const reading = api.request('/api/v1/projects', {}, AUTH_ACCESS.OWNER, '失败')
    api.accept(normal)
    reject(rejected(401, 'UNAUTHENTICATED'))
    await expect(reading).rejects.toMatchObject({ status: 401 })
    expect(api.state()).toEqual(normal)
    expect(api.navigate).not.toHaveBeenCalled()
  })

  it('allows ordinary users to logout through the same authenticated interceptor', async () => {
    const fetch = vi.fn().mockResolvedValue(envelope(null))
    vi.stubGlobal('$fetch', fetch)
    const api = fixture(normal)
    await api.signOut()
    expect(fetch).toHaveBeenCalledWith('/api/v1/auth/session', expect.objectContaining({ method: 'DELETE', retry: 0 }))
    expect(fetch.mock.calls[0]![1].headers.get(CSRF_HEADER)).toBe(normal.csrfToken)
    expect(api.state()).toEqual(anonymous)
  })

  it('does not pretend logout succeeded when the server rejects a request', async () => {
    vi.stubGlobal('$fetch', vi.fn().mockRejectedValue(rejected(500, 'INTERNAL_ERROR')))
    const api = fixture()
    await expect(api.signOut()).rejects.toThrow()
    expect(api.state()).toEqual(owner)
  })
})
