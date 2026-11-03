import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Blog, BlogPage } from '../../../app/api/blogs'
import { useAdminBlogList } from '../../../app/composables/useAdminBlogList'
import { validateBlogProperties, sameBlogProperties } from '../../../app/utils/blog/properties'
const api = vi.hoisted(() => ({
  list: vi.fn(),
  get: vi.fn(),
  transition: vi.fn(),
  remove: vi.fn(),
}))
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
  return { view: 'MANAGE', page: 1, size: 10, total: 1, items: [post()] }
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
    expect(api.list).toHaveBeenLastCalledWith({ page: 3, size: 10, keyword: '' })
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
    await expect(list.transition(post(), 'publish')).rejects.toThrow('文章已被更新')
    expect(api.transition).not.toHaveBeenCalled()
    expect(list.pending.value).toBe(false)
  })
  it('refuses incomplete resources without creating a publication request', async () => {
    api.get.mockResolvedValueOnce({ ...post(), resourcesReady: false })
    const list = useAdminBlogList()
    await expect(list.transition(post(), 'publish')).rejects.toThrow('未就绪资源')
    expect(api.transition).not.toHaveBeenCalled()
  })
  it('publishes with the existing revision contract and refreshes only on success', async () => {
    const list = useAdminBlogList()
    expect(await list.transition(post(), 'publish')).toBe(true)
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
    await expect(list.transition(post(), 'publish')).rejects.toThrow('network')
    const first = api.transition.mock.calls[0]![2]
    expect(api.list).not.toHaveBeenCalled()
    await list.transition(post(), 'publish')
    expect(api.transition.mock.calls[1]![2]).toEqual(first)
  })
})
it('accepts a newer revision after reloading an uncertain completed transition', async () => {
  api.transition.mockRejectedValueOnce(new Error('network'))
  const list = useAdminBlogList()
  await expect(list.transition(post(), 'publish')).rejects.toThrow('network')
  const published: Blog = { ...post(3), status: 'PUBLISHED' }
  api.list.mockResolvedValueOnce({ ...page(), items: [published] })
  await list.load()
  api.get.mockResolvedValueOnce(published)
  expect(await list.transition(published, 'unpublish')).toBe(true)
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
  expect(api.list).toHaveBeenLastCalledWith({ page: 1, size: 10, keyword: 'algorithms' })
  list.keyword.value = 'not submitted'
  await list.load(2)
  expect(api.list).toHaveBeenLastCalledWith({ page: 2, size: 10, keyword: 'algorithms' })
  await list.changeFilter('PUBLISHED')
  await list.search()
  await list.changeSize(5)
  expect(api.list).toHaveBeenLastCalledWith({
    page: 1,
    size: 5,
    keyword: 'not submitted',
    status: 'PUBLISHED',
  })
  await list.clear()
  await list.search()
  expect(api.list).toHaveBeenLastCalledWith({ page: 1, size: 5, keyword: '' })
})

it('clears the applied query even after the search input was manually emptied', async () => {
  const list = useAdminBlogList()
  await list.clear()
  expect(api.list).not.toHaveBeenCalled()
  list.keyword.value = 'Vue'
  await list.search()
  list.keyword.value = ''
  await list.clear()
  await list.search()
  expect(api.list).toHaveBeenLastCalledWith({ page: 1, size: 10, keyword: '' })
  expect(api.list).toHaveBeenCalledTimes(2)
})

it('keeps changed filters and cleared inputs local until querying', async () => {
  const list = useAdminBlogList()
  await list.load()
  await list.changeFilter('PUBLISHED')
  list.keyword.value = 'algorithm'
  expect(api.list).toHaveBeenCalledTimes(1)
  await list.clear()
  expect(api.list).toHaveBeenCalledTimes(1)
})

