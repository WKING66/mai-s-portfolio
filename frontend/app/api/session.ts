import { LOGIN_MESSAGES } from '../constants/messages'
import { encryptLoginPassword } from './loginCrypto'
import type { ApiResponse } from './types'

interface LoginChallengeResponse {
  challengeId: string
  publicKey: string
  algorithm: 'RSA-OAEP-256'
  expiresAt: string
}

export interface SessionResponse {
  loggedIn: boolean
  username: string | null
  csrfToken: string | null
}

const SESSION_PATH = '/api/v1/admin/session'

export async function loginOwner(username: string, password: string): Promise<SessionResponse> {
  if (!globalThis.crypto?.subtle) {
    throw new Error(LOGIN_MESSAGES.cryptoUnavailable)
  }
  // 公钥只用于本次提交；密码从不进入 URL、localStorage 或明文请求体。
  const challenge = await $fetch<ApiResponse<LoginChallengeResponse>>(`${SESSION_PATH}/challenge`, {
    credentials: 'same-origin',
    cache: 'no-store',
  })
  if (challenge.code !== 'OK' || !challenge.data || challenge.data.algorithm !== 'RSA-OAEP-256') {
    throw new Error(challenge.message)
  }
  const encryptedPassword = await encryptLoginPassword(challenge.data.publicKey, password)
  const session = await $fetch<ApiResponse<SessionResponse>>(SESSION_PATH, {
    method: 'POST',
    credentials: 'same-origin',
    body: { username, challengeId: challenge.data.challengeId, encryptedPassword },
  })
  if (session.code !== 'OK' || !session.data) {
    throw new Error(session.message)
  }
  return session.data
}
