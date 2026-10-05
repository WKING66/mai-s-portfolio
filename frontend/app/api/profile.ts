import { useFetch } from '#app'
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
  return useFetch<ApiResponse<PublicProfileResponse>>('/api/v1/public/profile', { key: PROFILE_PUBLIC_DATA_KEY })
}
