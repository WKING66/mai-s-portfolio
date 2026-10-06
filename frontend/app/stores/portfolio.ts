import { defineStore } from 'pinia'
import type { PublicProfileResponse } from '../api/profile'
import type { PublicProject, ProjectPage } from '../api/projects'
import type { ApiResponse } from '../api/types'

/** 仅保存公开 API 响应，可用于 SSR；绝不接受管理实体或会话资料。 */
export const usePortfolioStore = defineStore('portfolio', {
  state: () => ({
    profile: null as ApiResponse<PublicProfileResponse> | null,
    projects: {} as Record<string, ApiResponse<ProjectPage<PublicProject>>>,
    profileRevision: 0,
    projectsRevision: 0,
  }),
  actions: {
    invalidateProjects() { this.projectsRevision += 1; this.projects = {} },
    invalidateProfile() { this.profileRevision += 1; this.profile = null },
  },
})
