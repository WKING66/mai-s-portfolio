import { describe, expect, it } from 'vitest'
import { textContent } from 'comark/utils'
import {
  canvasSchema,
  importCanvasMarkdown,
  exportCanvasMarkdown,
} from '../utils/editor/canvasMarkdown'
import { IMMERSIVE_COMMANDS } from '../utils/editor/immersiveCommands'
import { parseBlogMarkdown } from '../utils/markdown'

describe('已取消的脚注语法仅保留为普通内容', () => {
  it('菜单和文档 Schema 不再提供脚注能力', () => {
    expect(IMMERSIVE_COMMANDS.some((item) => item.kind === 'footnote')).toBe(false)
    expect(Object.keys(canvasSchema.nodes).some((name) => /footnote/i.test(name))).toBe(false)
  })
  it.each([
    '正文[^note]\n\n[^note]: 说明文字\n\n    后续说明',
    '引用[^n]\n\n> [^n]: notes/note.md',
  ])('编辑导入和公开阅读都不消耗未知脚注定义：%s', async (source) => {
    const document = canvasSchema.nodeFromJSON(importCanvasMarkdown(source))
    expect(document.textContent).toContain(
      source.includes('说明文字') ? '[^note]: 说明文字' : '[^n]: notes/note.md',
    )
    const output = exportCanvasMarkdown(document.toJSON())
    for (const markdown of [source, output]) {
      const visible = (await parseBlogMarkdown(markdown)).nodes
        .map((node) => (typeof node === 'string' ? node : textContent(node)))
        .join('\n')
      expect(visible).toContain(
        source.includes('说明文字') ? '[^note]: 说明文字' : '[^n]: notes/note.md',
      )
    }
  })
  it('正常引用式链接和代码内原文继续保留', async () => {
    const document = canvasSchema.nodeFromJSON(
      importCanvasMarkdown(
        '[链接][target]\n\n[target]: https://example.com\n\n```md\n[^code]: untouched\n```',
      ),
    )
    const links: string[] = []
    document.descendants((node) => {
      for (const mark of node.marks) {
        if (mark.type.name === 'link') {
          links.push(mark.attrs.href)
        }
      }
    })
    expect(links).toEqual(['https://example.com'])
    expect(document.lastChild!.textContent).toBe('[^code]: untouched')
    expect(exportCanvasMarkdown(document.toJSON())).toContain('[^code]: untouched')
  })
})
