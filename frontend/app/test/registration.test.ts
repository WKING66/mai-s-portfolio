import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { register, validateRegistrationInput, type RegisterRequest, type RegistrationVo } from '../api/registration'
import { REGISTRATION_MESSAGES, REGISTRATION_PATHS } from '../constants/registration'
import { LOGIN_MESSAGES } from '../constants/messages'

afterEach(() => vi.unstubAllGlobals())

describe('registration input', () => {
  it('trims the username and accepts its ASCII length boundaries', () => {
    expect(validateRegistrationInput('  A_1  ', 'strong-password')).toBe('A_1')
    const username = 'a'.repeat(62) + '_-'
    expect(validateRegistrationInput(username, 'strong-password')).toBe(username)
  })

  it.each(['ab', 'a'.repeat(65), '中文用户', 'with space', 'name@site', 'name\nuser'])
    ('rejects an invalid username %j', (username) => {
      expect(() => validateRegistrationInput(username, 'strong-password'))
        .toThrow(REGISTRATION_MESSAGES.invalidUsername)
    })

  it('rejects passwords that become empty after trimming', () => {
    expect(() => validateRegistrationInput('visitor', ' '.repeat(12)))
      .toThrow(REGISTRATION_MESSAGES.passwordTooShort)
  })

  it('enforces the RSA length boundary after trimming', () => {
    expect(validateRegistrationInput('visitor', 'a'.repeat(190))).toBe('visitor')
    expect(() => validateRegistrationInput('visitor', 'a'.repeat(191)))
      .toThrow(REGISTRATION_MESSAGES.passwordTooLong)
    expect(validateRegistrationInput('visitor', '  ' + 'a'.repeat(190) + '  ')).toBe('visitor')
  })

  it('checks minimum length after trimming Unicode padding', () => {
    expect(() => validateRegistrationInput('visitor', ' ABCdefghi90 '))
      .toThrow(REGISTRATION_MESSAGES.passwordTooShort)
    expect(validateRegistrationInput('visitor', '\uFEFF\u00A0 test-password-2026 \u3000'))
      .toBe('visitor')
  })

  it.each(['internal password', 'test\tpassword-2026', 'test\npassword-2026',
    'test\u00A0password-2026', 'test\u3000password-2026', 'test\u0000password-2026',
    '中文密码测试-2026', '🔐test-password', 'test-password！'])
    ('rejects non-ASCII characters or internal whitespace %j', (password) => {
      expect(() => validateRegistrationInput('visitor', password))
        .toThrow(REGISTRATION_MESSAGES.invalidPasswordCharacters)
    })

  it('accepts letters, numbers, underscores and all visible ASCII punctuation', () => {
    const visibleAscii = Array.from({ length: 94 }, (_, index) => String.fromCharCode(33 + index)).join('')
    expect(validateRegistrationInput('visitor', visibleAscii)).toBe('visitor')
  })
})

describe('registration API', () => {
  let publicKey: string
  let privateKey: CryptoKey

  beforeAll(async () => {
    const keyPair = await crypto.subtle.generateKey({
      name: 'RSA-OAEP', modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256',
    }, true, ['encrypt', 'decrypt'])
    privateKey = keyPair.privateKey
    publicKey = btoa(String.fromCharCode(
      ...new Uint8Array(await crypto.subtle.exportKey('spki', keyPair.publicKey)),
    ))
  })

  it('trims password padding before encryption without logging in', async () => {
    let submittedBody: RegisterRequest | undefined
    const data: RegistrationVo = { username: 'visitor-01' }
    const fetchMock = vi.fn(async (_url: string, options?: { body?: RegisterRequest }) => {
      submittedBody = options?.body
      return { code: 'OK', message: '成功', data, details: [] }
    })
    vi.stubGlobal('$fetch', fetchMock)
    const password = '\uFEFF\u00A0  Test_password-2026!  \u3000'

    expect(await register('  visitor-01  ', password, publicKey)).toEqual(data)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith(REGISTRATION_PATHS.api, {
      method: 'POST', credentials: 'same-origin', retry: 0,
      body: { username: 'visitor-01', encryptedPassword: expect.any(String) },
    })
    expect(Object.keys(submittedBody!)).toEqual(['username', 'encryptedPassword'])
    expect(JSON.stringify(submittedBody)).not.toContain(password)
    const encryptedBytes = Uint8Array.from(atob(submittedBody!.encryptedPassword),
      character => character.charCodeAt(0))
    const decrypted = await crypto.subtle.decrypt({ name: 'RSA-OAEP' }, privateKey, encryptedBytes)
    expect(new TextDecoder().decode(decrypted)).toBe(password.trim())
  })

  it.each([
    ['ab', 'strong-password', REGISTRATION_MESSAGES.invalidUsername],
    ['visitor', 'short', REGISTRATION_MESSAGES.passwordTooShort],
    ['visitor', 'internal password', REGISTRATION_MESSAGES.invalidPasswordCharacters],
    ['visitor', 'a'.repeat(191), REGISTRATION_MESSAGES.passwordTooLong],
  ])('does not send invalid credentials for %s', async (username, password, message) => {
    const fetchMock = vi.fn()
    vi.stubGlobal('$fetch', fetchMock)
    await expect(register(username, password, publicKey)).rejects.toThrow(message)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('does not send requests when encryption is unavailable or misconfigured', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('$fetch', fetchMock)
    await expect(register('visitor', 'strong-password', '')).rejects.toThrow(LOGIN_MESSAGES.publicKeyMissing)
    await expect(register('visitor', 'strong-password', 'invalid-key')).rejects.toBeInstanceOf(Error)
    vi.stubGlobal('crypto', undefined)
    await expect(register('visitor', 'strong-password', publicKey)).rejects.toThrow(LOGIN_MESSAGES.cryptoUnavailable)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it.each([409, 429])('passes on HTTP %i business errors without retrying', async (statusCode) => {
    const message = statusCode === 409 ? '用户名已存在' : '请稍后重试'
    const fetchMock = vi.fn().mockRejectedValue({
      statusCode, data: { code: 'REJECTED', message, data: null, details: [] },
    })
    vi.stubGlobal('$fetch', fetchMock)
    await expect(register('visitor', 'strong-password', publicKey))
      .rejects.toMatchObject({ message, status: statusCode, code: 'REJECTED' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('does not report a missing result as a successful registration', async () => {
    vi.stubGlobal('$fetch', vi.fn().mockResolvedValue({ code: 'OK', data: null, details: [] }))
    await expect(register('visitor', 'strong-password', publicKey))
      .rejects.toThrow(REGISTRATION_MESSAGES.registerFailed)
  })
})