it('keeps submitted status when paging with different unsubmitted controls', async () => {
  const list = useAdminBlogList()
  list.changeFilter('PUBLISHED')
  await list.search()
  list.changeFilter('DRAFT')
  await list.load(2)
  expect(api.list).toHaveBeenLastCalledWith({ page: 2, size: 10, keyword: '', status: 'PUBLISHED' })
  list.clear()
  await list.load(3)
  expect(api.list).toHaveBeenLastCalledWith({ page: 3, size: 10, keyword: '', status: 'PUBLISHED' })
})
it('deletes a draft using its confirmed revision and refreshes only after success', async () => {
  const list = useAdminBlogList()
  expect(await list.remove(post())).toBe(true)
  expect(api.remove).toHaveBeenCalledWith(7, { revision: 2, requestId: expect.any(String) })
  expect(api.get).not.toHaveBeenCalled()
  expect(api.list).toHaveBeenCalledOnce()
})
it('does not delete published articles or a published document with a working draft', async () => {
  const list = useAdminBlogList()
  await expect(list.remove({ ...post(), status: 'PUBLISHED' })).rejects.toThrow('先下架')
  await expect(list.remove({ ...post(), hasPublished: true })).rejects.toThrow('工作草稿')
  await expect(list.remove({ ...post(), revision: null })).rejects.toThrow('修订号')
  expect(api.remove).not.toHaveBeenCalled()
})
it('preserves the delete request identity on uncertain failures and does not auto-retry', async () => {
  const list = useAdminBlogList()
  api.remove.mockRejectedValueOnce(new Error('network'))
  await expect(list.remove(post())).rejects.toThrow('network')
  expect(api.list).not.toHaveBeenCalled()
  expect(list.pending.value).toBe(false)
  const body = api.remove.mock.calls[0]![1]
  await list.remove(post())
  expect(api.remove.mock.calls[1]![1]).toEqual(body)
})
it('returns to the previous page after deleting the last row of a page', async () => {
  const list = useAdminBlogList()
  api.list.mockResolvedValueOnce({ ...page(), page: 2, total: 6, size: 5 })
  await list.load(2)
  await list.remove(post())
  expect(api.list).toHaveBeenLastCalledWith({ page: 1, size: 10, keyword: '' })
})

it('keeps the list intact on a delete revision conflict and never repeats a pending delete', async () => {
  const list = useAdminBlogList()
  await list.load()
  const snapshot = list.result.value
  let reject!: (error: Error) => void
  api.remove.mockImplementationOnce(
    () =>
      new Promise((_, fail) => {
        reject = fail
      }),
  )
  const deleting = list.remove(post())
  expect(await list.remove(post())).toBe(false)
  expect(api.remove).toHaveBeenCalledTimes(1)
  reject(new Error('revision conflict'))
  await expect(deleting).rejects.toThrow('revision conflict')
  expect(list.result.value).toBe(snapshot)
  expect(api.list).toHaveBeenCalledTimes(1)
})

it('keeps the displayed rows while a new page is loading', async () => {
  const list = useAdminBlogList()
  await list.load()
  const snapshot = list.result.value
  let finish!: (value: BlogPage) => void
  api.list.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  const loading = list.load(2)
  expect(list.pending.value).toBe(true)
  expect(list.result.value).toBe(snapshot)
  finish(page())
  await loading
  expect(list.pending.value).toBe(false)
})

it('publishes a new draft on an already published article instead of unpublishing it', async () => {
  const article: Blog = { ...post(), status: 'PUBLISHED', hasPublished: true, hasDraft: true }
  api.get.mockResolvedValueOnce(article)
  const list = useAdminBlogList()
  expect(await list.transition(article, 'publish')).toBe(true)
  expect(api.transition).toHaveBeenCalledWith(article.id, 'publish', {
    revision: article.revision,
    requestId: expect.any(String),
  })
})

it('still unpublishes explicitly when a new draft also exists', async () => {
  const article: Blog = { ...post(), status: 'PUBLISHED', hasPublished: true, hasDraft: true }
  api.get.mockResolvedValueOnce(article)
  const list = useAdminBlogList()
  expect(await list.transition(article, 'unpublish')).toBe(true)
  expect(api.transition).toHaveBeenCalledWith(article.id, 'unpublish', {
    revision: article.revision,
    requestId: expect.any(String),
  })
})
