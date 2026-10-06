import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import type { ApiResponse } from '../api/types'
import { ref } from 'vue'
import { usePortfolioStore } from '../stores/portfolio'
import { useManagementStore } from '../stores/management'
import { PROFILE_PUBLIC_DATA_KEY } from '../constants/profile'

const mocks = vi.hoisted(() => ({ request: vi.fn(), useFetch: vi.fn(), clearNuxtData: vi.fn(), allowed: true, pinia: null as Pinia | null }))
vi.mock('../composables/useAuthState', () => ({ useAuthState: () => ({ request: mocks.request, canAccess: () => mocks.allowed }) }))
vi.mock('#app', () => {
  return { useFetch: mocks.useFetch, clearNuxtData: mocks.clearNuxtData,
    useNuxtApp: () => ({ $pinia: mocks.pinia, runWithContext: (fn: () => unknown) => fn(), payload: { data: {} }, static: { data: {} } }) }
})
import { usePublicProfile } from '../api/profile'
import { useProjectApi, usePublicProjects, type AdminProject } from '../api/projects'
import { useAdminProfileApi } from '../api/adminProfile'

const envelope = <T>(data: T): ApiResponse<T> => ({ code: 'OK', data, message: '', details: [] })
const project: AdminProject = { id: 1, slug: 'test', title: 'title', summary: 'summary', contribution: 'contribution',
  outcome: null, timeLabel: null, tags: [], links: [], featured: false, sortOrder: 0, status: 'DRAFT',
  version: 0, publishedAt: null, updatedAt: '2026-10-07T00:00:00' }
beforeEach(() => {
  mocks.pinia = createPinia()
  setActivePinia(mocks.pinia)
  mocks.request.mockReset(); mocks.useFetch.mockReset(); mocks.clearNuxtData.mockReset(); mocks.allowed = true
})
function publicRequest(value: unknown) {
  const data = ref<unknown>(null)
  const response = Promise.resolve().then(() => { data.value = value; return { data } })
  return Object.assign(response, { data })
}

describe('API-level Pinia cache behavior', () => {
  it('writes completed SSR public profile into Pinia, not only a pre-fetch watcher', async () => {
    const value = envelope({ displayName: '公开昵称', techStack: [] })
    mocks.useFetch.mockReturnValue(publicRequest(value))
    await usePublicProfile()
    expect(usePortfolioStore().profile).toEqual(value)
    const options = mocks.useFetch.mock.calls[0]![1]
    expect(options.getCachedData(PROFILE_PUBLIC_DATA_KEY, {})).toEqual(value)
  })
  it('falls back to the Nuxt SSR payload during hydration', async () => {
    mocks.useFetch.mockReturnValue(publicRequest(null))
    await usePublicProfile()
    const cached = envelope({ displayName: 'SSR' })
    expect(mocks.useFetch.mock.calls[0]![1].getCachedData(PROFILE_PUBLIC_DATA_KEY,
      { payload: { data: { [PROFILE_PUBLIC_DATA_KEY]: cached } }, static: { data: {} } })).toEqual(cached)
  })
  it('never caches a management page as PUBLIC', async () => {
    mocks.useFetch.mockReturnValue(publicRequest(envelope({ view: 'MANAGE', page: 1, size: 4, items: [project] })))
    await usePublicProjects(1, 4)
    expect(usePortfolioStore().projects).toEqual({})
  })
  it('does not refill public caches from a response started before a mutation', async () => {
    mocks.useFetch.mockReturnValue(publicRequest(envelope({ view: 'PUBLIC', page: 1, size: 4, total: 0, items: [] })))
    const reading = usePublicProjects(1, 4)
    usePortfolioStore().invalidateProjects()
    await reading
    expect(usePortfolioStore().projects).toEqual({})
  })
  it('does not re-fetch shared tags, details or managed lists on route reuse', async () => {
    mocks.request.mockResolvedValueOnce([]).mockResolvedValueOnce(project)
      .mockResolvedValueOnce({ view: 'MANAGE', page: 1, size: 20, total: 1, items: [project] })
    const api = useProjectApi()
    await api.getProjectTags(); await api.getProject(1); await api.getManagedProjects()
    await api.getProjectTags(); await api.getProject(1); await api.getManagedProjects()
    expect(mocks.request).toHaveBeenCalledTimes(3)
    await api.getProject(1, true)
    expect(mocks.request).toHaveBeenCalledTimes(4)
  })
  it('updates detail and invalidates public/list caches after a write', async () => {
    const store = useManagementStore()
    store.lists.all = { view: 'MANAGE', page: 1, size: 20, total: 1, items: [project] }
    const publicStore = usePortfolioStore()
    publicStore.projects.cached = envelope({ view: 'PUBLIC', page: 1, size: 4, total: 0, items: [] })
    mocks.request.mockResolvedValue({ ...project, version: 1 })
    await useProjectApi().changeProjectStatus(1, 0, true)
    expect(store.projects[1]?.version).toBe(1)
    expect(store.lists).toEqual({})
    expect(publicStore.projects).toEqual({})
    expect(mocks.clearNuxtData).toHaveBeenCalled()
  })
  it('reuses public editor snapshots but invalidates the public profile only after successful update', async () => {
    const profile = { displayName: '作者', headline: 'headline', intro: 'intro', githubUrl: null,
      email: null, updatedAt: '2026-10-07T00:00:00', avatarUrl: null, resumeUrl: null }
    mocks.request.mockResolvedValue(profile)
    const api = useAdminProfileApi()
    await api.getProfile(); await api.getProfile()
    expect(mocks.request).toHaveBeenCalledTimes(1)
    usePortfolioStore().profile = envelope({ ...profile, techStack: [] })
    await api.updateProfile(profile)
    expect(usePortfolioStore().profile).toBeNull()
  })
})
