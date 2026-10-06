import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { AdminProfileVo } from '../api/adminProfile'
import type { AdminProject, ProjectPage, ProjectTag } from '../api/projects'

/** 管理快照只在客户端授权后读取；注销/换账号/失去 OWNER 时统一清空。 */
export const useManagementStore = defineStore('management', () => {
  const profile = ref<AdminProfileVo | null>(null)
  const tags = ref<ProjectTag[] | null>(null)
  const projects = ref<Record<number, AdminProject>>({})
  const lists = ref<Record<string, ProjectPage<AdminProject>>>({})
  // 在途 Promise 不放入可序列化 state，且每个 Pinia 实例独立。
  const loading = new Map<string, Promise<unknown>>()
  let revision = 0

  async function read<T>(key: string, cached: T | null | undefined, fetcher: () => Promise<T>, remember: (value: T) => void): Promise<T> {
    if (cached != null) return cached
    if (loading.has(key)) return loading.get(key) as Promise<T>
    const startedAt = revision
    const request = fetcher().then(value => {
      if (revision === startedAt) remember(value)
      return value
    }).finally(() => { if (loading.get(key) === request) loading.delete(key) })
    loading.set(key, request)
    return request
  }

  function getTags(fetcher: () => Promise<ProjectTag[]>) {
    return read('tags', tags.value, fetcher, value => { tags.value = value })
  }
  function getProject(id: number, fetcher: () => Promise<AdminProject>, force = false) {
    if (force) delete projects.value[id]
    return read('project:' + id, projects.value[id], fetcher, value => { projects.value[id] = value })
  }
  function getList(key: string, fetcher: () => Promise<ProjectPage<AdminProject>>, force = false) {
    if (force) delete lists.value[key]
    return read('list:' + key, lists.value[key], fetcher, value => { lists.value[key] = value })
  }
  function getProfile(fetcher: () => Promise<AdminProfileVo>, force = false) {
    if (force) profile.value = null
    return read('profile', profile.value, fetcher, value => { profile.value = value })
  }
  function acceptProject(project: AdminProject) {
    revision += 1
    loading.clear()
    projects.value[project.id] = project
    lists.value = {}
  }
  function acceptProfile(value: AdminProfileVo) { revision += 1; loading.clear(); profile.value = value }
  function clear() {
    revision += 1
    loading.clear()
    profile.value = null; tags.value = null; projects.value = {}; lists.value = {}
  }
  return { profile, tags, projects, lists, getTags, getProject, getList, getProfile, acceptProject, acceptProfile, clear }
})
