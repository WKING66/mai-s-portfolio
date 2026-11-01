import type { BlogCard } from '../../api/blogs'

/** 整篇删除沿用服务端生命周期限制；后端仍会原子校验当前状态和修订号。 */
export function blogDeletionBlocker(
  article: Pick<BlogCard, 'status' | 'hasPublished' | 'hasDraft' | 'revision'>,
): string {
  if (article.status === 'PUBLISHED') {
    return '请先下架文章，再删除。'
  }
  if (article.hasPublished && article.hasDraft) {
    return '文章仍有工作草稿，请先在正文编辑器发布或丢弃草稿，再删除。'
  }
  if (!Number.isSafeInteger(article.revision) || !article.revision || article.revision < 1) {
    return '缺少有效修订号，请刷新列表后重试。'
  }
  return ''
}
