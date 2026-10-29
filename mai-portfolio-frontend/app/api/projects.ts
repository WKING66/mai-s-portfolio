import { useAssetUrlRefresh } from '../composables/useAssetUrlRefresh'
import { clearNuxtData, useFetch, useNuxtApp } from '#app'
import { computed, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { usePortfolioStore } from '../stores/portfolio'
import { useManagementStore } from '../stores/management'
import type { ApiResponse } from './types'
import { useAuthState } from '../composables/useAuthState'
import { PROJECT_PATH, PROJECT_MESSAGES } from '../constants/projects'
import { assetUrlsFresh } from '../utils/assets/urls'
import { AUTH_ACCESS } from '../constants/auth'

export interface ProjectTag {
  id: number
  name: string
  slug: string
  group: string
  logoKey: string | null
}
export interface ProjectLink {
  type: 'CODE' | 'DEMO' | 'DOCUMENTATION' | 'OTHER'
  label: string | null
  url: string
  visible: boolean | null
  sortOrder: number
}
export interface PublicProject {
  id: number
  slug: string | null
  title: string
  summary: string
  contribution: string
  outcome: string | null
  timeLabel: string | null
  tags: ProjectTag[]
  links: Pick<ProjectLink, 'type' | 'label' | 'url'>[]
  cover?: { assetId: number; url: string; alt: string | null; expiresAt: string } | null
}
export interface ProjectEditor extends PublicProject {
  links: ProjectLink[]
  status: 'DRAFT' | 'PUBLISHED'
  featured: boolean
  sortOrder: number
  version: number
  publishedAt: string | null
  updatedAt: string
}
export interface ProjectPage<T> {
  view: 'PUBLIC' | 'MANAGE'
  page: number
  size: number
  total: number
  items: T[]
}
export interface ProjectInput {
  version?: number
  slug: string
  title: string
  summary: string
  contribution: string
  outcome: string
  timeLabel: string
  tagIds: number[]
  links: ProjectLink[]
  featured: boolean
  sortOrder: number
  coverMediaId?: number | null
}

export function usePublicProjects(page: MaybeRefOrGetter<number>, size: number) {
  // 明确 PUBLIC，站长 Cookie 不改变查询范围；不与管理列表共用缓存键。
  const portfolioStore = usePortfolioStore(useNuxtApp().$pinia)
  const projectsRevision = portfolioStore.projectsRevision
  const key = () => 'projects:PUBLIC:' + toValue(page) + ':' + size
  const response = useFetch<ApiResponse<ProjectPage<PublicProject>>>(PROJECT_PATH, {
    query: computed(() => ({ view: 'PUBLIC', page: toValue(page), size })),
    key,
    getCachedData: (cacheKey, nuxtApp) => {
      const cachedProjects =
        portfolioStore.projects[cacheKey] ??
        nuxtApp.payload.data[cacheKey] ??
        nuxtApp.static.data[cacheKey]
      return cachedProjects && assetUrlsFresh(cachedProjects) ? cachedProjects : undefined
    },
  })
  useAssetUrlRefresh(
    () => response.data.value,
    () => response.refresh(),
  )
  watch(
    response.data,
    (projectResponse) => {
      if (
        portfolioStore.projectsRevision === projectsRevision &&
        projectResponse?.code === 'OK' &&
        projectResponse.data?.view === 'PUBLIC'
      ) {
        portfolioStore.projects[
          'projects:PUBLIC:' + projectResponse.data.page + ':' + projectResponse.data.size
        ] = projectResponse
      }
    },
    { immediate: true },
  )
  return response.then((requestResult) => {
    const projectResponse = requestResult.data.value
    if (
      portfolioStore.projectsRevision === projectsRevision &&
      projectResponse?.code === 'OK' &&
      projectResponse.data?.view === 'PUBLIC'
    ) {
      portfolioStore.projects[
        'projects:PUBLIC:' + projectResponse.data.page + ':' + projectResponse.data.size
      ] = projectResponse
    }
    return requestResult
  })
}

/** 在 setup 时绑定 Nuxt 会话，事件/await 之后不再尝试获取隐式组件上下文。 */
export function useProjectApi() {
  const authState = useAuthState()
  const nuxtApp = useNuxtApp()
  const managementStore = useManagementStore(nuxtApp.$pinia)
  const portfolioStore = usePortfolioStore(nuxtApp.$pinia)
  async function projectRequest<T>(
    path: string,
    options: Parameters<typeof $fetch>[1] = {},
  ): Promise<T> {
    const requestResult = await authState.request<T>(
      path,
      options,
      AUTH_ACCESS.OWNER,
      PROJECT_MESSAGES.saveFailed,
    )
    if (requestResult === null) {
      throw new Error(PROJECT_MESSAGES.loadFailed)
    }
    return requestResult
  }
  function getManagedProjects(page = 1, status = '', force = false) {
    const fetcher = () =>
      projectRequest<ProjectPage<ProjectEditor>>(PROJECT_PATH, {
        query: { view: 'MANAGE', page, size: 20, ...(status ? { status } : {}) },
      })
    if (!authState.canAccess(AUTH_ACCESS.OWNER)) {
      return fetcher()
    }
    return managementStore.getList(page + ':' + status, fetcher, force)
  }
  function getProject(id: number, force = false) {
    const fetcher = () => projectRequest<ProjectEditor>(PROJECT_PATH + '/' + id)
    return authState.canAccess(AUTH_ACCESS.OWNER)
      ? managementStore.getProject(id, fetcher, force)
      : fetcher()
  }
  function getProjectTags() {
    const fetcher = () => projectRequest<ProjectTag[]>('/api/v1/tags', { query: { kind: 'TECH' } })
    return authState.canAccess(AUTH_ACCESS.OWNER) ? managementStore.getTags(fetcher) : fetcher()
  }
  function accept(project: ProjectEditor) {
    managementStore.acceptProject(project)
    portfolioStore.invalidateProjects()
    nuxtApp.runWithContext(() => clearNuxtData((key) => key.startsWith('projects:PUBLIC:')))
    return project
  }
  async function saveProject(id: number | null, body: ProjectInput) {
    return accept(
      await projectRequest<ProjectEditor>(id === null ? PROJECT_PATH : PROJECT_PATH + '/' + id, {
        method: id === null ? 'POST' : 'PATCH',
        body,
      }),
    )
  }
  async function changeProjectStatus(id: number, version: number, publish: boolean) {
    return accept(
      await projectRequest<ProjectEditor>(
        PROJECT_PATH + '/' + id + (publish ? '/publish' : '/unpublish'),
        {
          method: 'POST',
          body: { version },
        },
      ),
    )
  }
  return { getManagedProjects, getProject, getProjectTags, saveProject, changeProjectStatus }
}
