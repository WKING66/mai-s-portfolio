import { afterEach, describe, expect, it, vi } from 'vitest'
import { ACCOUNT_MESSAGES, ACCOUNT_PATHS, ACCOUNT_RULES } from '../constants/account'
import { AUTH_ACCESS } from '../constants/auth'
import { REGISTRATION_MESSAGES } from '../constants/registration'

const { request, encrypt, sessionRevision } = vi.hoisted(() => ({ request: vi.fn(), encrypt: vi.fn(), sessionRevision: vi.fn(() => 0) }))
vi.mock('../composables/useAuthState', () => ({ useAuthState: () => ({
  request, sessionRevision, state: { value: { username: 'visitor' } },
}) }))
vi.mock('../api/loginCrypto', () => ({
  LOGIN_PASSWORD_MAX_UTF8_BYTES: 190,
  encryptLoginPassword: encrypt,
}))
import { useAccountApi, validateAvatar, buildChangePasswordRequest } from '../api/account'

afterEach(() => { request.mockReset(); encrypt.mockReset(); sessionRevision.mockReset().mockReturnValue(0) })
const profile = { username: 'visitor', nickname: '测试昵称', avatarUrl: null }

describe('personal account API', () => {
  it('uses authenticated access, not OWNER, for personal profile', async () => {
    request.mockResolvedValue(profile)
    expect(await useAccountApi().getProfile()).toEqual(profile)
    expect(request).toHaveBeenCalledWith(ACCOUNT_PATHS.profile, {}, AUTH_ACCESS.AUTHENTICATED, ACCOUNT_MESSAGES.loadFailed)
  })
  it('normalizes nickname and sends no account ID or role', async () => {
    request.mockResolvedValue(profile)
    await useAccountApi().updateProfile('  测试昵称  ')
    expect(request).toHaveBeenCalledWith(ACCOUNT_PATHS.profile,
      { method: 'PUT', body: { nickname: '测试昵称' } }, AUTH_ACCESS.AUTHENTICATED, ACCOUNT_MESSAGES.saveFailed)
    await useAccountApi().updateProfile(' ')
    expect(request.mock.calls[1]![1].body).toEqual({ nickname: null })
  })
  it('rejects overlong nickname before dispatch', async () => {
    await expect(useAccountApi().updateProfile('字'.repeat(65))).rejects.toThrow(ACCOUNT_MESSAGES.nicknameTooLong)
    expect(request).not.toHaveBeenCalled()
  })
  it('sends avatar as multipart without hard-coding its boundary', async () => {
    const file = new File(['png'], 'avatar.png', { type: 'image/png' })
    request.mockResolvedValue(profile)
    await useAccountApi().uploadAvatar(file)
    const [path, options, access] = request.mock.calls[0]!
    expect(path).toBe(ACCOUNT_PATHS.avatar)
    expect(access).toBe(AUTH_ACCESS.AUTHENTICATED)
    expect(options.body.get('file').name).toBe('avatar.png')
    expect(options.headers).toBeUndefined()
  })
  it.each([
    new File(['svg'], 'avatar.svg', { type: 'image/svg+xml' }),
    new File([], 'empty.jpg', { type: 'image/jpeg' }),
    new File([new Uint8Array(ACCOUNT_RULES.avatarMaxBytes + 1)], 'big.png', { type: 'image/png' }),
  ])('rejects unsupported or oversized avatar', async (file) => {
    expect(() => validateAvatar(file)).toThrow(ACCOUNT_MESSAGES.avatarInvalid)
    await expect(useAccountApi().uploadAvatar(file)).rejects.toThrow()
    expect(request).not.toHaveBeenCalled()
  })
  it('does not pretend a missing profile is success', async () => {
    request.mockResolvedValue(null)
    await expect(useAccountApi().getProfile()).rejects.toThrow(ACCOUNT_MESSAGES.loadFailed)
  })
})

describe('password change', () => {
  it('keeps old password exact but normalizes the new one before encrypting', async () => {
    encrypt.mockImplementation(async (_key, value) => 'cipher:' + value)
    const body = await buildChangePasswordRequest('visitor', ' old password ', '  new-password!  ', 'new-password!', 'public-key')
    expect(body).toEqual({ oldEncryptedPassword: 'cipher: old password ', newEncryptedPassword: 'cipher:new-password!' })
    expect(Object.keys(body)).toEqual(['oldEncryptedPassword', 'newEncryptedPassword'])
  })
  it.each([
    ['', 'new-password!', 'new-password!', ACCOUNT_MESSAGES.passwordRequired],
    ['old', 'new-password!', 'different!', ACCOUNT_MESSAGES.passwordMismatch],
    ['old', 'short', 'short', REGISTRATION_MESSAGES.passwordTooShort],
    ['old', 'new password!', 'new password!', REGISTRATION_MESSAGES.invalidPasswordCharacters],
  ])('blocks invalid input before encryption', async (old, next, confirmation, message) => {
    await expect(buildChangePasswordRequest('visitor', old, next, confirmation, 'key')).rejects.toThrow(message)
    expect(encrypt).not.toHaveBeenCalled()
  })
  it('does not dispatch plaintext if encryption fails', async () => {
    encrypt.mockRejectedValue(new Error('crypto failed'))
    await expect(useAccountApi().changePassword('old-password!', 'new-password!', 'new-password!', 'key')).rejects.toThrow('crypto failed')
    expect(request).not.toHaveBeenCalled()
  })
  it('does not send an old password form under a session changed during encryption', async () => {
    let resolve!: (cipher: string) => void
    const delayed = new Promise<string>(done => { resolve = done })
    encrypt.mockReturnValue(delayed)
    const operation = useAccountApi().changePassword('old-password!', 'new-password!', 'new-password!', 'key')
    sessionRevision.mockReturnValue(2)
    resolve('cipher')
    await expect(operation).rejects.toMatchObject({ code: 'AUTH_SESSION_CHANGED' })
    expect(request).not.toHaveBeenCalled()
  })
})
