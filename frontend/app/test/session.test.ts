import { afterEach, describe, expect, it, vi } from 'vitest'
import { getSession, login } from '../api/session'

afterEach(() => vi.unstubAllGlobals())

describe('login', () => {
  it('restores session through the common GET endpoint', async () => {
    const data = { loggedIn: true, username: 'normal', csrfToken: 'test-csrf' }
    const fetchMock = vi.fn().mockResolvedValue({ code: 'OK', data })
    vi.stubGlobal('$fetch', fetchMock)
    expect(await getSession()).toEqual(data)
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/auth/session', {
      method: 'GET', credentials: 'same-origin', retry: 0,
    })
  })

  it('does not report success when session fails', async () => {
    vi.stubGlobal('$fetch', vi.fn().mockResolvedValue({ code: 'FORBIDDEN', message: '拒绝访问', details: [] }))
    await expect(getSession()).rejects.toThrow('拒绝访问')
  })

  it('uses the configured key and makes only one ciphertext POST', async () => {
    const keyPair = await crypto.subtle.generateKey({
      name: 'RSA-OAEP', modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256',
    }, true, ['encrypt', 'decrypt'])
    const publicKey = btoa(String.fromCharCode(
      ...new Uint8Array(await crypto.subtle.exportKey('spki', keyPair.publicKey)),
    ))
    let submittedBody: Record<string, string> | undefined
    const fetchMock = vi.fn(async (_url: string, options?: { body?: Record<string, string> }) => {
      submittedBody = options?.body
      return { code: 'OK', message: '成功', data: {
        loggedIn: true, username: 'owner', csrfToken: 'test-csrf',
      }, details: [] }
    })
    vi.stubGlobal('$fetch', fetchMock)

    const session = await login('owner', 'only-in-memory-password', publicKey)

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/auth/session', expect.objectContaining({
      method: 'POST', credentials: 'same-origin',
    }))
    expect(session.loggedIn).toBe(true)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(submittedBody).toBeDefined()
    expect(Object.keys(submittedBody!)).toEqual(['username', 'encryptedPassword'])
    expect(JSON.stringify(submittedBody)).not.toContain('only-in-memory-password')
    const encryptedPassword = submittedBody?.encryptedPassword
    if (typeof encryptedPassword !== 'string') {
      throw new Error('Missing encrypted password in login request')
    }
    const encryptedBytes = Uint8Array.from(atob(encryptedPassword),
      character => character.charCodeAt(0))
    const decrypted = await crypto.subtle.decrypt({ name: 'RSA-OAEP' }, keyPair.privateKey, encryptedBytes)
    expect(new TextDecoder().decode(decrypted)).toBe('only-in-memory-password')
  })
})
