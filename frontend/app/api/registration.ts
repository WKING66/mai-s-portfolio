import { REGISTRATION_MESSAGES, REGISTRATION_PATHS, REGISTRATION_RULES } from '../constants/registration'
import { encryptLoginPassword, LOGIN_PASSWORD_MAX_UTF8_BYTES } from './loginCrypto'
import { requestApi } from './request'

/** 注册请求只含规范化的用户名与密文，不携带角色或原始密码。 */
export interface RegisterRequest {
  username: string
  encryptedPassword: string
}

export interface RegistrationVo {
  username: string
}

/** 注册先去首尾空白；只接受可见 ASCII 字符，不删除或替换内部非法字符。 */
export function validateRegistrationInput(username: string, password: string): string {
  const normalizedUsername = username.trim()
  if (normalizedUsername.length < REGISTRATION_RULES.usernameMinLength
    || normalizedUsername.length > REGISTRATION_RULES.usernameMaxLength
    || !/^[A-Za-z0-9_-]+$/.test(normalizedUsername)) {
    throw new Error(REGISTRATION_MESSAGES.invalidUsername)
  }
  const normalizedPassword = password.trim()
  if (normalizedPassword && !/^[\x21-\x7e]+$/.test(normalizedPassword)) {
    throw new Error(REGISTRATION_MESSAGES.invalidPasswordCharacters)
  }
  if (Array.from(normalizedPassword).length < REGISTRATION_RULES.passwordMinCodePoints) {
    throw new Error(REGISTRATION_MESSAGES.passwordTooShort)
  }
  const passwordBytes = new TextEncoder().encode(normalizedPassword)
  const passwordTooLong = passwordBytes.length > LOGIN_PASSWORD_MAX_UTF8_BYTES
  passwordBytes.fill(0)
  if (passwordTooLong) throw new Error(REGISTRATION_MESSAGES.passwordTooLong)
  return normalizedUsername
}

export async function register(username: string, password: string, publicKey: string): Promise<RegistrationVo> {
  const normalizedPassword = password.trim()
  const normalizedUsername = validateRegistrationInput(username, normalizedPassword)
  // 复用部署公钥与 RSA-OAEP SHA-256；加密失败时不发送请求或回退到明文。
  const encryptedPassword = await encryptLoginPassword(publicKey, normalizedPassword)
  const body: RegisterRequest = { username: normalizedUsername, encryptedPassword }
  // 注册是公开写操作，使用公共请求入口；响应不接入会话，也不自动登录。
  const response = await requestApi<RegistrationVo>(REGISTRATION_PATHS.api, {
    method: 'POST', credentials: 'same-origin', body,
  }, REGISTRATION_MESSAGES.registerFailed)
  if (!response.data) throw new Error(REGISTRATION_MESSAGES.registerFailed)
  return response.data
}
