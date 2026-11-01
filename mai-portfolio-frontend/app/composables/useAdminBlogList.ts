import { ref, shallowRef } from 'vue'
import { useBlogApi, type BlogCard, type BlogPage } from '../api/blogs'
import { BLOG_FIRST_PAGE } from '../constants/blogs'
import { useFeedback } from './useFeedback'
import { createBlogPersistence } from '../utils/editor/session/blogPersistence'

/** 管理列表只读分页快照；状态写入继续走带修订号、幂等和冲突保护的博客会话。 */
export function useAdminBlogList() {
  const api = useBlogApi()
  const result = shallowRef<BlogPage | null>(null)
  const pending = ref(false)
  const error = ref('')
  const page = ref(BLOG_FIRST_PAGE)
  const sessions = new Map<number, ReturnType<typeof createBlogPersistence>>()
  const feedback = useFeedback()
  async function load(nextPage = page.value) {
    if (pending.value) {
      return
    }
    pending.value = true
    page.value = nextPage
    error.value = ''
    result.value = null
    try {
      result.value = await api.list(nextPage)
      for (const [id, session] of sessions) {
        // 重读已确认新修订时，旧状态请求不能再阻止用户操作当前版本。
        const current = result.value.items.find((article) => article.id === id)
        if (
          session.conflicted.value ||
          (current && current.revision !== session.post.value?.revision)
        ) {
          sessions.delete(id)
        }
      }
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '文章列表读取失败。'
    } finally {
      pending.value = false
    }
  }
  async function transition(article: BlogCard): Promise<boolean> {
    if (pending.value) {
      return false
    }
    pending.value = true
    const action = article.status === 'PUBLISHED' ? 'unpublish' : 'publish'
    try {
      let session = sessions.get(article.id)
      if (!session) {
        const post = await api.get(article.id)
        if (post.revision !== article.revision) {
          throw new Error('文章已被更新，请刷新列表并核对后再操作。')
        }
        if (
          action === 'publish' &&
          (!post.hasDraft ||
            !post.resourcesReady ||
            !post.title.trim() ||
            !post.slug ||
            !post.content.trim())
        ) {
          throw new Error('请先保存完整的标题、文章地址和正文，并处理未就绪资源，再发布草稿。')
        }
        session = createBlogPersistence(api, post, () => {})
        sessions.set(article.id, session)
      }
      await session.transition(action)
      sessions.delete(article.id)
      feedback.success(action === 'publish' ? '草稿已发布。' : '文章已下架。')
    } finally {
      pending.value = false
    }
    await load()
    return true
  }
  return { result, pending, error, load, transition }
}
