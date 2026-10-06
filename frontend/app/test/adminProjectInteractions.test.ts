import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminProject, ProjectPage } from '../api/projects'
import { ADMIN_PROJECT_STATUS } from '../constants/admin'
import { PROJECT_MESSAGES } from '../constants/projects'

const api = vi.hoisted(() => ({
  getManagedProjects: vi.fn(), getProject: vi.fn(), getProjectTags: vi.fn(),
  saveProject: vi.fn(), changeProjectStatus: vi.fn(),
}))
vi.mock('../api/projects', () => ({ useProjectApi: () => api }))

import { useAdminProjectList } from '../composables/useAdminProjectList'
import { useProjectEditor } from '../composables/useProjectEditor'

const project: AdminProject = {
  id: 7, slug: 'example-project', title: '真实项目', summary: '摘要', contribution: '本人贡献',
  outcome: null, timeLabel: null, tags: [{ id: 2, name: 'Java', slug: 'java', group: 'BACKEND', logoKey: null }],
  links: [{ type: 'CODE', label: '仓库', url: 'https://example.com/repository', visible: true, sortOrder: 0 }],
  status: ADMIN_PROJECT_STATUS.DRAFT, featured: false, sortOrder: 0, version: 3,
  publishedAt: null, updatedAt: '2026-10-06T01:00:00.123456',
}
function page(current = 1, total = 21): ProjectPage<AdminProject> {
  return { view: 'MANAGE', page: current, size: 20, total, items: [{ ...project }] }
}

beforeEach(() => {
  Object.values(api).forEach(mock => mock.mockReset())
  api.getManagedProjects.mockResolvedValue(page())
  api.getProjectTags.mockResolvedValue(project.tags)
  api.getProject.mockResolvedValue(project)
  api.saveProject.mockResolvedValue(project)
  api.changeProjectStatus.mockResolvedValue({ ...project, status: ADMIN_PROJECT_STATUS.PUBLISHED, version: 4 })
})

describe('managed project list interactions', () => {
  it('discards the old page on load failure, exposes the error and retries the intended page', async () => {
    const list = useAdminProjectList()
    await list.load()
    api.getManagedProjects.mockRejectedValueOnce(new Error('分页请求失败'))
    await list.changePage(2)
    expect(list.result.value).toBeNull()
    expect(list.error.value).toBe('分页请求失败')
    expect(list.pending.value).toBe(false)
    api.getManagedProjects.mockResolvedValueOnce(page(2))
    await list.load()
    expect(api.getManagedProjects).toHaveBeenLastCalledWith(2, '')
    expect(list.result.value?.page).toBe(2)
    expect(list.error.value).toBe('')
  })

  it('ignores a second load and does not change filters or pages while a request is pending', async () => {
    let finish: ((value: ProjectPage<AdminProject>) => void) | undefined
    api.getManagedProjects.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const list = useAdminProjectList()
    const loading = list.load()
    await list.load(2)
    await list.changeFilter(ADMIN_PROJECT_STATUS.PUBLISHED)
    await list.changePage(2)
    expect(api.getManagedProjects).toHaveBeenCalledTimes(1)
    expect(list.filter.value).toBe('ALL')
    finish?.(page())
    await loading
    expect(list.pending.value).toBe(false)
  })

  it('resets pagination on filtering and trusts response pagination instead of a stale local page', async () => {
    const list = useAdminProjectList()
    await list.load(2)
    api.getManagedProjects.mockResolvedValueOnce(page(1, 1))
    await list.changeFilter(ADMIN_PROJECT_STATUS.DRAFT)
    expect(api.getManagedProjects).toHaveBeenLastCalledWith(1, ADMIN_PROJECT_STATUS.DRAFT)
    expect(list.canPrevious.value).toBe(false)
    expect(list.canNext.value).toBe(false)
    await list.changePage(2)
    await list.changePage(0)
    expect(api.getManagedProjects).toHaveBeenCalledTimes(2)
  })

  it('can leave an empty high page after another operation reduces the total', async () => {
    api.getManagedProjects.mockResolvedValueOnce({ ...page(5, 0), items: [] })
    const list = useAdminProjectList()
    await list.load(5)
    expect(list.canPrevious.value).toBe(true)
    expect(list.canNext.value).toBe(false)
    await list.changePage(4)
    expect(api.getManagedProjects).toHaveBeenLastCalledWith(4, '')
  })

  it('preserves a genuine empty result without turning an API failure into empty success', async () => {
    api.getManagedProjects.mockResolvedValueOnce({ ...page(1, 0), items: [] })
    const list = useAdminProjectList()
    await list.load()
    expect(list.result.value?.items).toEqual([])
    expect(list.error.value).toBe('')
    api.getManagedProjects.mockRejectedValueOnce(new Error('读取失败'))
    await list.load()
    expect(list.result.value).toBeNull()
    expect(list.error.value).toBe('读取失败')
  })

  it('keeps a failed status change recoverable and does not retry a conflicting write', async () => {
    const list = useAdminProjectList()
    await list.load()
    api.changeProjectStatus.mockRejectedValueOnce(new Error(PROJECT_MESSAGES.conflict))
    await list.transition(project)
    expect(api.changeProjectStatus).toHaveBeenCalledWith(project.id, project.version, true)
    expect(api.changeProjectStatus).toHaveBeenCalledTimes(1)
    expect(api.getManagedProjects).toHaveBeenCalledTimes(1)
    expect(list.result.value?.items[0]?.version).toBe(project.version)
    expect(list.error.value).toBe(PROJECT_MESSAGES.conflict)
    expect(list.pending.value).toBe(false)
    expect(list.changingProjectId.value).toBeNull()
  })

  it('refreshes the list after successful publication and retains its success message', async () => {
    const list = useAdminProjectList()
    await list.load()
    await list.transition(project)
    expect(api.getManagedProjects).toHaveBeenCalledTimes(2)
    expect(list.notice.value).toBe(PROJECT_MESSAGES.published)
  })
})

