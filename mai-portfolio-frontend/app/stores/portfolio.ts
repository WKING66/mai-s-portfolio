import { defineStore } from 'pinia'
import type { SiteProfileResponse } from '../api/profile'
import type { PublicProject, ProjectPage } from '../api/projects'
import type { ApiResponse } from '../api/types'

/** 仅保存公开 API 响应，可用于 SSR；绝不接受管理实体或会话资料。 */
export const usePortfolioStore = defineStore('portfolio', {
  state: () => ({
    profile: null as ApiResponse<SiteProfileResponse> | null,
    projects: {} as Record<string, ApiResponse<ProjectPage<PublicProject>>>,
    profileRevision: 0,
    projectsRevision: 0,
  }),
  actions: {
    // 使旧的公开项目查询失效，下一次读取不能继续回填过期列表。
    invalidateProjects() {
      this.projectsRevision += 1
      this.projects = {}
    },
    // 公开资料和项目各自维护世代，不把管理状态写入公开快照。
    invalidateProfile() {
      this.profileRevision += 1
      this.profile = null
    },
  },
})
