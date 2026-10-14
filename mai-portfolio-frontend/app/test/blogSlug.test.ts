import { describe, expect, it } from 'vitest'
import { blogSlugIssue } from '../utils/blogSlug'
describe('blog slug URL usability', () => {
  it('空标识可保存草稿，标题语言不参与 Slug 校验', () => {
    for (const value of ['', 'manual-slug', '技术笔记', 'blog-import-ui-qa-20261009-17:14']) {
      expect(blogSlugIssue(value)).toBe('')
    }
  })
  it('拒绝无法作为独立路径段的地址分隔符和不可见字符', () => {
    for (const value of [
      'bad slug',
      'bad/slug',
      'bad?slug',
      'bad#slug',
      'bad\\slug',
      'bad%2fslug',
      '\u0000slug',
      'slug\u200B',
      '.',
      '..',
    ]) {
      expect(blogSlugIssue(value)).not.toBe('')
    }
  })
  it('遵循既有 160 字符边界，不自动截断或更改输入', () => {
    expect(blogSlugIssue('a'.repeat(160))).toBe('')
    expect(blogSlugIssue('a'.repeat(161))).toContain('160')
  })
})
