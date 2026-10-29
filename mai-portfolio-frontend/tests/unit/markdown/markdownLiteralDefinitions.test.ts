import { describe, expect, it } from 'vitest'
import { textContent } from 'comark/utils'
import { parseBlogMarkdown } from '../../../app/utils/markdown/parser'
import { createBlogMarkdownManager } from '../../../app/utils/editor/markdown/markdownCompatibility'

describe('不支持的脚注定义只作普通阅读文字', () => {
  it('段落中的转义方括号不会截断为块公式，独立块公式仍可导入', () => {
    const manager = createBlogMarkdownManager()
    const paragraph = manager.parse('正文\\[普通\\]').content![0]!
    expect(paragraph.type).toBe('paragraph')
    expect(paragraph.content).toEqual([{ type: 'text', text: '正文[普通]' }])
    expect(manager.parse('\\[x^2\\]').content![0]!.type).toBe('preservedMathBlock')
  })
  it.each(['正文[^n]\n\n[^n]: 说明文字', '> [^n]: 说明文字'])(
    '保留定义和标记，不作为隐形链接定义：%s',
    async (source) => {
      const tree = await parseBlogMarkdown(source)
      const text = tree.nodes
        .map((node) => (typeof node === 'string' ? node : textContent(node)))
        .join('\n')
      expect(text).toContain('[^n]: 说明文字')
      expect(JSON.stringify(tree.nodes)).not.toContain('footnote-ref')
    },
  )
  it('普通引用式链接和代码内容不受影响', async () => {
    const tree = await parseBlogMarkdown(
      '[链接][target]\n\n[target]: https://example.com\n\n```md\n[^n]: code\n```',
    )
    expect(JSON.stringify(tree.nodes)).toContain('https://example.com')
    expect(JSON.stringify(tree.nodes)).toContain('[^n]: code')
  })
})
