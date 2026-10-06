import { useAuthState } from '../composables/useAuthState'
import { AUTH_ACCESS } from '../constants/auth'
import { PROFILE_MESSAGES, PROFILE_PATHS } from '../constants/profile'
import { useNuxtApp } from '#app'
import { useManagementStore } from '../stores/management'
import { usePortfolioStore } from '../stores/portfolio'

export interface UpdateProfileRequest {
  displayName: string
  headline: string
  intro: string
  githubUrl: string | null
  email: string | null
  /** 原样回传服务端 UTC 时间戳，不能经 Date 转换损失微秒精度。 */
  updatedAt: string
}

export interface AdminProfileVo extends UpdateProfileRequest {
  avatarUrl: string | null
  resumeUrl: string | null
}

/** setup 时绑定统一鉴权入口；资料管理不另建角色判断或 CSRF 流程。 */
export function useAdminProfileApi() {
  const auth = useAuthState()
  const app = useNuxtApp()
  const store = useManagementStore(app.$pinia)
  const publicStore = usePortfolioStore(app.$pinia)

  async function readProfile(): Promise<AdminProfileVo> {
    const profile = await auth.request<AdminProfileVo>(PROFILE_PATHS.api, {},
      AUTH_ACCESS.OWNER, PROFILE_MESSAGES.loadFailed)
    if (!profile) throw new Error(PROFILE_MESSAGES.loadFailed)
    return profile
  }

  function getProfile(force = false) {
    return auth.canAccess(AUTH_ACCESS.OWNER) ? store.getProfile(readProfile, force) : readProfile()
  }

  async function updateProfile(body: UpdateProfileRequest): Promise<AdminProfileVo> {
    const profile = await auth.request<AdminProfileVo>(PROFILE_PATHS.api, { method: 'PATCH', body },
      AUTH_ACCESS.OWNER, PROFILE_MESSAGES.saveFailed)
    if (!profile) throw new Error(PROFILE_MESSAGES.saveFailed)
    store.acceptProfile(profile)
    publicStore.invalidateProfile()
    return profile
  }

  return { getProfile, updateProfile }
}
