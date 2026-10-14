import { computed, ref } from 'vue'
import { useProjectApi, type ProjectEditor, type ProjectPage } from '../api/projects'
import { ADMIN_PROJECT_STATUS, type AdminProjectFilter } from '../constants/admin'
import { PROJECT_MESSAGES } from '../constants/projects'
import { useFeedback } from './useFeedback'

/** 管理列表拥有请求快照；筛选或分页失败时不把旧项目伪装成新结果。 */
export function useAdminProjectList() {
  const { getManagedProjects, changeProjectStatus } = useProjectApi()
  const feedback = useFeedback()
  const projectPage = ref<ProjectPage<ProjectEditor> | null>(null)
  const requestedPage = ref(1)
  const statusFilter = ref<AdminProjectFilter>('ALL')
  const isPending = ref(false)
  const changingProjectId = ref<number | null>(null)
  const canGoToPreviousPage = computed(() => !!projectPage.value && projectPage.value.page > 1)
  const canGoToNextPage = computed(
    () =>
      !!projectPage.value &&
      projectPage.value.page * projectPage.value.size < projectPage.value.total,
  )

  async function load(
    nextPage = requestedPage.value,
    nextFilter = statusFilter.value,
    force = false,
  ) {
    // 1. 在途操作互斥；先记录本次筛选和页码，不把旧列表展示为新结果。
    if (isPending.value) {
      return
    }
    isPending.value = true
    requestedPage.value = nextPage
    statusFilter.value = nextFilter
    projectPage.value = null
    try {
      // 2. 查询成功才设置当前结果，失败保留目标页码以便重试。
      const status = nextFilter === 'ALL' ? '' : nextFilter
      projectPage.value = await (force
        ? getManagedProjects(nextPage, status, true)
        : getManagedProjects(nextPage, status))
    } catch (error) {
      feedback.error(error, PROJECT_MESSAGES.loadFailed)
    } finally {
      isPending.value = false
    }
  }

  async function transition(project: ProjectEditor) {
    if (isPending.value) {
      return
    }
    // 1. 根据当前发布状态选择操作，并锁定本次变更的项目。
    const shouldPublish = project.status === ADMIN_PROJECT_STATUS.DRAFT
    isPending.value = true
    changingProjectId.value = project.id
    try {
      // 2. 携带已有版本提交；冲突不会自动重放写请求。
      await changeProjectStatus(project.id, project.version, shouldPublish)
      feedback.success(shouldPublish ? PROJECT_MESSAGES.published : PROJECT_MESSAGES.unpublished)
    } catch (error) {
      // 冲突或校验失败保留原列表，允许重新读取，不自动重试写入。
      feedback.error(error, PROJECT_MESSAGES.saveFailed)
      return
    } finally {
      isPending.value = false
      changingProjectId.value = null
    }
    // 3. 写入成功且互斥锁释放后重新读取列表；失败分支提前返回。
    await load()
  }

  async function changePage(nextPage: number) {
    if (isPending.value || !projectPage.value) {
      return
    }
    // 总数可能被其他操作减少，当前空页仍应能向前退出，不能被新的总页数困住。
    const lastPage = Math.max(
      projectPage.value.page,
      1,
      Math.ceil(projectPage.value.total / projectPage.value.size),
    )
    if (!Number.isSafeInteger(nextPage) || nextPage < 1 || nextPage > lastPage) {
      return
    }
    await load(nextPage)
  }

  async function changeFilter(nextFilter: AdminProjectFilter) {
    if (isPending.value) {
      return
    }
    await load(1, nextFilter)
  }

  function reload() {
    return load(requestedPage.value, statusFilter.value, true)
  }

  return {
    result: projectPage,
    filter: statusFilter,
    pending: isPending,
    changingProjectId,
    canPrevious: canGoToPreviousPage,
    canNext: canGoToNextPage,
    load,
    reload,
    transition,
    changePage,
    changeFilter,
  }
}
