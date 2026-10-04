import { LOGIN_MESSAGES } from '../constants/messages'
import { encryptLoginPassword } from './loginCrypto'
import type { ApiResponse } from './types'

export interface SessionResponse {
  loggedIn: boolean
  username: string | null
  csrfToken: string | null
}

const SESSION_PATH = '/api/v1/auth/session'

export async function login(username: string, password: string, publicKey: string): Promise<SessionResponse> {
  if (!globalThis.crypto?.subtle) {
    throw new Error(LOGIN_MESSAGES.cryptoUnavailable)
  }
  // 使用部署配置中的公钥，登录只有一次 POST，不再请求挑战或公钥。
  const encryptedPassword = await encryptLoginPassword(publicKey, password)
  const session = await $fetch<ApiResponse<SessionResponse>>(SESSION_PATH, {
    method: 'POST',
    credentials: 'same-origin',
    body: { username, encryptedPassword },
  })
  if (session.code !== 'OK' || !session.data) {
    throw new Error(session.message)
  }
  return session.data
}

/** 页面状态查询不承担授权职责；后台仍由服务端校验。 */
export async function getSession(): Promise<SessionResponse> {
  const response = await $fetch<ApiResponse<SessionResponse>>(SESSION_PATH, {
    method: 'GET', credentials: 'same-origin',
  })
  if (response.code !== 'OK' || !response.data) {
    throw new Error(response.message)
  }
  return response.data
}

export async function logout(csrfToken: string): Promise<void> {
  const response = await $fetch<ApiResponse<null>>(SESSION_PATH, {
    method: 'DELETE', credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken },
  })
  if (response.code !== 'OK') {
    throw new Error(response.message)
  }
}
