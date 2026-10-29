import { describe, expect, it, vi } from 'vitest'
import type { Blog, BlogInput, BlogContentInput, BlogPropertiesInput } from '../../../app/api/blogs'
import { ApiRequestError } from '../../../app/api/request'
import { createBlogPersistence } from '../../../app/utils/editor/session/blogPersistence'
import type { CanvasSaveSnapshot } from '../../../app/utils/editor/session/canvasSave'
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
function snapshot(markdown = '新正文'): CanvasSaveSnapshot {
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
    create: vi.fn<(body: BlogInput) => Promise<Blog>>().mockResolvedValue(post(2)),
    saveContent: vi
      .fn<(id: number, body: BlogContentInput) => Promise<Blog>>()
      .mockResolvedValue(post(3)),
    saveProperties: vi
      .fn<(id: number, body: BlogPropertiesInput) => Promise<Blog>>()
      .mockResolvedValue(post(3)),
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
        ...post(4),
        hasDraft: false,
        hasPublished: true,
        stage: 'PUBLISHED',
        status: 'PUBLISHED',
      }),
  }
  const accepted = vi.fn()
  return { api, accepted, session: createBlogPersistence(api, initial, accepted) }
}
describe('正文与文章属性独立保存（受控 API，不冒充真实联调）', () => {
  it('资源未就绪时，同一正文仍可明确重试，不被无改动短路吞掉', async () => {
    const { api, session } = setup({ ...post(), resourcesReady: false })
    const value = snapshot('正文')
    value.metadata.remoteRightsConfirmed = true
    await session.adapter.save(value)
    expect(api.saveContent).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ content: '正文', remoteRightsConfirmed: true }),
    )
  })
  it('属性地址清空规则与后端一致，空白输入转换为空标识', async () => {
    const { api, session } = setup()
    await session.saveProperties({ ...snapshot().metadata, slug: '   ' })
    expect(api.saveProperties).toHaveBeenCalledWith(7, expect.objectContaining({ slug: '' }))
  })
  it('正文不验证或上传未提交属性，首次创建仅建空属性草稿', async () => {
    const { api, session } = setup(null)
    const value = snapshot()
    value.metadata.slug = '无效/尚未提交'
    value.metadata.title = '尚未提交的标题'
    await session.adapter.save(value)
    expect(api.create).toHaveBeenCalledWith(
      expect.objectContaining({ title: '', slug: '', tagIds: [], content: '新正文' }),
    )
    expect(api.create.mock.calls[0]![0].requestId).toMatch(/^[a-f0-9-]{36}$/)
    await session.adapter.save(snapshot('下一版'))
    expect(api.saveContent).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ revision: 2, content: '下一版' }),
    )
    expect(Object.keys(api.saveContent.mock.calls[0]![1]).sort()).toEqual([
      'content',
      'remoteRightsConfirmed',
      'requestId',
      'revision',
    ])
    expect(api.saveProperties).not.toHaveBeenCalled()
  })
  it('断线先以原身份确认原创建，再用新修订保存后来的正文', async () => {
    const { api, session } = setup(null)
    api.create.mockRejectedValueOnce(new ApiRequestError('断线', 503, null))
    await expect(session.adapter.save(snapshot('原输入'))).rejects.toThrow('断线')
    const original = structuredClone(api.create.mock.calls[0]![0])
    await session.adapter.save(snapshot('后续正文'))
    expect(api.create.mock.calls).toEqual([[original], [original]])
    expect(api.saveContent).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ revision: 2, content: '后续正文' }),
    )
  })
  it('修订冲突保留输入、读取最新状态，不换身份盲目覆盖', async () => {
    const { api, session, accepted } = setup()
    api.saveContent.mockRejectedValueOnce(
      new ApiRequestError('冲突', 409, 'DOCUMENT_REVISION_CONFLICT'),
    )
    const value = snapshot()
    const before = structuredClone(value)
    await expect(session.adapter.save(value)).rejects.toThrow('服务器写入冲突')
    expect(value).toEqual(before)
    expect(api.get).toHaveBeenCalledExactlyOnceWith(7)
    expect(session.latest.value?.revision).toBe(9)
    expect(accepted).not.toHaveBeenCalled()
    await expect(session.adapter.save(snapshot('后续输入'))).rejects.toThrow('存在保存冲突')
    expect(api.saveContent).toHaveBeenCalledTimes(1)
  })
  it('属性保存原样保留旧格式 slug，不带正文，也不走正文适配器', async () => {
    const { api, session } = setup({ ...post(), slug: 'old_slug' })
    await session.saveProperties({ ...snapshot().metadata, title: '新标题', slug: 'old_slug' })
    expect(api.saveProperties).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ title: '新标题', slug: 'old_slug', revision: 1 }),
    )
    expect(api.saveProperties.mock.calls[0]![1]).not.toHaveProperty('content')
    expect(api.saveProperties.mock.calls[0]![1]).not.toHaveProperty('remoteRightsConfirmed')
    expect(api.saveContent).not.toHaveBeenCalled()
  })
  it('Slug 占用只拒绝属性；正文仍能保存，错误不被泛化为全局修订冲突', async () => {
    const { api, session } = setup()
    api.saveProperties.mockRejectedValueOnce(
      new ApiRequestError('文章地址已使用', 409, 'BLOG_SLUG_CONFLICT'),
    )
    await expect(
      session.saveProperties({ ...snapshot().metadata, slug: 'occupied' }),
    ).rejects.toThrow('文章地址已使用')
    expect(session.conflicted.value).toBe(false)
    await session.adapter.save(snapshot())
    expect(api.saveContent).toHaveBeenCalledTimes(1)
    expect(api.get).not.toHaveBeenCalled()
  })
  it('正文和属性排队使用最新修订，各自提交自己的快照', async () => {
    const { api, session } = setup()
    let resolve!: (value: Blog) => void
    api.saveContent.mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      }),
    )
    const bodySave = session.adapter.save(snapshot())
    const properties = { ...snapshot().metadata, title: '先提交标题', slug: 'test' }
    const propertiesSave = session.saveProperties(properties)
    properties.title = '排队后继续输入'
    await Promise.resolve()
    expect(api.saveProperties).not.toHaveBeenCalled()
    resolve(post(6))
    await bodySave
    await propertiesSave
    expect(api.saveProperties).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ revision: 6, title: '先提交标题' }),
    )
  })
  it('另一范围请求前确认上次不明结果，既不丢原请求身份，也不重复正文提交', async () => {
    const { api, session } = setup()
    api.saveProperties.mockRejectedValueOnce(new ApiRequestError('断线', 502, null))
    const properties = { ...snapshot().metadata, title: '待确认属性', slug: 'test' }
    await expect(session.saveProperties(properties)).rejects.toThrow('断线')
    const original = structuredClone(api.saveProperties.mock.calls[0]![1])
    await session.adapter.save(snapshot())
    expect(api.saveProperties.mock.calls).toEqual([
      [7, original],
      [7, original],
    ])
    expect(api.saveContent).toHaveBeenCalledWith(7, expect.objectContaining({ revision: 3 }))
  })
  it('相同内容/属性不发请求，不改变修订；仅属性被修改才验证新 slug', async () => {
    const { api, session } = setup()
    await session.adapter.save(snapshot('正文'))
    await session.saveProperties({
      ...snapshot().metadata,
      title: '标题',
      slug: 'test',
      tagIds: [],
    })
    expect(api.saveContent).not.toHaveBeenCalled()
    expect(api.saveProperties).not.toHaveBeenCalled()
    expect(session.post.value?.revision).toBe(1)
    await expect(
      session.saveProperties({ ...snapshot().metadata, slug: 'INVALID/slug' }),
    ).rejects.toThrow('文章标识')
    await session.adapter.save(snapshot())
    expect(api.saveContent).toHaveBeenCalledTimes(1)
  })
  it('明确拒绝后更换请求身份；状态失败保留身份，阻止改成另一动作', async () => {
    const { api, session, accepted } = setup()
    api.saveContent.mockRejectedValueOnce(new ApiRequestError('输入无效', 422, 'VALIDATION_FAILED'))
    await expect(session.adapter.save(snapshot())).rejects.toThrow('输入无效')
    await session.adapter.save(snapshot())
    expect(api.saveContent.mock.calls[0]![1].requestId).not.toBe(
      api.saveContent.mock.calls[1]![1].requestId,
    )
    expect(accepted).toHaveBeenCalledWith(post(3), 'content')
    api.transition.mockRejectedValueOnce(new ApiRequestError('断线', 502, null))
    await expect(session.transition('restore')).rejects.toThrow('断线')
    await expect(session.adapter.save(snapshot())).rejects.toThrow('上次发布状态尚未确认')
    await expect(session.transition('publish')).rejects.toThrow('上次状态操作尚未确认')
    await session.transition('restore')
    expect(api.transition.mock.calls[0]).toEqual(api.transition.mock.calls[1])
  })
})
