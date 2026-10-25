import { describe, expect, it, vi } from 'vitest'
import type { Blog, BlogInput } from '../api/blogs'
import { ApiRequestError } from '../api/request'
import { createBlogPersistence } from '../utils/editor/blogPersistence'
import type { CanvasSaveSnapshot } from '../utils/editor/canvasSave'

function post(revision = 1): Blog {
  return {
    id: 7,
    documentId: 11,
    contentId: 21,
    revision,
    stage: 'DRAFT',
    hasDraft: true,
    hasPublished: false,
    resourcesReady: true,
    title: '标题',
    slug: 'test',
    summary: '',
    status: 'DRAFT',
    publishedAt: null,
    updatedAt: '',
    content: '正文',
    previewIssue: null,
    resources: [],
    tags: [],
    allowDownload: true,
  }
}
function snapshot(markdown = '正文'): CanvasSaveSnapshot {
  return {
    document: { type: 'doc', content: [{ type: 'paragraph' }] },
    markdown,
    metadata: {
      title: '',
      slug: '',
      summary: '',
      allowDownload: true,
      remoteRightsConfirmed: false,
      tagIds: [3],
    },
  }
}
function setup(initial: Blog | null = post()) {
  const api = {
    save: vi.fn<(id: number | null, body: BlogInput) => Promise<Blog>>().mockResolvedValue(post(2)),
    get: vi.fn<(id: number) => Promise<Blog>>().mockResolvedValue(post(9)),
    transition: vi
      .fn<
        (
          id: number,
          action: 'publish' | 'unpublish' | 'restore' | 'draft',
          body: { revision: number; requestId: string },
        ) => Promise<Blog>
      >()
      .mockResolvedValue({
        ...post(3),
        hasDraft: false,
        hasPublished: true,
        stage: 'PUBLISHED',
        status: 'PUBLISHED',
      }),
  }
  const accepted = vi.fn()
  return { api, accepted, session: createBlogPersistence(api, initial, accepted) }
}

describe('正式博客保存边界（受控 API 响应，不冒充真实联调）', () => {
  it('创建允许空标题/地址，必传标签与 UUID；下一次保存使用服务器修订号', async () => {
    const { api, session } = setup(null)
    await session.adapter.save(snapshot())
    const body = api.save.mock.calls[0]![1]
    expect(api.save.mock.calls[0]![0]).toBeNull()
    expect(body).toMatchObject({ title: '', slug: '', tagIds: [3], content: '正文' })
    expect(body).not.toHaveProperty('revision')
    expect(body).not.toHaveProperty('version')
    expect(body.requestId).toMatch(/^[a-f0-9-]{36}$/)
    await session.adapter.save(snapshot('后续编辑'))
    expect(api.save.mock.calls[1]).toEqual([
      7,
      expect.objectContaining({ revision: 2, content: '后续编辑' }),
    ])
    expect(api.save.mock.calls[1]![1].requestId).not.toBe(body.requestId)
  })
  it('断线后先以原身份和原输入确认原请求，再保存后来输入，不重复创建', async () => {
    const { api, session } = setup(null)
    api.save.mockRejectedValueOnce(new ApiRequestError('断线', 503, null))
    await expect(session.adapter.save(snapshot('原输入'))).rejects.toThrow('断线')
    const original = structuredClone(api.save.mock.calls[0]![1])
    await session.adapter.save(snapshot('后来输入'))
    expect(api.save.mock.calls).toEqual([
      [null, original],
      [null, original],
      [7, expect.objectContaining({ revision: 2, content: '后来输入' })],
    ])
    expect(api.save.mock.calls[2]![1].requestId).not.toBe(original.requestId)
  })
  it('409 读取最新管理状态但不覆盖本地快照，也不换 UUID 盲目重放', async () => {
    const { api, session, accepted } = setup()
    api.save.mockRejectedValueOnce(new ApiRequestError('冲突', 409, 'CONFLICT'))
    const value = snapshot('未保存内容')
    const before = structuredClone(value)
    await expect(session.adapter.save(value)).rejects.toThrow('保存冲突')
    expect(api.get).toHaveBeenCalledExactlyOnceWith(7)
    expect(session.latest.value?.revision).toBe(9)
    expect(session.post.value?.revision).toBe(1)
    expect(value).toEqual(before)
    expect(accepted).not.toHaveBeenCalled()
    await expect(session.adapter.save(snapshot('继续编辑'))).rejects.toThrow('存在保存冲突')
    expect(api.save).toHaveBeenCalledTimes(1)
  })
  it('明确拒绝的输入修正后使用新请求，响应只交给投影回调，不改写输入', async () => {
    const { api, session, accepted } = setup()
    api.save.mockRejectedValueOnce(new ApiRequestError('无效输入', 400, 'BAD_REQUEST'))
    const value = snapshot()
    await expect(session.adapter.save(value)).rejects.toThrow('无效输入')
    await session.adapter.save(value)
    expect(api.save.mock.calls[1]![1].requestId).not.toBe(api.save.mock.calls[0]![1].requestId)
    expect(value.markdown).toBe('正文')
    expect(accepted).toHaveBeenCalledExactlyOnceWith(post(2))
  })
  it('发布与恢复按不同动作发送；状态请求断线保留请求身份，禁止未确认时保存', async () => {
    const { api, session } = setup()
    api.transition.mockRejectedValueOnce(new ApiRequestError('断线', 502, null))
    await expect(session.transition('restore')).rejects.toThrow('断线')
    await expect(session.adapter.save(snapshot())).rejects.toThrow('上次发布状态尚未确认')
    await expect(session.transition('publish')).rejects.toThrow('上次状态操作尚未确认')
    await session.transition('restore')
    expect(api.transition.mock.calls[0]).toEqual(api.transition.mock.calls[1])
    await session.transition('publish')
    expect(api.transition.mock.calls[2]).toEqual([
      7,
      'publish',
      expect.objectContaining({ revision: 3 }),
    ])
  })
  it('保存未结束时状态操作被拒绝；响应后使用最新修订号', async () => {
    const { api, session } = setup()
    let resolve!: (value: Blog) => void
    api.save.mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    const saving = session.adapter.save(snapshot())
    await expect(session.transition('publish')).rejects.toThrow('上一项文章操作尚未结束')
    resolve(post(4))
    await saving
    await session.transition('publish')
    expect(api.transition).toHaveBeenCalledWith(
      7,
      'publish',
      expect.objectContaining({ revision: 4 }),
    )
  })
})
