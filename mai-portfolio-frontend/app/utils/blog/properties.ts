import type { FormError } from '@nuxt/ui'
import type { BlogPropertiesInput } from '../../api/blogs'
import { BLOG_INPUT_LIMITS } from '../../constants/blogs'
import { blogSlugIssue } from './slug'

export type BlogProperties = Omit<BlogPropertiesInput, 'revision' | 'requestId'>
export function sameBlogProperties(left: BlogProperties, right: BlogProperties) {
  return (
    left.title === right.title &&
    left.slug === right.slug &&
    left.summary === right.summary &&
    left.allowDownload === right.allowDownload &&
    JSON.stringify([...left.tagIds].sort()) === JSON.stringify([...right.tagIds].sort())
  )
}
export function validateBlogProperties(value: BlogProperties, savedSlug: string): FormError[] {
  const errors: FormError[] = []
  const issue = value.slug === savedSlug ? '' : blogSlugIssue(value.slug.trim() ? value.slug : '')
  if (issue) {
    errors.push({ name: 'slug', message: issue })
  }
  if (value.title.length > BLOG_INPUT_LIMITS.title) {
    errors.push({ name: 'title', message: `标题最多 ${BLOG_INPUT_LIMITS.title} 字。` })
  }
  if (value.summary.length > BLOG_INPUT_LIMITS.summary) {
    errors.push({ name: 'summary', message: `摘要最多 ${BLOG_INPUT_LIMITS.summary} 字。` })
  }
  return errors
}
