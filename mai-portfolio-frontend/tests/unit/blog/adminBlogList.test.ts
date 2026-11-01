import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Blog, BlogPage } from '../../../app/api/blogs'
import { useAdminBlogList } from '../../../app/composables/useAdminBlogList'
import { validateBlogProperties, sameBlogProperties } from '../../../app/utils/blog/properties'
const api = vi.hoisted(() => ({ list: vi.fn(), get: vi.fn(), transition: vi.fn() }))
const feedback = vi.hoisted(() => ({ success: vi.fn() }))
vi.mock('../../../app/api/blogs', () => ({ useBlogApi: () => api }))
vi.mock('../../../app/composables/useFeedback', () => ({ useFeedback: () => feedback }))
function post(revision = 2): Blog {
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
    slug: 'title',
    summary: '摘要',
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
function page(): BlogPage {
  return { view: 'MANAGE', page: 1, size: 20, total: 1, items: [post()] }
}
beforeEach(() => {
  vi.clearAllMocks()
  api.list.mockResolvedValue(page())
  api.get.mockResolvedValue(post())
  api.transition.mockResolvedValue({ ...post(3), status: 'PUBLISHED' })
})
describe('admin blog list', () => {
  it('retries the failed requested page instead of showing an empty success', async () => {
    api.list.mockRejectedValueOnce(new Error('读取失败'))
    const list = useAdminBlogList()
    await list.load(3)
    expect(list.result.value).toBeNull()
    expect(list.error.value).toBe('读取失败')
    await list.load()
    expect(api.list).toHaveBeenLastCalledWith({ page: 3, size: 20, keyword: '' })
  })
  it('does not overlap list requests', async () => {
    let finish!: (result: BlogPage) => void
    api.list.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const list = useAdminBlogList()
    const loading = list.load()
    await list.load(2)
    expect(api.list).toHaveBeenCalledTimes(1)
    finish(page())
    await loading
    expect(list.pending.value).toBe(false)
  })
  it('refuses to publish a newer revision than the card the user confirmed', async () => {
    api.get.mockResolvedValueOnce(post(4))
    const list = useAdminBlogList()
    await expect(list.transition(post())).rejects.toThrow('文章已被更新')
    expect(api.transition).not.toHaveBeenCalled()
    expect(list.pending.value).toBe(false)
  })
  it('refuses incomplete resources without creating a publication request', async () => {
    api.get.mockResolvedValueOnce({ ...post(), resourcesReady: false })
    const list = useAdminBlogList()
    await expect(list.transition(post())).rejects.toThrow('未就绪资源')
    expect(api.transition).not.toHaveBeenCalled()
  })
  it('publishes with the existing revision contract and refreshes only on success', async () => {
    const list = useAdminBlogList()
    expect(await list.transition(post())).toBe(true)
    expect(api.transition).toHaveBeenCalledWith(7, 'publish', {
      revision: 2,
      requestId: expect.any(String),
    })
    expect(api.list).toHaveBeenCalledOnce()
    expect(feedback.success).toHaveBeenCalledWith('草稿已发布。')
  })
  it('reuses the same idempotency key after an uncertain network response', async () => {
    api.transition.mockRejectedValueOnce(new Error('network'))
    const list = useAdminBlogList()
    await expect(list.transition(post())).rejects.toThrow('network')
    const first = api.transition.mock.calls[0]![2]
    expect(api.list).not.toHaveBeenCalled()
    await list.transition(post())
    expect(api.transition.mock.calls[1]![2]).toEqual(first)
  })
})
it('accepts a newer revision after reloading an uncertain completed transition', async () => {
  api.transition.mockRejectedValueOnce(new Error('network'))
  const list = useAdminBlogList()
  await expect(list.transition(post())).rejects.toThrow('network')
  const published: Blog = { ...post(3), status: 'PUBLISHED' }
  api.list.mockResolvedValueOnce({ ...page(), items: [published] })
  await list.load()
  api.get.mockResolvedValueOnce(published)
  expect(await list.transition(published)).toBe(true)
  expect(api.transition).toHaveBeenLastCalledWith(7, 'unpublish', {
    revision: 3,
    requestId: expect.any(String),
  })
})

describe('shared article property rules', () => {
  const properties = {
    title: '标题',
    slug: 'title',
    summary: '',
    tagIds: [1, 2],
    allowDownload: true,
  }
  it('treats tag order as unchanged and validates real property changes', () => {
    expect(sameBlogProperties(properties, { ...properties, tagIds: [2, 1] })).toBe(true)
    expect(validateBlogProperties({ ...properties, slug: 'BAD slug' }, properties.slug)).toEqual([
      expect.objectContaining({ name: 'slug' }),
    ])
    expect(
      validateBlogProperties({ ...properties, summary: 'a'.repeat(2001) }, properties.slug)[0]
        ?.name,
    ).toBe('summary')
  })
})

it('changes article page size and resets to the first page, preserving the choice on retry', async () => {
  const list = useAdminBlogList()
  await list.load(3)
  api.list.mockRejectedValueOnce(new Error('读取失败'))
  await list.changeSize(5)
  expect(api.list).toHaveBeenLastCalledWith({ page: 1, size: 5, keyword: '' })
  expect(list.pageSize.value).toBe(5)
  await list.load()
  expect(api.list).toHaveBeenLastCalledWith({ page: 1, size: 5, keyword: '' })
  await list.changeSize(50)
  expect(list.pageSize.value).toBe(5)
})

it('submits trimmed search once and preserves the applied query while paging', async () => {
  const list = useAdminBlogList()
  list.keyword.value = '  algorithms  '
  expect(api.list).not.toHaveBeenCalled()
  await list.search()
  expect(api.list).toHaveBeenLastCalledWith({ page: 1, size: 20, keyword: 'algorithms' })
  list.keyword.value = 'not submitted'
  await list.load(2)
  expect(api.list).toHaveBeenLastCalledWith({ page: 2, size: 20, keyword: 'algorithms' })
  await list.changeFilter('PUBLISHED')
  await list.changeSize(5)
  expect(api.list).toHaveBeenLastCalledWith({
    page: 1,
    size: 5,
    keyword: 'not submitted',
    status: 'PUBLISHED',
  })
  await list.clear()
  expect(api.list).toHaveBeenLastCalledWith({ page: 1, size: 5, keyword: '' })
})
