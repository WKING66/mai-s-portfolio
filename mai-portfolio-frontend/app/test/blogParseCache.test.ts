import { afterEach, describe, expect, it } from 'vitest'
import { BLOG_PARSE_CACHE_LIMITS, parseBlogMarkdown } from '../utils/markdown'
import { buildBlogChapters } from '../utils/blogChapters'

afterEach(() => { globalThis.__blogReadingProbe = undefined })
describe('bounded immutable reading result reuse', () => {
  it('reuses parsing/highlighting/math for identical content, but refreshes changed source', async () => {
    const counts: Record<string, number> = {}
    globalThis.__blogReadingProbe = name => { counts[name] = (counts[name] || 0) + 1 }
    const source = '# 缓存验证\n\n公式 $a^2$\n\n```java\nclass CachedArticle {}\n```'
    const first = await parseBlogMarkdown(source), snapshot = JSON.stringify(first)
    buildBlogChapters(first.nodes)
    const second = await parseBlogMarkdown(source)
    expect(second).toBe(first)
    expect(JSON.stringify(second)).toBe(snapshot)
    expect(counts.markdownParse).toBe(1)
    expect(counts.katexRender).toBe(1)
    expect(counts.highlightRequest).toBe(1)
    expect(counts.parseCacheHit).toBe(1)
    const changed = await parseBlogMarkdown(source + '\n\n## 新章节')
    expect(changed).not.toBe(first)
    expect(buildBlogChapters(changed.nodes).byId.has('新章节')).toBe(true)
  })
  it('evicts old article results instead of growing without bounds', async () => {
    const source = '# 有界缓存基点'
    const first = await parseBlogMarkdown(source)
    for (let i = 0; i < BLOG_PARSE_CACHE_LIMITS.entries; i++) await parseBlogMarkdown('# 有界缓存文章 ' + i)
    expect(await parseBlogMarkdown(source)).not.toBe(first)
  })
})
