import { LOGIN_MESSAGES } from '../constants/messages'

export const LOGIN_PASSWORD_MAX_UTF8_BYTES = 190

export async function encryptLoginPassword(publicKeyBase64: string, password: string): Promise<string> {
  if (!globalThis.crypto?.subtle) {
    throw new Error(LOGIN_MESSAGES.cryptoUnavailable)
  }
  const passwordBytes = new TextEncoder().encode(password)
  if (passwordBytes.length > LOGIN_PASSWORD_MAX_UTF8_BYTES) {
    passwordBytes.fill(0)
    throw new Error(LOGIN_MESSAGES.passwordTooLong)
  }

  try {
    const publicKeyBytes = Uint8Array.from(atob(publicKeyBase64), character => character.charCodeAt(0))
    const publicKey = await crypto.subtle.importKey(
      'spki', publicKeyBytes, { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['encrypt'],
    )
    const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
      { name: 'RSA-OAEP' }, publicKey, passwordBytes,
    ))
    return btoa(String.fromCharCode(...ciphertext))
  } finally {
    // 只能清理编码后的字节副本；JS 字符串由运行时管理，不能承诺立即从内存消失。
    passwordBytes.fill(0)
  }
}
