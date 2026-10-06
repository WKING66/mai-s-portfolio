import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAccountStore } from '../stores/account'
import { useManagementStore } from '../stores/management'
import { usePortfolioStore } from '../stores/portfolio'
import type { AdminProject } from '../api/projects'

const owner = { loggedIn: true, username: 'owner', roles: ['OWNER'], csrfToken: 'test-only' }
const project: AdminProject = { id: 15, slug: 'test', title: '测试', summary: '摘要', contribution: '贡献',
  outcome: null, timeLabel: null, tags: [], links: [], status: 'DRAFT', featured: false,
  sortOrder: 0, version: 4, publishedAt: null, updatedAt: '2026-10-07T00:00:00' }
beforeEach(() => setActivePinia(createPinia()))

describe('Pinia account and content snapshots', () => {
  it('shares the account across callers and preserves profile on same-account confirmation', () => {
    const store = useAccountStore()
    store.setSession(owner)
    store.profile = { username: 'owner', nickname: '昵称', avatarUrl: null }
    store.setSession({ ...owner, csrfToken: 'renewed' })
    expect(useAccountStore().profile?.nickname).toBe('昵称')
    expect(store.ready).toBe(true)
  })
  it('clears profile and its pending state on account switch or logout', () => {
    const store = useAccountStore()
    store.setSession(owner)
    store.profile = { username: 'owner', nickname: '旧账号', avatarUrl: null }
    store.profilePending = true
    const revision = store.profileRevision
    store.setSession({ ...owner, username: 'visitor', roles: [] })
    expect(store.profile).toBeNull()
    expect(store.profilePending).toBe(false)
    expect(store.profileRevision).toBeGreaterThan(revision)
  })
  it('does not share private or public state across SSR Pinia instances', () => {
    const first = createPinia(), second = createPinia()
    useAccountStore(first).setSession(owner)
    useManagementStore(first).acceptProject(project)
    expect(useAccountStore(second).session.loggedIn).toBe(false)
    expect(useManagementStore(second).projects).toEqual({})
    usePortfolioStore(first).projects.test = { code: 'OK', message: '', data: { view: 'PUBLIC', page: 1, size: 4, total: 0, items: [] }, details: [] }
    expect(usePortfolioStore(second).projects).toEqual({})
  })
  it('reuses technical tags and deduplicates concurrent reads', async () => {
    const store = useManagementStore()
    const fetcher = vi.fn().mockResolvedValue([{ id: 1, name: 'Java', slug: 'java', group: 'LANGUAGE', logoKey: null }])
    await Promise.all([store.getTags(fetcher), store.getTags(fetcher)])
    await store.getTags(fetcher)
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
  it('reuses project details unless explicitly reloaded', async () => {
    const store = useManagementStore(), fetcher = vi.fn().mockResolvedValue(project)
    await store.getProject(15, fetcher)
    await store.getProject(15, fetcher)
    expect(fetcher).toHaveBeenCalledTimes(1)
    await store.getProject(15, fetcher, true)
    expect(fetcher).toHaveBeenCalledTimes(2)
  })
  it('keeps writes and clears cached lists without discarding unrelated tags', () => {
    const store = useManagementStore()
    store.tags = []
    store.lists.all = { view: 'MANAGE', page: 1, size: 20, total: 1, items: [project] }
    store.acceptProject({ ...project, version: 5 })
    expect(store.projects[15]?.version).toBe(5)
    expect(store.lists).toEqual({})
    expect(store.tags).toEqual([])
  })
  it('does not repopulate private caches when an older read finishes after clearing', async () => {
    const store = useManagementStore()
    let finish!: (value: AdminProject) => void
    const reading = store.getProject(15, () => new Promise(resolve => { finish = resolve }))
    store.clear()
    finish(project)
    await reading
    expect(store.projects).toEqual({})
  })
  it('does not let an older read overwrite a newer saved snapshot', async () => {
    const store = useManagementStore()
    let finish!: (value: AdminProject) => void
    const reading = store.getProject(15, () => new Promise(resolve => { finish = resolve }))
    store.acceptProject({ ...project, version: 5 })
    finish(project)
    await reading
    expect(store.projects[15]?.version).toBe(5)
  })
  it('retries failed reads and never caches a failure', async () => {
    const store = useManagementStore(), fetcher = vi.fn().mockRejectedValueOnce(new Error('failed')).mockResolvedValue([])
    await expect(store.getTags(fetcher)).rejects.toThrow('failed')
    expect(store.tags).toBeNull()
    await store.getTags(fetcher)
    expect(store.tags).toEqual([])
  })
})
