import { useFetch } from '#app'
import { computed, toValue, type MaybeRefOrGetter } from 'vue'
import type { ApiResponse } from './types'
import { useAuthState } from '../composables/useAuthState'
import { ADMIN_PROJECT_PATH, PROJECT_LIST_PATH, PROJECT_MESSAGES } from '../constants/projects'
import { AUTH_ACCESS } from '../constants/auth'

export interface ProjectTag { id: number; name: string; slug: string; group: string; logoKey: string | null }
export interface ProjectLink {
  type: 'CODE' | 'DEMO' | 'DOCUMENTATION' | 'OTHER'
  label: string | null; url: string; visible: boolean | null; sortOrder: number
}
export interface PublicProject {
  id: number; slug: string | null; title: string; summary: string; contribution: string
  outcome: string | null; timeLabel: string | null; tags: ProjectTag[]; links: Pick<ProjectLink, 'type' | 'label' | 'url'>[]
}
export interface AdminProject extends PublicProject {
  links: ProjectLink[]
  status: 'DRAFT' | 'PUBLISHED'; featured: boolean; sortOrder: number; version: number
  publishedAt: string | null; updatedAt: string
}
export interface ProjectPage<T> { view: 'PUBLIC' | 'MANAGE'; page: number; size: number; total: number; items: T[] }
export interface ProjectInput {
  version?: number; slug: string; title: string; summary: string; contribution: string; outcome: string
  timeLabel: string; tagIds: number[]; links: ProjectLink[]; featured: boolean; sortOrder: number
}

export function usePublicProjects(page: MaybeRefOrGetter<number>, size: number) {
  // 明确 PUBLIC，站长 Cookie 不改变查询范围；不与管理列表共用缓存键。
  return useFetch<ApiResponse<ProjectPage<PublicProject>>>(PROJECT_LIST_PATH, {
    query: computed(() => ({ view: 'PUBLIC', page: toValue(page), size })),
    key: () => 'projects:PUBLIC:' + toValue(page) + ':' + size,
  })
}

/** 在 setup 时绑定 Nuxt 会话，事件/await 之后不再尝试获取隐式组件上下文。 */
export function useProjectApi() {
  const auth = useAuthState()
  async function projectRequest<T>(path: string, options: Parameters<typeof $fetch>[1] = {}): Promise<T> {
    const result = await auth.request<T>(path, options, AUTH_ACCESS.OWNER, PROJECT_MESSAGES.saveFailed)
    if (result === null) throw new Error(PROJECT_MESSAGES.loadFailed)
    return result
  }
  function getManagedProjects(page = 1, status = '') {
    return projectRequest<ProjectPage<AdminProject>>(PROJECT_LIST_PATH, {
      query: { view: 'MANAGE', page, size: 20, ...(status ? { status } : {}) },
    })
  }
  function getProject(id: number) { return projectRequest<AdminProject>(ADMIN_PROJECT_PATH + '/' + id) }
  function getProjectTags() { return projectRequest<ProjectTag[]>('/api/v1/admin/tags', { query: { kind: 'TECH' } }) }
  function saveProject(id: number | null, body: ProjectInput) {
    return projectRequest<AdminProject>(id === null ? ADMIN_PROJECT_PATH : ADMIN_PROJECT_PATH + '/' + id, {
      method: id === null ? 'POST' : 'PATCH', body,
    })
  }
  function changeProjectStatus(id: number, version: number, publish: boolean) {
    return projectRequest<AdminProject>(ADMIN_PROJECT_PATH + '/' + id + (publish ? '/publish' : '/unpublish'), {
      method: 'POST', body: { version },
    })
  }
  return { getManagedProjects, getProject, getProjectTags, saveProject, changeProjectStatus }
}
