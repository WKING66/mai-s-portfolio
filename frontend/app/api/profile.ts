import { useFetch, useNuxtApp } from '#app'
import { watch } from 'vue'
import { usePortfolioStore } from '../stores/portfolio'
import type { ApiResponse } from './types'
import { PROFILE_PUBLIC_DATA_KEY } from '../constants/profile'

export interface TechTagResponse {
  name: string
  slug: string
  group: string
  logoKey: string | null
}

export interface PublicProfileResponse {
  displayName: string
  headline: string
  intro: string
  githubUrl: string | null
  email: string | null
  avatarUrl: string | null
  resumeUrl: string | null
  techStack: TechTagResponse[]
}

export function usePublicProfile() {
  // 公开资料参与服务端渲染；页面只通过此处读取统一 API 响应。
  const store = usePortfolioStore(useNuxtApp().$pinia)
  const revision = store.profileRevision
  const response = useFetch<ApiResponse<PublicProfileResponse>>('/api/v1/public/profile', {
    key: PROFILE_PUBLIC_DATA_KEY,
    getCachedData: (key, app) => store.profile ?? app.payload.data[key] ?? app.static.data[key],
  })
  watch(response.data, value => { if (store.profileRevision === revision && value?.code === 'OK') store.profile = value }, { immediate: true })
  return response.then(result => {
    // SSR watcher 不会持续运行，必须在公开请求完成后把结果写入本请求的 Pinia。
    if (store.profileRevision === revision && result.data.value?.code === 'OK') store.profile = result.data.value
    return result
  })
}
