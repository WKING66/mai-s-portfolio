import { clearNuxtData, useFetch, useNuxtApp } from '#app'
import { computed, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { usePortfolioStore } from '../stores/portfolio'
import { useManagementStore } from '../stores/management'
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
  cover?: { assetId: number; url: string; alt: string | null } | null
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
  coverMediaId?: number | null
}

export function usePublicProjects(page: MaybeRefOrGetter<number>, size: number) {
  // 明确 PUBLIC，站长 Cookie 不改变查询范围；不与管理列表共用缓存键。
  const store = usePortfolioStore(useNuxtApp().$pinia)
  const revision = store.projectsRevision
  const key = () => 'projects:PUBLIC:' + toValue(page) + ':' + size
  const response = useFetch<ApiResponse<ProjectPage<PublicProject>>>(PROJECT_LIST_PATH, {
    query: computed(() => ({ view: 'PUBLIC', page: toValue(page), size })),
    key,
    getCachedData: (cacheKey, app) => store.projects[cacheKey] ?? app.payload.data[cacheKey] ?? app.static.data[cacheKey],
  })
  watch(response.data, value => {
    if (store.projectsRevision === revision && value?.code === 'OK' && value.data?.view === 'PUBLIC') {
      store.projects['projects:PUBLIC:' + value.data.page + ':' + value.data.size] = value
    }
  }, { immediate: true })
  return response.then(result => {
    const value = result.data.value
    if (store.projectsRevision === revision && value?.code === 'OK' && value.data?.view === 'PUBLIC') {
      store.projects['projects:PUBLIC:' + value.data.page + ':' + value.data.size] = value
    }
    return result
  })
}

/** 在 setup 时绑定 Nuxt 会话，事件/await 之后不再尝试获取隐式组件上下文。 */
export function useProjectApi() {
  const auth = useAuthState()
  const app = useNuxtApp()
  const store = useManagementStore(app.$pinia)
  const publicStore = usePortfolioStore(app.$pinia)
  async function projectRequest<T>(path: string, options: Parameters<typeof $fetch>[1] = {}): Promise<T> {
    const result = await auth.request<T>(path, options, AUTH_ACCESS.OWNER, PROJECT_MESSAGES.saveFailed)
    if (result === null) throw new Error(PROJECT_MESSAGES.loadFailed)
    return result
  }
  function getManagedProjects(page = 1, status = '', force = false) {
    const fetcher = () => projectRequest<ProjectPage<AdminProject>>(PROJECT_LIST_PATH, {
      query: { view: 'MANAGE', page, size: 20, ...(status ? { status } : {}) },
    })
    if (!auth.canAccess(AUTH_ACCESS.OWNER)) return fetcher()
    return store.getList(page + ':' + status, fetcher, force)
  }
  function getProject(id: number, force = false) {
    const fetcher = () => projectRequest<AdminProject>(ADMIN_PROJECT_PATH + '/' + id)
    return auth.canAccess(AUTH_ACCESS.OWNER) ? store.getProject(id, fetcher, force) : fetcher()
  }
  function getProjectTags() {
    const fetcher = () => projectRequest<ProjectTag[]>('/api/v1/admin/tags', { query: { kind: 'TECH' } })
    return auth.canAccess(AUTH_ACCESS.OWNER) ? store.getTags(fetcher) : fetcher()
  }
  function accept(project: AdminProject) {
    store.acceptProject(project)
    publicStore.invalidateProjects()
    app.runWithContext(() => clearNuxtData(key => key.startsWith('projects:PUBLIC:')))
    return project
  }
  async function saveProject(id: number | null, body: ProjectInput) {
    return accept(await projectRequest<AdminProject>(id === null ? ADMIN_PROJECT_PATH : ADMIN_PROJECT_PATH + '/' + id, {
      method: id === null ? 'POST' : 'PATCH', body,
    }))
  }
  async function changeProjectStatus(id: number, version: number, publish: boolean) {
    return accept(await projectRequest<AdminProject>(ADMIN_PROJECT_PATH + '/' + id + (publish ? '/publish' : '/unpublish'), {
      method: 'POST', body: { version },
    }))
  }
  async function uploadProjectCover(file: File) {
    const body = new FormData()
    body.append('file', file)
    // 不手写 Content-Type，浏览器生成 multipart boundary；复用 OWNER/CSRF/错误拦截。
    return projectRequest<{ id: number; previewUrl: string }>('/api/v1/admin/assets/images', { method: 'POST', body })
  }
  return { getManagedProjects, getProject, getProjectTags, saveProject, changeProjectStatus, uploadProjectCover }
}
