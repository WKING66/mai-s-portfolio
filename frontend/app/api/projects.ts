import { useFetch, navigateTo } from '#app'
import { computed, toValue, type MaybeRefOrGetter } from 'vue'
import type { ApiResponse } from './types'
import { ApiRequestError, requestApi } from './request'
import { useAuthState } from '../composables/useAuthState'
import { ADMIN_PROJECT_PATH, PROJECT_LIST_PATH, PROJECT_MESSAGES } from '../constants/projects'

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

export async function ownerRequest<T>(path: string, options: Parameters<typeof $fetch>[1] = {}): Promise<T> {
  const auth = useAuthState()
  if (!auth.isOwner.value) throw new Error(PROJECT_MESSAGES.forbidden)
  try {
    const response = await requestApi<T>(path, {
      ...options, credentials: 'same-origin',
      headers: { ...options.headers, 'X-CSRF-Token': auth.state.value.csrfToken || '' },
    }, PROJECT_MESSAGES.saveFailed)
    return response.data as T
  } catch (error) {
    if (error instanceof ApiRequestError) {
      if (error.status === 401) {
        auth.clear()
        await navigateTo('/login?returnTo=/admin/projects')
      } else if (error.status === 403) {
        await auth.refresh().catch(() => auth.clear())
        if (!auth.state.value.loggedIn) await navigateTo('/login')
        else if (!auth.isOwner.value) await navigateTo('/forbidden')
        else if (error.code === 'CSRF_INVALID') throw new Error(PROJECT_MESSAGES.csrf)
      }
    }
    throw error
  }
}
export function getManagedProjects(page = 1, status = '') {
  return ownerRequest<ProjectPage<AdminProject>>(PROJECT_LIST_PATH, {
    query: { view: 'MANAGE', page, size: 20, ...(status ? { status } : {}) },
  })
}
export function getProject(id: number) { return ownerRequest<AdminProject>(ADMIN_PROJECT_PATH + '/' + id) }
export function getProjectTags() { return ownerRequest<ProjectTag[]>('/api/v1/admin/tags', { query: { kind: 'TECH' } }) }
export function saveProject(id: number | null, body: ProjectInput) {
  return ownerRequest<AdminProject>(id === null ? ADMIN_PROJECT_PATH : ADMIN_PROJECT_PATH + '/' + id, {
    method: id === null ? 'POST' : 'PATCH', body,
  })
}
export function changeProjectStatus(id: number, version: number, publish: boolean) {
  return ownerRequest<AdminProject>(ADMIN_PROJECT_PATH + '/' + id + (publish ? '/publish' : '/unpublish'), {
    method: 'POST', body: { version },
  })
}
