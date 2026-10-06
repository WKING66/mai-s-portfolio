import { computed, ref } from 'vue'
import { useProjectApi, type AdminProject, type ProjectPage } from '../api/projects'
import { ADMIN_PROJECT_STATUS, type AdminProjectFilter } from '../constants/admin'
import { PROJECT_MESSAGES } from '../constants/projects'

/** 管理列表拥有请求快照；筛选或分页失败时不把旧项目伪装成新结果。 */
export function useAdminProjectList() {
  const { getManagedProjects, changeProjectStatus } = useProjectApi()
  const result = ref<ProjectPage<AdminProject> | null>(null)
  const requestedPage = ref(1)
  const filter = ref<AdminProjectFilter>('ALL')
  const pending = ref(false)
  const changingProjectId = ref<number | null>(null)
  const error = ref('')
  const notice = ref('')
  const canPrevious = computed(() => !!result.value && result.value.page > 1)
  const canNext = computed(() => !!result.value && result.value.page * result.value.size < result.value.total)

  async function load(nextPage = requestedPage.value, nextFilter = filter.value, force = false) {
    if (pending.value) return
    pending.value = true
    error.value = ''
    requestedPage.value = nextPage
    filter.value = nextFilter
    result.value = null
    try {
      const status = nextFilter === 'ALL' ? '' : nextFilter
      result.value = await (force ? getManagedProjects(nextPage, status, true) : getManagedProjects(nextPage, status))
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : PROJECT_MESSAGES.loadFailed
    } finally {
      pending.value = false
    }
  }

  async function transition(project: AdminProject) {
    if (pending.value) return
    const publish = project.status === ADMIN_PROJECT_STATUS.DRAFT
    pending.value = true
    changingProjectId.value = project.id
    error.value = ''
    notice.value = ''
    try {
      await changeProjectStatus(project.id, project.version, publish)
      notice.value = publish ? PROJECT_MESSAGES.published : PROJECT_MESSAGES.unpublished
    } catch (cause) {
      // 冲突或校验失败保留原列表，允许重新读取，不自动重试写入。
      error.value = cause instanceof Error ? cause.message : PROJECT_MESSAGES.saveFailed
    } finally {
      pending.value = false
      changingProjectId.value = null
    }
    if (!error.value) await load()
  }

  async function changePage(nextPage: number) {
    if (pending.value || !result.value) return
    // 总数可能被其他操作减少，当前空页仍应能向前退出，不能被新的总页数困住。
    const lastPage = Math.max(result.value.page, 1, Math.ceil(result.value.total / result.value.size))
    if (!Number.isSafeInteger(nextPage) || nextPage < 1 || nextPage > lastPage) return
    await load(nextPage)
  }

  async function changeFilter(nextFilter: AdminProjectFilter) {
    if (pending.value) return
    notice.value = ''
    await load(1, nextFilter)
  }

  function reload() { return load(requestedPage.value, filter.value, true) }

  return { result, filter, pending, changingProjectId, error, notice, canPrevious, canNext,
    load, reload, transition, changePage, changeFilter }
}
