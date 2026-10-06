import { computed, reactive, ref } from 'vue'
import { useProjectApi, type AdminProject, type ProjectInput, type ProjectTag } from '../api/projects'
import { ADMIN_PROJECT_DEFAULT_LINK_TYPE, ADMIN_PROJECT_STATUS } from '../constants/admin'
import { PROJECT_MESSAGES } from '../constants/projects'

/** 编辑器维护完整版本快照；失败时保留输入，不绕过统一请求拦截器。 */
export function useProjectEditor(projectId?: number) {
  const { getProject, getProjectTags, saveProject, changeProjectStatus } = useProjectApi()
  const savedProjectId = ref(projectId ?? null)
  const form = reactive<ProjectInput>({
    slug: '', title: '', summary: '', contribution: '', outcome: '', timeLabel: '',
    tagIds: [], links: [], featured: false, sortOrder: 0,
  })
  const tags = ref<ProjectTag[]>([])
  const status = ref<AdminProject['status']>(ADMIN_PROJECT_STATUS.DRAFT)
  const previouslyPublished = ref(false)
  const loaded = ref(false)
  const operation = ref<'load' | 'save' | 'publish' | 'unpublish' | null>(null)
  const pending = computed(() => operation.value !== null)
  const error = ref('')
  const notice = ref('')
  const savedForm = ref('')
  const dirty = computed(() => loaded.value && JSON.stringify(form) !== savedForm.value)
  const canOperate = computed(() => loaded.value && !pending.value)

  function apply(project: AdminProject) {
    Object.assign(form, {
      version: project.version, slug: project.slug ?? '', title: project.title,
      summary: project.summary, contribution: project.contribution, outcome: project.outcome ?? '',
      timeLabel: project.timeLabel ?? '', tagIds: project.tags.map(tag => tag.id),
      links: project.links.map(link => ({ ...link })), featured: project.featured, sortOrder: project.sortOrder,
    })
    status.value = project.status
    previouslyPublished.value = project.publishedAt !== null
    savedForm.value = JSON.stringify(form)
  }

  async function load() {
    if (pending.value) return
    operation.value = 'load'
    error.value = ''
    notice.value = ''
    try {
      // 初次失败同样允许重试；两个只读请求完成后才启用编辑器。
      const currentProjectId = savedProjectId.value
      const [loadedTags, project] = await Promise.all([
        getProjectTags(), currentProjectId ? getProject(currentProjectId) : Promise.resolve(null),
      ])
      tags.value = loadedTags
      if (project) apply(project)
      else savedForm.value = JSON.stringify(form)
      loaded.value = true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : PROJECT_MESSAGES.loadFailed
    } finally {
      operation.value = null
    }
  }

  async function save(): Promise<number | null> {
    if (!canOperate.value) return null
    operation.value = 'save'
    error.value = ''
    notice.value = ''
    try {
      // 克隆集合，防止请求与响应快照共享可编辑引用。
      const project = await saveProject(savedProjectId.value, {
        ...form, tagIds: [...form.tagIds], links: form.links.map(link => ({ ...link })),
      })
      apply(project)
      // 新建完成后的路由切换可能尚未结束，再次点击只能更新同一项目，不能重复创建。
      savedProjectId.value = project.id
      notice.value = PROJECT_MESSAGES.saved
      return project.id
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : PROJECT_MESSAGES.saveFailed
      return null
    } finally {
      operation.value = null
    }
  }

  async function reload(confirmDiscard: () => boolean) {
    if (pending.value || (dirty.value && !confirmDiscard())) return
    await load()
  }

  async function transition(publish: boolean) {
    // 发布/下架只能针对已保存内容；此限制不代替 OWNER 鉴权。
    if (!canOperate.value || dirty.value || !projectId || form.version === undefined) return
    operation.value = publish ? 'publish' : 'unpublish'
    error.value = ''
    notice.value = ''
    try {
      apply(await changeProjectStatus(projectId, form.version, publish))
      notice.value = publish ? PROJECT_MESSAGES.published : PROJECT_MESSAGES.unpublished
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : PROJECT_MESSAGES.saveFailed
    } finally {
      operation.value = null
    }
  }

  function setTag(id: number, selected: boolean) {
    if (!canOperate.value) return
    if (selected && !form.tagIds.includes(id)) form.tagIds.push(id)
    if (!selected) form.tagIds = form.tagIds.filter(tagId => tagId !== id)
  }

  function addLink() {
    if (!canOperate.value) return
    form.links.push({ type: ADMIN_PROJECT_DEFAULT_LINK_TYPE, label: '', url: '', visible: true, sortOrder: form.links.length })
  }

  function removeLink(index: number) {
    if (canOperate.value) form.links.splice(index, 1)
  }

  return { form, tags, status, previouslyPublished, loaded, operation, pending, error, notice,
    dirty, canOperate, load, reload, save, transition, setTag, addLink, removeLink }
}
