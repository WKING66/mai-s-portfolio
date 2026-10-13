import { useAssetUrlRefresh } from '../composables/useAssetUrlRefresh'
import { clearNuxtData, useFetch, useNuxtApp } from '#app'
import { watch } from 'vue'
import { usePortfolioStore } from '../stores/portfolio'
import { useManagementStore } from '../stores/management'
import { assetUrlsFresh } from '../utils/assetUrls'
import type { ApiResponse } from './types'
import { useAuthState } from '../composables/useAuthState'
import { AUTH_ACCESS } from '../constants/auth'
import { PROFILE_PATHS, PROFILE_MESSAGES, PROFILE_PUBLIC_DATA_KEY } from '../constants/profile'

export interface TechTagResponse {
  name: string
  slug: string
  group: string
  logoKey: string | null
}

export interface SiteProfileResponse extends UpdateProfileRequest {
  avatarUrl: string | null
  resumeUrl: string | null
  avatarExpiresAt: string | null
  resumeExpiresAt: string | null
  techStack: TechTagResponse[]
}

export function useSiteProfile() {
  // 公开资料参与服务端渲染；页面只通过此处读取统一 API 响应。
  const store = usePortfolioStore(useNuxtApp().$pinia)
  const revision = store.profileRevision
  const response = useFetch<ApiResponse<SiteProfileResponse>>(PROFILE_PATHS.api, {
    key: PROFILE_PUBLIC_DATA_KEY,
    getCachedData: (key, app) => {
      const cached = store.profile ?? app.payload.data[key] ?? app.static.data[key]
      return cached && assetUrlsFresh(cached) ? cached : undefined
    },
  })
  useAssetUrlRefresh(() => response.data.value, () => response.refresh())
  watch(response.data, value => { if (store.profileRevision === revision && value?.code === 'OK') store.profile = value }, { immediate: true })
  return response.then(result => {
    // SSR watcher 不会持续运行，必须在公开请求完成后把结果写入本请求的 Pinia。
    if (store.profileRevision === revision && result.data.value?.code === 'OK') store.profile = result.data.value
    return result
  })
}

export interface UpdateProfileRequest {
  displayName: string
  headline: string
  intro: string
  githubUrl: string | null
  email: string | null
  /** 原样回传服务端 UTC 时间戳，不能经 Date 转换损失微秒精度。 */
  updatedAt: string
}

/** setup 时绑定统一鉴权入口；资料管理不另建角色判断或 CSRF 流程。 */
export function useSiteProfileApi() {
  const auth = useAuthState()
  const app = useNuxtApp()
  const store = useManagementStore(app.$pinia)
  const publicStore = usePortfolioStore(app.$pinia)

  async function readProfile(): Promise<SiteProfileResponse> {
    const profile = await auth.request<SiteProfileResponse>(PROFILE_PATHS.api, {},
      AUTH_ACCESS.OWNER, PROFILE_MESSAGES.loadFailed)
    if (!profile) throw new Error(PROFILE_MESSAGES.loadFailed)
    return profile
  }

  function getProfile(force = false) {
    return auth.canAccess(AUTH_ACCESS.OWNER) ? store.getProfile(readProfile, force) : readProfile()
  }

  async function updateProfile(body: UpdateProfileRequest): Promise<SiteProfileResponse> {
    const profile = await auth.request<SiteProfileResponse>(PROFILE_PATHS.api, { method: 'PATCH', body },
      AUTH_ACCESS.OWNER, PROFILE_MESSAGES.saveFailed)
    if (!profile) throw new Error(PROFILE_MESSAGES.saveFailed)
    store.acceptProfile(profile)
    publicStore.invalidateProfile()
    app.runWithContext(() => clearNuxtData(PROFILE_PUBLIC_DATA_KEY))
    return profile
  }

  return { getProfile, updateProfile }
}
