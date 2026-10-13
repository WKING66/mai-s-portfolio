import { describe, expect, it } from 'vitest'
import { encryptLoginPassword, LOGIN_PASSWORD_MAX_UTF8_BYTES } from '../api/loginCrypto'

describe('encryptLoginPassword', () => {
  it('encrypts with RSA-OAEP SHA-256 and remains decryptable by the matching private key', async () => {
    const keyPair = await crypto.subtle.generateKey({
      name: 'RSA-OAEP',
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: 'SHA-256',
    }, true, ['encrypt', 'decrypt'])
    const spki = new Uint8Array(await crypto.subtle.exportKey('spki', keyPair.publicKey))
    const publicKeyBase64 = btoa(String.fromCharCode(...spki))

    const ciphertext = await encryptLoginPassword(publicKeyBase64, '测试密码-strong-2026')
    expect(ciphertext).not.toContain('测试密码-strong-2026')
    const bytes = Uint8Array.from(atob(ciphertext), character => character.charCodeAt(0))
    const plaintext = await crypto.subtle.decrypt({ name: 'RSA-OAEP' }, keyPair.privateKey, bytes)
    expect(new TextDecoder().decode(plaintext)).toBe('测试密码-strong-2026')
  })

  it('rejects passwords too large for RSA-2048 OAEP SHA-256', async () => {
    await expect(encryptLoginPassword('unused-key', 'a'.repeat(LOGIN_PASSWORD_MAX_UTF8_BYTES + 1)))
      .rejects.toThrow('190 字节')
  })

  it('rejects a missing configured public key', async () => {
    await expect(encryptLoginPassword('', 'valid-password')).rejects.toThrow('尚未配置登录公钥')
  })
})
