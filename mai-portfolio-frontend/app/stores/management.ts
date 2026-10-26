import { assetUrlsFresh } from '../utils/assetUrls'
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { SiteProfileResponse } from '../api/profile'
import type { ProjectEditor, ProjectPage, ProjectTag } from '../api/projects'

/** 管理快照只在客户端授权后读取；注销/换账号/失去 OWNER 时统一清空。 */
export const useManagementStore = defineStore('management', () => {
  const profileCache = ref<SiteProfileResponse | null>(null)
  const tagCache = ref<ProjectTag[] | null>(null)
  const projectCache = ref<Record<number, ProjectEditor>>({})
  const projectListCache = ref<Record<string, ProjectPage<ProjectEditor>>>({})
  // 在途 Promise 不放入可序列化 state，且每个 Pinia 实例独立。
  const pendingReads = new Map<string, Promise<unknown>>()
  let cacheRevision = 0

  async function readCachedValue<T>(
    key: string,
    cachedValue: T | null | undefined,
    fetchValue: () => Promise<T>,
    rememberValue: (fetchedValue: T) => void,
  ): Promise<T> {
    // 1. 复用地址仍有效的快照，或复用同一键的在途查询。
    if (cachedValue != null && assetUrlsFresh(cachedValue)) {
      return cachedValue
    }
    if (pendingReads.has(key)) {
      return pendingReads.get(key) as Promise<T>
    }
    // 2. 读取绑定缓存世代；保存或清空之后，旧响应不能回填管理快照。
    const startedRevision = cacheRevision
    const pendingRead = fetchValue()
      .then((fetchedValue) => {
        if (cacheRevision === startedRevision) {
          rememberValue(fetchedValue)
        }
        return fetchedValue
      })
      .finally(() => {
        if (pendingReads.get(key) === pendingRead) {
          pendingReads.delete(key)
        }
      })
    pendingReads.set(key, pendingRead)
    return pendingRead
  }

  function getTags(fetchValue: () => Promise<ProjectTag[]>) {
    return readCachedValue('tags', tagCache.value, fetchValue, (fetchedValue) => {
      tagCache.value = fetchedValue
    })
  }
  function getProject(id: number, fetchValue: () => Promise<ProjectEditor>, force = false) {
    if (force) {
      // 缓存是可序列化字典，保留对象引用及严格 delete 的失败行为。
      // eslint-disable-next-line @typescript-eslint/no-dynamic-delete -- 保留既有缓存字典的删除语义
      delete projectCache.value[id]
    }
    return readCachedValue('project:' + id, projectCache.value[id], fetchValue, (fetchedValue) => {
      projectCache.value[id] = fetchedValue
    })
  }
  function getList(
    key: string,
    fetchValue: () => Promise<ProjectPage<ProjectEditor>>,
    force = false,
  ) {
    if (force) {
      // 缓存是可序列化字典，不能用新对象替换或忽略删除失败。
      // eslint-disable-next-line @typescript-eslint/no-dynamic-delete -- 保留既有缓存字典的删除语义
      delete projectListCache.value[key]
    }
    return readCachedValue(
      'list:' + key,
      projectListCache.value[key],
      fetchValue,
      (fetchedValue) => {
        projectListCache.value[key] = fetchedValue
      },
    )
  }
  function getProfile(fetchValue: () => Promise<SiteProfileResponse>, force = false) {
    if (force) {
      profileCache.value = null
    }
    return readCachedValue('profile', profileCache.value, fetchValue, (fetchedValue) => {
      profileCache.value = fetchedValue
    })
  }
  function acceptProject(project: ProjectEditor) {
    // 写入快照优先于更早的查询结果，同时使旧列表和在途缓存回填失效。
    cacheRevision += 1
    pendingReads.clear()
    projectCache.value[project.id] = project
    projectListCache.value = {}
  }
  function acceptProfile(siteProfile: SiteProfileResponse) {
    // 保存后的资料优先于更早的查询结果，旧请求不再回填当前快照。
    cacheRevision += 1
    pendingReads.clear()
    profileCache.value = siteProfile
  }
  function clear() {
    // 注销、换账号或撤销权限时提升世代并清除全部受保护快照。
    cacheRevision += 1
    pendingReads.clear()
    profileCache.value = null
    tagCache.value = null
    projectCache.value = {}
    projectListCache.value = {}
  }
  return {
    profile: profileCache,
    tags: tagCache,
    projects: projectCache,
    lists: projectListCache,
    getTags,
    getProject,
    getList,
    getProfile,
    acceptProject,
    acceptProfile,
    clear,
  }
})
