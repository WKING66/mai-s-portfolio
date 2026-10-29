import { BLOG_INPUT_LIMITS } from '../../constants/blogs'

/** 不生成或音译 Slug；仅检查独立 URL 路径段的可用性，唯一性由服务端负责。 */
export function blogSlugIssue(slug: string): string {
  // 草稿允许空标识；发布前另行检查完整性。
  if (!slug) {
    return ''
  }
  if (slug.length > BLOG_INPUT_LIMITS.slug) {
    return `文章标识不能超过 ${BLOG_INPUT_LIMITS.slug} 个字符。`
  }
  if (/[\s/?#\\%\p{C}]/u.test(slug) || slug === '.' || slug === '..') {
    return '文章标识不能包含空格、控制字符或 / ? # \\ %，也不能为 . 或 ..。'
  }
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(slug)) {
    return '文章标识仅支持小写字母、数字和短横线。'
  }
  return ''
}
