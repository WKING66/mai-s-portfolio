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
  avatarExpiresAt: string | null
}

export interface UpdateAccountProfileRequest {
  nickname: string | null
}
export interface ChangePasswordRequest {
  oldEncryptedPassword: string
  newEncryptedPassword: string
}

export function validateAvatar(file: File) {
  if (
    !ACCOUNT_RULES.avatarTypes.some((type) => type === file.type) ||
    file.size <= 0 ||
    file.size > ACCOUNT_RULES.avatarMaxBytes
  ) {
    throw new Error(ACCOUNT_MESSAGES.avatarInvalid)
  }
}

/** 与登录使用同一部署公钥；旧密码不规范化，新密码遵循注册规则。 */
export async function buildChangePasswordRequest(
  username: string,
  oldPassword: string,
  newPassword: string,
  confirmation: string,
  publicKey: string,
): Promise<ChangePasswordRequest> {
  // 1. 校验旧密码存在，并按注册规则检查新密码与确认输入。
  if (!oldPassword) {
    throw new Error(ACCOUNT_MESSAGES.passwordRequired)
  }
  const normalizedPassword = newPassword.trim()
  validateRegistrationInput(username, normalizedPassword)
  if (normalizedPassword !== confirmation.trim()) {
    throw new Error(ACCOUNT_MESSAGES.passwordMismatch)
  }
  // 2. 并行加密两份密码；任一加密失败都不发送修改请求。
  const [oldEncryptedPassword, newEncryptedPassword] = await Promise.all([
    encryptLoginPassword(publicKey, oldPassword),
    encryptLoginPassword(publicKey, normalizedPassword),
  ])
  return { oldEncryptedPassword, newEncryptedPassword }
}

/** 所有个人资料请求共用认证、CSRF、401/403 和过期响应拦截。 */
export function useAccountApi() {
  const authState = useAuthState()
  async function getProfile(): Promise<AccountProfileVo> {
    const profile = await authState.request<AccountProfileVo>(
      ACCOUNT_PATHS.profile,
      {},
      AUTH_ACCESS.AUTHENTICATED,
      ACCOUNT_MESSAGES.loadFailed,
    )
    if (!profile) {
      throw new Error(ACCOUNT_MESSAGES.loadFailed)
    }
    return profile
  }
  async function updateProfile(nickname: string): Promise<AccountProfileVo> {
    const normalizedNickname = nickname.trim()
    if (Array.from(normalizedNickname).length > ACCOUNT_RULES.nicknameMaxLength) {
      throw new Error(ACCOUNT_MESSAGES.nicknameTooLong)
    }
    const body: UpdateAccountProfileRequest = { nickname: normalizedNickname || null }
    const profile = await authState.request<AccountProfileVo>(
      ACCOUNT_PATHS.profile,
      { method: 'PUT', body },
      AUTH_ACCESS.AUTHENTICATED,
      ACCOUNT_MESSAGES.saveFailed,
    )
    if (!profile) {
      throw new Error(ACCOUNT_MESSAGES.saveFailed)
    }
    return profile
  }
  async function bindAvatar(assetId: number): Promise<AccountProfileVo> {
    const profile = await authState.request<AccountProfileVo>(
      ACCOUNT_PATHS.avatar,
      { method: 'PUT', body: { assetId } },
      AUTH_ACCESS.AUTHENTICATED,
      ACCOUNT_MESSAGES.avatarFailed,
    )
    if (!profile) {
      throw new Error(ACCOUNT_MESSAGES.avatarFailed)
    }
    return profile
  }
  async function changePassword(
    oldPassword: string,
    newPassword: string,
    confirmation: string,
    publicKey: string,
  ) {
    // 1. 加密准备绑定当前账号世代，避免切换账号后提交旧表单。
    const sessionRevision = authState.sessionRevision()
    const body = await buildChangePasswordRequest(
      authState.state.value.username || '',
      oldPassword,
      newPassword,
      confirmation,
      publicKey,
    )
    if (authState.sessionRevision() !== sessionRevision) {
      throw new ApiRequestError(AUTH_MESSAGES.sessionChanged, null, AUTH_ERROR_CODES.sessionChanged)
    }
    // 2. 世代未变化才提交密文，继续使用统一 CSRF 和权限入口。
    await authState.request<null>(
      ACCOUNT_PATHS.password,
      { method: 'PUT', body },
      AUTH_ACCESS.AUTHENTICATED,
      ACCOUNT_MESSAGES.passwordFailed,
    )
  }
  return { getProfile, updateProfile, bindAvatar, changePassword }
}