describe('project editor interactions', () => {
  it('initializes a new draft as clean and enables editing only after the tags have loaded', async () => {
    const editor = useProjectEditor()
    expect(editor.canOperate.value).toBe(false)
    await editor.load()
    expect(editor.loaded.value).toBe(true)
    expect(editor.dirty.value).toBe(false)
    expect(api.getProject).not.toHaveBeenCalled()
    editor.form.title = '未保存标题'
    expect(editor.dirty.value).toBe(true)
  })

  it('allows retry after initial loading fails and always releases the pending state', async () => {
    api.getProjectTags.mockRejectedValueOnce(new Error('标签读取失败'))
    const editor = useProjectEditor(project.id)
    await editor.load()
    expect(editor.loaded.value).toBe(false)
    expect(editor.pending.value).toBe(false)
    expect(editor.error.value).toBe('标签读取失败')
    await editor.load()
    expect(editor.loaded.value).toBe(true)
    expect(editor.error.value).toBe('')
  })

  it('retains unsaved inputs and version on save conflict without retrying', async () => {
    const editor = useProjectEditor(project.id)
    await editor.load()
    editor.form.summary = '未保存摘要'
    api.saveProject.mockRejectedValueOnce(new Error(PROJECT_MESSAGES.conflict))
    expect(await editor.save()).toBeNull()
    expect(editor.form.summary).toBe('未保存摘要')
    expect(editor.form.version).toBe(project.version)
    expect(editor.dirty.value).toBe(true)
    expect(editor.pending.value).toBe(false)
    expect(editor.error.value).toBe(PROJECT_MESSAGES.conflict)
    expect(api.saveProject).toHaveBeenCalledTimes(1)
  })

  it('does not discard unsaved input when reload confirmation is cancelled', async () => {
    const editor = useProjectEditor(project.id)
    await editor.load()
    editor.form.title = '保留输入'
    const confirm = vi.fn(() => false)
    await editor.reload(confirm)
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(api.getProject).toHaveBeenCalledTimes(1)
    expect(editor.form.title).toBe('保留输入')
    expect(editor.dirty.value).toBe(true)
    await editor.reload(() => true)
    expect(api.getProject).toHaveBeenCalledTimes(2)
    expect(editor.form.title).toBe(project.title)
    expect(editor.dirty.value).toBe(false)
  })

  it('blocks duplicate saves and preserves one created identity until route navigation completes', async () => {
    let finish: ((value: AdminProject) => void) | undefined
    api.saveProject.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const editor = useProjectEditor()
    await editor.load()
    const saving = editor.save()
    expect(await editor.save()).toBeNull()
    expect(api.saveProject).toHaveBeenCalledTimes(1)
    finish?.(project)
    expect(await saving).toBe(project.id)
    await editor.save()
    expect(api.saveProject).toHaveBeenLastCalledWith(project.id, expect.objectContaining({ version: project.version }))
    await editor.reload(() => true)
    expect(api.getProject).toHaveBeenCalledWith(project.id)
  })

  it('prevents publishing dirty contents and uses the saved version after a successful save', async () => {
    const editor = useProjectEditor(project.id)
    await editor.load()
    editor.form.title = '修改标题'
    await editor.transition(true)
    expect(api.changeProjectStatus).not.toHaveBeenCalled()
    api.saveProject.mockResolvedValueOnce({ ...project, title: '修改标题', version: 4 })
    await editor.save()
    await editor.transition(true)
    expect(api.changeProjectStatus).toHaveBeenCalledWith(project.id, 4, true)
    expect(editor.status.value).toBe(ADMIN_PROJECT_STATUS.PUBLISHED)
    expect(editor.dirty.value).toBe(false)
  })

  it('locks a historically published slug and clones tag/link snapshots from the response', async () => {
    api.getProject.mockResolvedValueOnce({ ...project, publishedAt: '2026-10-05T01:00:00' })
    const editor = useProjectEditor(project.id)
    await editor.load()
    expect(editor.previouslyPublished.value).toBe(true)
    editor.form.links[0]!.label = '编辑中的名称'
    editor.setTag(3, true)
    editor.setTag(3, true)
    expect(project.links[0]?.label).toBe('仓库')
    expect(editor.form.tagIds).toEqual([2, 3])
    editor.setTag(2, false)
    expect(editor.form.tagIds).toEqual([3])
  })
})
