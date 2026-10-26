import { computed, reactive, ref } from 'vue'
import { useAssetUrlRefresh } from './useAssetUrlRefresh'
import { useAssetApi } from '../api/assets'
import {
  useProjectApi,
  type ProjectEditor,
  type ProjectInput,
  type ProjectTag,
} from '../api/projects'
import { ADMIN_PROJECT_DEFAULT_LINK_TYPE, ADMIN_PROJECT_STATUS } from '../constants/admin'
import { PROJECT_MESSAGES, PROJECT_COVER_MAX_BYTES } from '../constants/projects'
import { useFeedback } from './useFeedback'

/** 编辑器维护完整版本快照；失败时保留输入，不绕过统一请求拦截器。 */
export function useProjectEditor(projectId?: number) {
  const { getProject, getProjectTags, saveProject, changeProjectStatus } = useProjectApi()
  const assetApi = useAssetApi()
  const feedback = useFeedback()
  const coverPreview = ref<string | null>(null)
  const coverExpiresAt = ref<string | null>(null)
  const savedProjectId = ref(projectId ?? null)
  const projectForm = reactive<ProjectInput>({
    slug: '',
    title: '',
    summary: '',
    contribution: '',
    outcome: '',
    timeLabel: '',
    tagIds: [],
    links: [],
    featured: false,
    sortOrder: 0,
    coverMediaId: null,
  })
  const projectTags = ref<ProjectTag[]>([])
  const projectStatus = ref<ProjectEditor['status']>(ADMIN_PROJECT_STATUS.DRAFT)
  const wasPreviouslyPublished = ref(false)
  const isLoaded = ref(false)
  const activeOperation = ref<'load' | 'save' | 'publish' | 'unpublish' | 'upload' | null>(null)
  const isPending = computed(() => activeOperation.value !== null)
  const savedFormSnapshot = ref('')
  const hasUnsavedChanges = computed(
    () => isLoaded.value && JSON.stringify(projectForm) !== savedFormSnapshot.value,
  )
  const canEditProject = computed(() => isLoaded.value && !isPending.value)

  function applyProjectSnapshot(project: ProjectEditor) {
    // 1. 保留表单引用并克隆集合，避免编辑输入反向修改响应快照。
    Object.assign(projectForm, {
      version: project.version,
      slug: project.slug ?? '',
      title: project.title,
      summary: project.summary,
      contribution: project.contribution,
      outcome: project.outcome ?? '',
      timeLabel: project.timeLabel ?? '',
      tagIds: project.tags.map((tag) => tag.id),
      links: project.links.map((link) => ({ ...link })),
      featured: project.featured,
      sortOrder: project.sortOrder,
      coverMediaId: project.cover?.assetId ?? null,
    })
    // 2. 同步版本、发布和封面状态，再记录用于未保存判断的完整表单快照。
    projectStatus.value = project.status
    coverPreview.value = project.cover?.url ?? null
    coverExpiresAt.value = project.cover?.expiresAt ?? null
    wasPreviouslyPublished.value = project.publishedAt !== null
    savedFormSnapshot.value = JSON.stringify(projectForm)
  }

  async function load(force = false) {
    if (isPending.value) {
      return
    }
    // 1. 设置互斥状态；两项只读请求都成功后才启用编辑。
    activeOperation.value = 'load'
    try {
      // 初次失败同样允许重试；两个只读请求完成后才启用编辑器。
      const currentProjectId = savedProjectId.value
      // 2. 先发起标签查询，再按已有项目及强制刷新要求准备详情查询，保持并行。
      const tagsRequest = getProjectTags()
      let projectRequest: Promise<ProjectEditor | null>
      if (!currentProjectId) {
        projectRequest = Promise.resolve(null)
      } else if (force) {
        projectRequest = getProject(currentProjectId, true)
      } else {
        projectRequest = getProject(currentProjectId)
      }
      const [loadedTags, project] = await Promise.all([tagsRequest, projectRequest])
      projectTags.value = loadedTags
      if (project) {
        applyProjectSnapshot(project)
      } else {
        savedFormSnapshot.value = JSON.stringify(projectForm)
      }
      // 3. 响应完整后记录干净快照；失败仍保留输入，finally 释放操作锁。
      isLoaded.value = true
    } catch (error) {
      feedback.error(error, PROJECT_MESSAGES.loadFailed)
    } finally {
      activeOperation.value = null
    }
  }

  async function save(): Promise<number | null> {
    if (!canEditProject.value) {
      return null
    }
    // 1. 仅已加载且空闲时保存；保留现有版本和全部未保存字段。
    activeOperation.value = 'save'
    try {
      // 2. 克隆集合，防止请求与响应快照共享可编辑引用。
      const project = await saveProject(savedProjectId.value, {
        ...projectForm,
        tagIds: [...projectForm.tagIds],
        links: projectForm.links.map((link) => ({ ...link })),
      })
      applyProjectSnapshot(project)
      // 3. 新建完成后的路由切换可能尚未结束，再次点击只能更新同一项目，不能重复创建。
      savedProjectId.value = project.id
      feedback.success(PROJECT_MESSAGES.saved)
      return project.id
    } catch (error) {
      feedback.error(error, PROJECT_MESSAGES.saveFailed)
      return null
    } finally {
      activeOperation.value = null
    }
  }

  async function reload(confirmDiscard: () => boolean) {
    if (isPending.value || (hasUnsavedChanges.value && !confirmDiscard())) {
      return
    }
    await load(true)
  }

  async function transition(publish: boolean) {
    // 1. 发布/下架只能针对已保存内容；此限制不代替 OWNER 鉴权。
    if (
      !canEditProject.value ||
      hasUnsavedChanges.value ||
      !projectId ||
      projectForm.version === undefined
    ) {
      return
    }
    activeOperation.value = publish ? 'publish' : 'unpublish'
    try {
      // 2. 使用已保存版本执行状态变更，成功后才应用新快照。
      applyProjectSnapshot(await changeProjectStatus(projectId, projectForm.version, publish))
      feedback.success(publish ? PROJECT_MESSAGES.published : PROJECT_MESSAGES.unpublished)
    } catch (error) {
      feedback.error(error, PROJECT_MESSAGES.saveFailed)
    } finally {
      activeOperation.value = null
    }
  }

  function setTag(id: number, selected: boolean) {
    if (!canEditProject.value) {
      return
    }
    if (selected && !projectForm.tagIds.includes(id)) {
      projectForm.tagIds.push(id)
    }
    if (!selected) {
      projectForm.tagIds = projectForm.tagIds.filter((tagId) => tagId !== id)
    }
  }

  function addLink() {
    if (!canEditProject.value) {
      return
    }
    projectForm.links.push({
      type: ADMIN_PROJECT_DEFAULT_LINK_TYPE,
      label: '',
      url: '',
      visible: true,
      sortOrder: projectForm.links.length,
    })
  }

  function removeLink(index: number) {
    if (canEditProject.value) {
      projectForm.links.splice(index, 1)
    }
  }

  async function uploadCover(file: File) {
    // 1. 空闲时校验封面格式和大小，拒绝非法文件且保留原封面。
    if (!canEditProject.value) {
      return
    }
    if (
      !['image/png', 'image/jpeg'].includes(file.type) ||
      file.size === 0 ||
      file.size > PROJECT_COVER_MAX_BYTES
    ) {
      feedback.error(PROJECT_MESSAGES.coverInvalid)
      return
    }
    activeOperation.value = 'upload'
    try {
      // 2. 上传只取得资源投影；业务绑定随之后的项目保存完成。
      const uploadedCover = await assetApi.upload(file, 'IMAGE')
      projectForm.coverMediaId = uploadedCover.id
      coverPreview.value = uploadedCover.resourceUrl
      coverExpiresAt.value = uploadedCover.expiresAt
      feedback.success(PROJECT_MESSAGES.coverUploaded)
    } catch (error) {
      // 上传失败保留之前的封面和其他未保存输入，不伪装为成功、不清空整个表单。
      feedback.error(error, PROJECT_MESSAGES.coverFailed)
    } finally {
      activeOperation.value = null
    }
  }

  function removeCover() {
    if (!canEditProject.value) {
      return
    }
    projectForm.coverMediaId = null
    coverPreview.value = null
    coverExpiresAt.value = null
  }

  useAssetUrlRefresh(
    () => ({ expiresAt: coverExpiresAt.value }),
    async () => {
      // 刷新签名期间用户可能换封面；只回填仍引用同一资源的表单。
      const id = projectForm.coverMediaId
      if (!id) {
        return
      }
      try {
        const resource = await assetApi.resource(id)
        if (projectForm.coverMediaId === id) {
          coverPreview.value = resource.resourceUrl
          coverExpiresAt.value = resource.expiresAt
        }
      } catch (error) {
        feedback.error(error, PROJECT_MESSAGES.coverFailed)
        throw error
      }
    },
  )

  return {
    form: projectForm,
    tags: projectTags,
    status: projectStatus,
    previouslyPublished: wasPreviouslyPublished,
    loaded: isLoaded,
    operation: activeOperation,
    pending: isPending,
    dirty: hasUnsavedChanges,
    canOperate: canEditProject,
    load,
    reload,
    save,
    transition,
    setTag,
    addLink,
    removeLink,
    coverPreview,
    uploadCover,
    removeCover,
  }
}
