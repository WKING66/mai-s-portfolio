import { LOGIN_MESSAGES } from '../constants/messages'

export const LOGIN_PASSWORD_MAX_UTF8_BYTES = 190

export async function encryptLoginPassword(
  publicKeyBase64: string,
  password: string,
): Promise<string> {
  // 1. 验证安全加密环境、公钥与 UTF-8 字节长度；失败时不发送明文。
  if (!globalThis.crypto?.subtle) {
    throw new Error(LOGIN_MESSAGES.cryptoUnavailable)
  }
  if (!publicKeyBase64.trim()) {
    throw new Error(LOGIN_MESSAGES.publicKeyMissing)
  }
  const passwordBytes = new TextEncoder().encode(password)
  if (passwordBytes.length > LOGIN_PASSWORD_MAX_UTF8_BYTES) {
    passwordBytes.fill(0)
    throw new Error(LOGIN_MESSAGES.passwordTooLong)
  }

  // 2. 导入部署公钥并执行 RSA-OAEP SHA-256，加密参数保持与后端一致。
  try {
    const spkiBytes = Uint8Array.from(atob(publicKeyBase64), (character) => character.charCodeAt(0))
    const importedPublicKey = await crypto.subtle.importKey(
      'spki',
      spkiBytes,
      { name: 'RSA-OAEP', hash: 'SHA-256' },
      false,
      ['encrypt'],
    )
    const encryptedPasswordBytes = new Uint8Array(
      await crypto.subtle.encrypt({ name: 'RSA-OAEP' }, importedPublicKey, passwordBytes),
    )
    return btoa(String.fromCharCode(...encryptedPasswordBytes))
  } finally {
    // 3. 无论加密是否成功，都清理密码的字节副本。
    // 只能清理编码后的字节副本；JS 字符串由运行时管理，不能承诺立即从内存消失。
    passwordBytes.fill(0)
  }
}
