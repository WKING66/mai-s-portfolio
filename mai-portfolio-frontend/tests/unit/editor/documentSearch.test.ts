import { describe, expect, it, vi } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import { undo, redo } from '@tiptap/pm/history'
import {
  CANVAS_EDITOR_EXTENSIONS,
  CANVAS_STARTER_KIT,
  createCanvasMarkdownOptions,
} from '../../../app/utils/editor/markdown/canvasMarkdown'
import { ImmersiveMarkdownGuard } from '../../../app/utils/editor/interaction/immersiveCapabilities'
import {
  findCanvasText,
  replaceCanvasText,
} from '../../../app/utils/editor/interaction/documentSearch'

function setup(source: string) {
  const editor = new Editor({
    element: null,
    extensions: [
      StarterKit.configure(CANVAS_STARTER_KIT),
      ...CANVAS_EDITOR_EXTENSIONS,
      Markdown.configure(createCanvasMarkdownOptions()),
      ImmersiveMarkdownGuard.configure({ roundtrip: false }),
    ],
    content: source,
    contentType: 'markdown',
  })
  editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
  vi.spyOn(editor, 'isDestroyed', 'get').mockReturnValue(false)
  return editor
}
function history(editor: Editor, command: typeof undo) {
  expect(command(editor.state, editor.view.dispatch)).toBe(true)
}
const fixture =
  '# 目标标题\n\n前文目标，**目标**。\n\n- 列表目标\n  - 子项目标\n\n| A | B |\n|---|---|\n| 表格目标 | 不变 |\n\n~~~c [目标.c]\n// 代码目标\nint n = 1;\n~~~\n\n$$\n目标\n$$\n\n~~~mermaid\nflowchart LR; A[目标] --> B\n~~~\n\n后文目标'

