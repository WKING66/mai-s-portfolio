import { useAuthState } from '../composables/useAuthState'
import { AUTH_ACCESS, AUTH_MESSAGES, AUTH_ERROR_CODES } from '../constants/auth'
import { ACCOUNT_MESSAGES, ACCOUNT_PATHS, ACCOUNT_RULES } from '../constants/account'
import { encryptLoginPassword } from './loginCrypto'
import { validateRegistrationInput } from './registration'
import { ApiRequestError } from './request'

export interface AccountProfileVo {
  username: string
  nickname: string | null
  avatarUrl: string | null
}

export interface UpdateAccountProfileRequest { nickname: string | null }
export interface ChangePasswordRequest { oldEncryptedPassword: string; newEncryptedPassword: string }

export function validateAvatar(file: File) {
  if (!ACCOUNT_RULES.avatarTypes.some(type => type === file.type)
    || file.size <= 0 || file.size > ACCOUNT_RULES.avatarMaxBytes) {
    throw new Error(ACCOUNT_MESSAGES.avatarInvalid)
  }
}

/** 与登录使用同一部署公钥；旧密码不规范化，新密码遵循注册规则。 */
export async function buildChangePasswordRequest(username: string, oldPassword: string,
  newPassword: string, confirmation: string, publicKey: string): Promise<ChangePasswordRequest> {
  if (!oldPassword) throw new Error(ACCOUNT_MESSAGES.passwordRequired)
  const normalizedPassword = newPassword.trim()
  validateRegistrationInput(username, normalizedPassword)
  if (normalizedPassword !== confirmation.trim()) throw new Error(ACCOUNT_MESSAGES.passwordMismatch)
  const [oldEncryptedPassword, newEncryptedPassword] = await Promise.all([
    encryptLoginPassword(publicKey, oldPassword), encryptLoginPassword(publicKey, normalizedPassword),
  ])
  return { oldEncryptedPassword, newEncryptedPassword }
}

/** 所有个人资料请求共用认证、CSRF、401/403 和过期响应拦截。 */
export function useAccountApi() {
  const auth = useAuthState()
  async function getProfile(): Promise<AccountProfileVo> {
    const profile = await auth.request<AccountProfileVo>(ACCOUNT_PATHS.profile, {},
      AUTH_ACCESS.AUTHENTICATED, ACCOUNT_MESSAGES.loadFailed)
    if (!profile) throw new Error(ACCOUNT_MESSAGES.loadFailed)
    return profile
  }
  async function updateProfile(nickname: string): Promise<AccountProfileVo> {
    const normalized = nickname.trim()
    if (Array.from(normalized).length > ACCOUNT_RULES.nicknameMaxLength) throw new Error(ACCOUNT_MESSAGES.nicknameTooLong)
    const body: UpdateAccountProfileRequest = { nickname: normalized || null }
    const profile = await auth.request<AccountProfileVo>(ACCOUNT_PATHS.profile, { method: 'PUT', body },
      AUTH_ACCESS.AUTHENTICATED, ACCOUNT_MESSAGES.saveFailed)
    if (!profile) throw new Error(ACCOUNT_MESSAGES.saveFailed)
    return profile
  }
  async function uploadAvatar(file: File): Promise<AccountProfileVo> {
    validateAvatar(file)
    const body = new FormData()
    body.append('file', file)
    // 不指定 multipart Content-Type，让浏览器生成边界。
    const profile = await auth.request<AccountProfileVo>(ACCOUNT_PATHS.avatar, { method: 'POST', body },
      AUTH_ACCESS.AUTHENTICATED, ACCOUNT_MESSAGES.avatarFailed)
    if (!profile) throw new Error(ACCOUNT_MESSAGES.avatarFailed)
    return profile
  }
  async function changePassword(oldPassword: string, newPassword: string, confirmation: string, publicKey: string) {
    const revision = auth.sessionRevision()
    const body = await buildChangePasswordRequest(auth.state.value.username || '', oldPassword,
      newPassword, confirmation, publicKey)
    if (auth.sessionRevision() !== revision) {
      throw new ApiRequestError(AUTH_MESSAGES.sessionChanged, null, AUTH_ERROR_CODES.sessionChanged)
    }
    await auth.request<null>(ACCOUNT_PATHS.password, { method: 'PUT', body },
      AUTH_ACCESS.AUTHENTICATED, ACCOUNT_MESSAGES.passwordFailed)
  }
  return { getProfile, updateProfile, uploadAvatar, changePassword }
}
