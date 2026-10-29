import { afterEach, describe, expect, it, vi } from 'vitest'
import katex from 'katex'
import { BLOG_PARSE_CACHE_LIMITS, parseBlogMarkdown } from '../../../app/utils/markdown/parser'
import { buildBlogChapters } from '../../../app/utils/blog/chapters'

afterEach(() => vi.restoreAllMocks())

describe('bounded immutable reading result reuse', () => {
  it('reuses parsing/highlighting/math for identical content, but refreshes changed source', async () => {
    const render = vi.spyOn(katex, 'renderToString')
    const source = '# 缓存验证\n\n公式 $a^2$\n\n```java\nclass CachedArticle {}\n```'
    const first = await parseBlogMarkdown(source),
      snapshot = JSON.stringify(first)
    buildBlogChapters(first.nodes)
    const second = await parseBlogMarkdown(source)
    expect(second).toBe(first)
    expect(JSON.stringify(second)).toBe(snapshot)
    expect(render).toHaveBeenCalledOnce()
    // 首次解析与缓存命中均不等待高亮，着色由代码组件在后台单独补充。
    expect(snapshot).toContain('pending')
    const changed = await parseBlogMarkdown(source + '\n\n## 新章节')
    expect(changed).not.toBe(first)
    expect(buildBlogChapters(changed.nodes).byId.has('新章节')).toBe(true)
  })
  it('evicts old article results instead of growing without bounds', async () => {
    const source = '# 有界缓存基点'
    const first = await parseBlogMarkdown(source)
    for (let i = 0; i < BLOG_PARSE_CACHE_LIMITS.entries; i++) {
      await parseBlogMarkdown('# 有界缓存文章 ' + i)
    }
    expect(await parseBlogMarkdown(source)).not.toBe(first)
  })
})