describe('正文搜索与真实替换事务', () => {
  it('匹配正文标题、列表、单元格和代码，忽略公式、Mermaid 及节点属性', () => {
    const editor = setup(fixture)
    try {
      const matches = findCanvasText(editor.state.doc, '目标')
      expect(matches).toHaveLength(8)
      expect(matches.filter((match) => match.code !== undefined)).toHaveLength(1)
      expect(findCanvasText(editor.state.doc, '目标.c')).toHaveLength(0)
      matches.forEach((match) =>
        expect(editor.state.doc.textBetween(match.from, match.to)).toBe('目标'),
      )
    } finally {
      editor.destroy()
    }
  })
  it('全部替换跨复杂块后一次撤销全部恢复，一次重做完整重放', () => {
    const editor = setup(fixture)
    try {
      const before = editor.state.doc
      const getJSON = vi.spyOn(editor, 'getJSON')
      const getMarkdown = vi.spyOn(editor, 'getMarkdown')
      const matches = findCanvasText(before, '目标')
      expect(replaceCanvasText(editor, before, matches, '结果')).toBe(8)
      const replaced = editor.state.doc
      expect(findCanvasText(replaced, '目标')).toHaveLength(0)
      expect(getJSON).not.toHaveBeenCalled()
      expect(getMarkdown).not.toHaveBeenCalled()
      expect(findCanvasText(replaced, '结果')).toHaveLength(8)
      const atoms: string[] = []
      replaced.descendants((node) => {
        if (node.type.name === 'preservedMathBlock') {
          atoms.push(node.attrs.source)
        }
        if (node.type.name === 'codeBlock' && String(node.attrs.language).startsWith('mermaid')) {
          atoms.push(node.textContent)
        }
      })
      expect(atoms.join('\n')).toContain('目标')
      history(editor, undo)
      expect(editor.state.doc.eq(before)).toBe(true)
      history(editor, redo)
      expect(editor.state.doc.eq(replaced)).toBe(true)
    } finally {
      editor.destroy()
    }
  })
  it('全部替换与之前、之后的连续输入分别撤销，不混成一组', () => {
    const editor = setup('目标前文\n\n后文目标')
    try {
      const original = editor.state.doc
      editor.view.dispatch(editor.state.tr.insertText('先输入', 1))
      const typed = editor.state.doc
      replaceCanvasText(editor, typed, findCanvasText(typed, '目标'), '替换')
      const replaced = editor.state.doc
      editor.view.dispatch(editor.state.tr.insertText('后输入', editor.state.doc.content.size - 1))
      const later = editor.state.doc
      history(editor, undo)
      expect(editor.state.doc.eq(replaced)).toBe(true)
      history(editor, undo)
      expect(editor.state.doc.eq(typed)).toBe(true)
      history(editor, undo)
      expect(editor.state.doc.eq(original)).toBe(true)
      history(editor, redo)
      history(editor, redo)
      history(editor, redo)
      expect(editor.state.doc.eq(later)).toBe(true)
    } finally {
      editor.destroy()
    }
  })
  it('跨样式匹配继承首字符样式，范围外的内容、链接和格式保持', () => {
    const editor = setup('正文')
    try {
      editor.commands.setContent({
        type: 'doc',
        content: [
          {
            type: 'paragraph',
            content: [
              { type: 'text', text: '前' },
              { type: 'text', text: '甲', marks: [{ type: 'bold' }] },
              { type: 'text', text: '乙', marks: [{ type: 'italic' }] },
              {
                type: 'text',
                text: '后',
                marks: [{ type: 'link', attrs: { href: 'https://example.com/甲乙' } }],
              },
            ],
          },
        ],
      })
      const before = editor.state.doc
      replaceCanvasText(editor, before, findCanvasText(before, '甲乙'), '新字')
      const paragraph = editor.state.doc.firstChild!
      expect(paragraph.child(1).text).toBe('新字')
      expect(paragraph.child(1).marks.map((mark) => mark.type.name)).toEqual(['bold'])
      expect(paragraph.lastChild!.marks[0]!.attrs.href).toBe('https://example.com/甲乙')
      history(editor, undo)
      expect(editor.state.doc.eq(before)).toBe(true)
    } finally {
      editor.destroy()
    }
  })
  it('软换行、不同段落和原子节点之间不拼接匹配', () => {
    const editor = setup('甲  \n乙\n\n甲\n\n乙\n\n甲$x$乙\n\n甲![图](resource://image)乙')
    try {
      expect(findCanvasText(editor.state.doc, '甲乙')).toHaveLength(0)
    } finally {
      editor.destroy()
    }
  })
  it('空替换删除文字后仍保持段落与表格结构，撤销恢复', () => {
    const editor = setup('目标\n\n| A |\n|---|\n| 目标 |')
    try {
      const before = editor.state.doc
      expect(replaceCanvasText(editor, before, findCanvasText(before, '目标'), '')).toBe(2)
      expect(editor.state.doc.firstChild!.type.name).toBe('paragraph')
      expect(editor.state.doc.child(1).type.name).toBe('table')
      expect(() => editor.state.doc.check()).not.toThrow()
      history(editor, undo)
      expect(editor.state.doc.eq(before)).toBe(true)
    } finally {
      editor.destroy()
    }
  })
  it('原文相同或没有命中不制造文档变更和撤销步骤', () => {
    const editor = setup('目标')
    try {
      const before = editor.state.doc
      expect(replaceCanvasText(editor, before, findCanvasText(before, '目标'), '目标')).toBe(0)
      expect(editor.state.doc).toBe(before)
      expect(undo(editor.state)).toBe(false)
    } finally {
      editor.destroy()
    }
  })
  it('旧文档快照、只读状态与非法换行替换都拒绝写入', () => {
    const editor = setup('目标')
    try {
      const before = editor.state.doc
      const matches = findCanvasText(before, '目标')
      editor.view.dispatch(editor.state.tr.insertText('新', 1))
      const current = editor.state.doc
      expect(() => replaceCanvasText(editor, before, matches, '旧覆盖')).toThrow('正文已变化')
      expect(editor.state.doc).toBe(current)
      expect(() =>
        replaceCanvasText(editor, current, findCanvasText(current, '目标'), '\n'),
      ).toThrow('不支持换行')
      editor.setEditable(false, false)
      expect(() =>
        replaceCanvasText(editor, current, findCanvasText(current, '目标'), '只读修改'),
      ).toThrow()
      expect(editor.state.doc).toBe(current)
    } finally {
      editor.destroy()
    }
  })
})
