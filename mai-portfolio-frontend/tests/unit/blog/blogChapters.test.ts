import { describe, expect, it } from 'vitest'
import { buildBlogChapters, chapterAncestors, visibleToc } from '../../../app/utils/blog/chapters'
import { parseBlogMarkdown } from '../../../app/utils/markdown/parser'
import { CODE_FOLD } from '../../../app/constants/codeThemes'

describe('AST chapters and one-way TOC visibility', () => {
  it('omits only the leading duplicate article H1 from reading and TOC without modifying Markdown AST', async () => {
    const doc = await parseBlogMarkdown(
      '# **数据结构与算法**\n\n正文介绍\n\n## 第一章\n\n章节正文\n\n# 其他一级章节',
    )
    const original = JSON.stringify(doc.nodes)
    const grouped = buildBlogChapters(doc.nodes, '数据结构与算法')
    expect(grouped.chapters.map((chapter) => chapter.text)).toEqual(['第一章', '其他一级章节'])
    expect(grouped.nodes[0]![0]).toBe('p')
    expect(JSON.stringify(doc.nodes)).toBe(original)
    expect(buildBlogChapters(doc.nodes).chapters[0]!.text).toBe('数据结构与算法')
    expect(buildBlogChapters(doc.nodes, '另一个标题').chapters[0]!.text).toBe('数据结构与算法')
  })
  it.each([1, 2, 3, 4, 5, 6])(
    'recognizes the actual highest heading at H%i, including introductory content',
    async (depth) => {
      const doc = await parseBlogMarkdown(`介绍\n\n${'#'.repeat(depth)} **文章标题**\n\n正文`)
      const original = JSON.stringify(doc.nodes)
      const grouped = buildBlogChapters(doc.nodes, '文章标题')
      expect(grouped.chapters).toHaveLength(0)
      expect(grouped.nodes).toHaveLength(2)
      expect(JSON.stringify(doc.nodes)).toBe(original)
    },
  )
  it('keeps mismatches, later peer headings and same-name lower-level chapters', async () => {
    for (const markdown of [
      '# 其他标题\n\n## 文章标题',
      '## 第一章\n\n## 文章标题',
      '### 文章标题\n\n## 最高层级标题',
    ]) {
      const doc = await parseBlogMarkdown(markdown)
      expect(buildBlogChapters(doc.nodes, '文章标题').byId.size).toBe(2)
    }
    const doc = await parseBlogMarkdown('## 文章标题\n\n### 小节\n\n## 文章标题')
    const grouped = buildBlogChapters(doc.nodes, '文章标题')
    expect([...grouped.byId.values()].map((chapter) => chapter.text)).toEqual(['小节', '文章标题'])
  })
  it('does not use headings inside quotations or lists to determine the article heading', async () => {
    const doc = await parseBlogMarkdown('> # 引用标题\n\n- # 列表标题\n\n## 文章标题\n\n正文')
    const grouped = buildBlogChapters(doc.nodes, '文章标题')
    expect(grouped.chapters).toHaveLength(0)
    expect(grouped.nodes.map((node) => node[0])).toEqual(['blockquote', 'ul', 'p'])
  })
  it('does not duplicate MathML, annotation and visual text in mathematical heading labels', async () => {
    const grouped = buildBlogChapters((await parseBlogMarkdown('# 复杂度 $O(n^2)$')).nodes)
    expect(grouped.chapters[0]!.text).toBe('复杂度 O(n^2)')
  })
  it('groups H1–H6 until a same/higher heading without splitting nested blocks or changing source AST', async () => {
    const doc = await parseBlogMarkdown(
      'preamble\n\n# A\n\nbody\n\n### C\n\n> ## quoted\n\n###### F\n\nend\n\n## B\n\npeer\n\n# D',
    )
    const original = JSON.stringify(doc.nodes)
    const grouped = buildBlogChapters(doc.nodes)
    expect(grouped.chapters.map((ch) => ch.id)).toEqual(['a', 'd'])
    expect(grouped.byId.get('a')!.children.map((ch) => ch.id)).toEqual(['c', 'b'])
    expect(grouped.byId.get('c')!.children[0]!.id).toBe('f')
    expect(grouped.byId.has('quoted')).toBe(false)
    expect(chapterAncestors('f', grouped.byId)).toEqual(['c', 'a'])
    expect(grouped.byId.get('c')!.body[0]![0]).toBe('blockquote')
    expect(JSON.stringify(doc.nodes)).toBe(original)
    expect(grouped.nodes[0]![0]).toBe('p')
  })
  it('combines independent body and TOC preferences and selects only necessary body ancestors', async () => {
    const { chapters, byId } = buildBlogChapters(
      (await parseBlogMarkdown('# A\n\n## B\n\n### C\n\n## D\n\n# E')).nodes,
    )
    const body = new Set(['a', 'b']),
      toc = new Set(['b'])
    expect(visibleToc(chapters, body, toc).map((ch) => ch.id)).toEqual(['a', 'e'])
    body.delete('a')
    expect(visibleToc(chapters, body, toc).map((ch) => ch.id)).toEqual(['a', 'b', 'd', 'e'])
    for (const ancestor of chapterAncestors('c', byId)) {
      body.delete(ancestor)
    }
    expect([...toc]).toEqual(['b'])
    expect(visibleToc(chapters, body, toc).map((ch) => ch.id)).toEqual(['a', 'b', 'd', 'e'])
    toc.clear()
    expect(visibleToc(chapters, body, toc).map((ch) => ch.id)).toEqual(['a', 'b', 'c', 'd', 'e'])
  })
  it('uses a bounded long-code preview without replacing the full source', () => {
    expect(CODE_FOLD.threshold).toBe(20)
    expect(CODE_FOLD.previewLines).toBeGreaterThanOrEqual(12)
    expect(CODE_FOLD.previewLines).toBeLessThanOrEqual(16)
  })
})
