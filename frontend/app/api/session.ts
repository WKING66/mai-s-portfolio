import { LOGIN_MESSAGES } from '../constants/messages'
import { encryptLoginPassword } from './loginCrypto'
import { requestApi } from './request'

export interface SessionResponse {
  loggedIn: boolean
  username: string | null
  csrfToken: string | null
  roles: string[]
}

const SESSION_PATH = '/api/v1/auth/session'

export async function login(username: string, password: string, publicKey: string): Promise<SessionResponse> {
  if (!globalThis.crypto?.subtle) {
    throw new Error(LOGIN_MESSAGES.cryptoUnavailable)
  }
  // 使用部署配置中的公钥，登录只有一次 POST，不再请求挑战或公钥。
  const encryptedPassword = await encryptLoginPassword(publicKey, password)
  const session = await requestApi<SessionResponse>(SESSION_PATH, {
    method: 'POST',
    credentials: 'same-origin',
    body: { username, encryptedPassword },
  }, LOGIN_MESSAGES.loginFailed)
  if (!session.data) {
    throw new Error(LOGIN_MESSAGES.loginFailed)
  }
  return session.data
}

/** 页面状态查询不承担授权职责；后台仍由服务端校验。 */
export async function getSession(): Promise<SessionResponse> {
  const response = await requestApi<SessionResponse>(SESSION_PATH, {
    method: 'GET', credentials: 'same-origin',
  }, LOGIN_MESSAGES.sessionFailed)
  if (!response.data) {
    throw new Error(LOGIN_MESSAGES.sessionFailed)
  }
  return response.data
}

export async function logout(csrfToken: string): Promise<void> {
  await requestApi<null>(SESSION_PATH, {
    method: 'DELETE', credentials: 'same-origin',
    headers: { 'X-CSRF-Token': csrfToken },
  }, LOGIN_MESSAGES.logoutFailed)
}
