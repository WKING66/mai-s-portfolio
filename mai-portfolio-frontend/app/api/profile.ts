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
  // 1. 公开资料参与 SSR；读取当前应用的缓存并保留其修订号。
  const portfolioStore = usePortfolioStore(useNuxtApp().$pinia)
  const profileRevision = portfolioStore.profileRevision
  const response = useFetch<ApiResponse<SiteProfileResponse>>(PROFILE_PATHS.api, {
    key: PROFILE_PUBLIC_DATA_KEY,
    getCachedData: (key, nuxtApp) => {
      const cachedProfile =
        portfolioStore.profile ?? nuxtApp.payload.data[key] ?? nuxtApp.static.data[key]
      return cachedProfile && assetUrlsFresh(cachedProfile) ? cachedProfile : undefined
    },
  })
  // 2. 签名地址即将到期时刷新业务投影，不修改原始资源身份。
  useAssetUrlRefresh(
    () => response.data.value,
    () => response.refresh(),
  )
  // 3. 当前修订号才可回填缓存；SSR 在 Promise 完成时做同样校验。
  watch(
    response.data,
    (profileResponse) => {
      if (portfolioStore.profileRevision === profileRevision && profileResponse?.code === 'OK') {
        portfolioStore.profile = profileResponse
      }
    },
    { immediate: true },
  )
  return response.then((result) => {
    // SSR watcher 不会持续运行，必须在公开请求完成后把结果写入本请求的 Pinia。
    if (portfolioStore.profileRevision === profileRevision && result.data.value?.code === 'OK') {
      portfolioStore.profile = result.data.value
    }
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
  const authState = useAuthState()
  const nuxtApp = useNuxtApp()
  const managementStore = useManagementStore(nuxtApp.$pinia)
  const portfolioStore = usePortfolioStore(nuxtApp.$pinia)

  async function readProfile(): Promise<SiteProfileResponse> {
    const profile = await authState.request<SiteProfileResponse>(
      PROFILE_PATHS.api,
      {},
      AUTH_ACCESS.OWNER,
      PROFILE_MESSAGES.loadFailed,
    )
    if (!profile) {
      throw new Error(PROFILE_MESSAGES.loadFailed)
    }
    return profile
  }

  function getProfile(force = false) {
    return authState.canAccess(AUTH_ACCESS.OWNER)
      ? managementStore.getProfile(readProfile, force)
      : readProfile()
  }

  async function updateProfile(body: UpdateProfileRequest): Promise<SiteProfileResponse> {
    const profile = await authState.request<SiteProfileResponse>(
      PROFILE_PATHS.api,
      { method: 'PATCH', body },
      AUTH_ACCESS.OWNER,
      PROFILE_MESSAGES.saveFailed,
    )
    if (!profile) {
      throw new Error(PROFILE_MESSAGES.saveFailed)
    }
    managementStore.acceptProfile(profile)
    portfolioStore.invalidateProfile()
    nuxtApp.runWithContext(() => clearNuxtData(PROFILE_PUBLIC_DATA_KEY))
    return profile
  }

  return { getProfile, updateProfile }
}
