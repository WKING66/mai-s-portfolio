import { LOGIN_MESSAGES } from '../constants/messages'
import { encryptLoginPassword } from './loginCrypto'
import { requestApi } from './request'
import { AUTH_PATHS } from '../constants/auth'

export interface SessionResponse {
  loggedIn: boolean
  username: string | null
  csrfToken: string | null
  roles: string[]
}

export async function login(
  username: string,
  password: string,
  publicKey: string,
): Promise<SessionResponse> {
  // 1. 确认浏览器支持加密，再使用部署公钥准备登录密文。
  if (!globalThis.crypto?.subtle) {
    throw new Error(LOGIN_MESSAGES.cryptoUnavailable)
  }
  // 使用部署配置中的公钥，登录只有一次 POST，不再请求挑战或公钥。
  const encryptedPassword = await encryptLoginPassword(publicKey, password)
  // 2. 只发送一次登录 POST，保留服务端返回的会话快照。
  const loginResponse = await requestApi<SessionResponse>(
    AUTH_PATHS.session,
    {
      method: 'POST',
      credentials: 'same-origin',
      body: { username, encryptedPassword },
    },
    LOGIN_MESSAGES.loginFailed,
  )
  // 3. 空会话响应不能被视为登录成功。
  if (!loginResponse.data) {
    throw new Error(LOGIN_MESSAGES.loginFailed)
  }
  return loginResponse.data
}

/** 页面状态查询不承担授权职责；后台仍由服务端校验。 */
export async function getSession(): Promise<SessionResponse> {
  const response = await requestApi<SessionResponse>(
    AUTH_PATHS.session,
    {
      method: 'GET',
      credentials: 'same-origin',
    },
    LOGIN_MESSAGES.sessionFailed,
  )
  if (!response.data) {
    throw new Error(LOGIN_MESSAGES.sessionFailed)
  }
  return response.data
}
