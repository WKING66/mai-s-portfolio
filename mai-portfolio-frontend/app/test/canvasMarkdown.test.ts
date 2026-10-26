import { describe, expect, it } from 'vitest'
import {
  canvasSchema,
  importCanvasMarkdown,
  exportCanvasMarkdown,
} from '../utils/editor/canvasMarkdown'
import { editorContentMeaning } from '../utils/editor/markdownOutput'

describe('单画布 Markdown 边界契约', () => {
  it.each(['', ' \n\n\t'])('空草稿可导入为可编辑段落并保存为空 Markdown：%j', (source) => {
    const document = importCanvasMarkdown(source)
    expect(document.content).toEqual([{ type: 'paragraph' }])
    expect(exportCanvasMarkdown(document)).toBe('')
  })
  it.each([
    '***组合强调*** 与 **文字 $x^2$ 图片 ![图](resource://image)**',
    '1\\. 普通数字段落\n\n- 父项\n      - 深层子项\n\n*斜体 **内层加粗** 继续*',
    '# 一级\n\n## 二级\n\n正文 **加粗** *斜体* ~~删除~~ `code` [链接](https://example.com)\n\n---',
    '- 项目\n  - 子项\n\n> 引用\n>\n> > 嵌套引用\n\n- [x] 完成\n- [ ] 未完成',
    '| A | B |\n|:---|---:|\n| $x^2$ | **加粗** |',
    '~~~ts title="article.ts"\nconst code = "```"\n~~~\n\n~~~mermaid\nflowchart LR\n A --> B\n~~~',
    '公式 $ $ 与 $x^2$\n\n$$\n\\begin{aligned}a&=b\\\\c&=d\\end{aligned}\n$$',
    ...['NOTE', 'TIP', 'IMPORTANT', 'WARNING', 'CAUTION'].map(
      (kind) => '> [!' + kind + ']\n> 注意 **正文**\n>\n> - 条目',
    ),
  ])('支持语法经过编辑文档与 Markdown 双向转换保持语义 %#', (source) => {
    const document = importCanvasMarkdown(source)
    const markdown = exportCanvasMarkdown(document)
    if (source.startsWith('> [!')) {
      expect(document.content?.[0]?.type).toBe('canvasAlert')
    }
    expect(editorContentMeaning(importCanvasMarkdown(markdown))).toEqual(
      editorContentMeaning(document),
    )
    expect(JSON.stringify(document)).not.toContain('preservedMarkdownBlock')
  })
  it('未知 HTML 仅作为可编辑字面文本，保存后不会重建可执行 DOM', () => {
    const parsed = importCanvasMarkdown(
      '<script>alert(1)</script>\n\n正文 <span onclick="evil()">文字</span>',
    )
    const document = canvasSchema.nodeFromJSON(parsed)
    expect(document.textContent).toContain('<script>alert(1)</script>')
    const markdown = exportCanvasMarkdown(parsed)
    expect(markdown).toMatch(/(?:&lt;|\\<)script(?:&gt;|>)/)
    expect(editorContentMeaning(importCanvasMarkdown(markdown))).toEqual(
      editorContentMeaning(parsed),
    )
  })
})
