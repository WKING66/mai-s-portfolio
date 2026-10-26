import { describe, expect, it } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import { CellSelection, TableMap } from '@tiptap/pm/tables'
import { closeHistory } from '@tiptap/pm/history'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT } from '../utils/editor/markdownExtensions'
import { createBlogMarkdownOptions } from '../utils/editor/markdownCompatibility'
import { ImmersiveMarkdownGuard } from '../utils/editor/immersiveCapabilities'
import {
  ImmersiveSelection,
  selectImmersiveAxes,
  immersiveAxisSelection,
  immersiveTextRanges,
  formatImmersiveText,
} from '../utils/editor/immersiveSelection'
import {
  clearImmersiveCells,
  editImmersiveAxes,
  immersiveAxisProblem,
} from '../utils/editor/immersiveTables'
import { readEditorMarkdown } from '../utils/editor/markdownOutput'
import { immersiveLinkRange } from '../utils/editor/immersiveLinks'
const source =
  '| 姓名 | 技术 | 备注 |\n|---|:---:|---:|\n| 张三 | Java | $2^{i-1}$ |\n| 李四 | Python | [链接](/blog) |\n| 王五 | Vue | 前端 |\n| 赵六 | Rust | 后端 |\n| 陈七 | TS | 备注五 |\n| 刘八 | JS | 备注六 |'
function createEditor() {
  const editor = new Editor({
    element: null,
    extensions: [
      StarterKit.configure(EDITOR_STARTER_KIT),
      ...BLOG_EDITOR_EXTENSIONS,
      Markdown.configure(createBlogMarkdownOptions()),
      ImmersiveSelection,
      ImmersiveMarkdownGuard,
    ],
    content: source,
    contentType: 'markdown',
  })
  editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
  return editor
}
function markMatrix(editor: Editor, name: string) {
  return editor.state.doc.firstChild!.children.map((row) =>
    row.children.map((cell) => {
      let marked = false
      cell.descendants((node) => {
        marked ||= node.isText && node.marks.some((mark) => mark.type.name === name)
      })
      return marked
    }),
  )
}
describe('统一表格文字范围与批量操作', () => {
  it('矩形单元格清空只修改目标内容，保留原表格结构并可撤销', () => {
    const editor = createEditor()
    try {
      const before = editor.state.doc
      const map = TableMap.get(before.firstChild!)
      editor.view.dispatch(
        editor.state.tr.setSelection(
          CellSelection.create(before, 1 + map.map[3]!, 1 + map.map[7]!),
        ),
      )
      expect(editor.commands.command(clearImmersiveCells())).toBe(true)
      expect(editor.state.doc.firstChild!.childCount).toBe(7)
      expect(
        editor.state.doc.firstChild!.child(1).children.map((cell) => cell.textContent),
      ).toEqual(['', '', ''])
      expect(
        editor.state.doc.firstChild!.child(2).children.map((cell) => cell.textContent),
      ).toEqual(['', '', '链接'])
      expect(readEditorMarkdown(editor, editor.getMarkdown())).toContain('$2^{i-1}$')
      editor.commands.undo()
      expect(editor.state.doc.eq(before)).toBe(true)
    } finally {
      editor.destroy()
    }
  })
  it('部分单元格文字与原生矩形 CellSelection 共用真实文本范围', () => {
    const editor = createEditor()
    try {
      let java = 0
      editor.state.doc.descendants((node, position) => {
        if (node.isText && node.text === 'Java') {
          java = position
        }
      })
      editor.commands.setTextSelection({ from: java + 1, to: java + 3 })
      expect(editor.commands.command(formatImmersiveText('bold'))).toBe(true)
      expect(readEditorMarkdown(editor, editor.getMarkdown())).toContain('J**av**a')
      editor.commands.undo()
      const map = TableMap.get(editor.state.doc.firstChild!)
      editor.view.dispatch(
        editor.state.tr.setSelection(
          CellSelection.create(editor.state.doc, 1 + map.map[3]!, 1 + map.map[7]!),
        ),
      )
      editor.commands.command(formatImmersiveText('strike'))
      expect(markMatrix(editor, 'strike')).toEqual([
        [false, false, false],
        [true, true, false],
        [true, true, false],
        [false, false, false],
        [false, false, false],
        [false, false, false],
        [false, false, false],
      ])
      expect(readEditorMarkdown(editor, editor.getMarkdown())).toContain('~~Java~~')
    } finally {
      editor.destroy()
    }
  })
  it('批量行内代码保留链接地址与公式 LaTeX', () => {
    const editor = createEditor()
    try {
      editor.commands.command(selectImmersiveAxes(0, 'column', 2))
      expect(editor.commands.command(formatImmersiveText('code'))).toBe(true)
      const markdown = readEditorMarkdown(editor, editor.getMarkdown())
      expect(markdown).toContain('](/blog)')
      expect(markMatrix(editor, 'code')[2]![2]).toBe(true)
      expect(markdown).toContain('$2^{i-1}$')
      expect(markdown).toContain('\x60前端\x60')
      editor.commands.undo()
      expect(markMatrix(editor, 'code').flat().some(Boolean)).toBe(false)
    } finally {
      editor.destroy()
    }
  })

  it('整列加粗只修改该列，Undo/Redo 恢复实际 Markdown', () => {
    const editor = createEditor()
    try {
      editor.commands.command(selectImmersiveAxes(0, 'column', 1))
      expect(immersiveTextRanges(editor.state)).toHaveLength(7)
      expect(immersiveLinkRange(editor)).toBeNull()
      editor.view.dispatch(closeHistory(editor.state.tr))
      editor.commands.command(formatImmersiveText('bold'))
      expect(markMatrix(editor, 'bold')).toEqual(
        Array.from({ length: 7 }, () => [false, true, false]),
      )
      expect(readEditorMarkdown(editor, editor.getMarkdown())).toContain('**Java**')
      editor.commands.undo()
      expect(markMatrix(editor, 'bold').flat().some(Boolean)).toBe(false)
      editor.commands.redo()
      expect(markMatrix(editor, 'bold')[2]).toEqual([false, true, false])
    } finally {
      editor.destroy()
    }
  })
  it('Ctrl 非连续第2/4/6行斜体不触及间隔行，公式与链接保持原样', () => {
    const editor = createEditor()
    try {
      editor.commands.command(selectImmersiveAxes(0, 'row', 2))
      editor.commands.command(selectImmersiveAxes(0, 'row', 4, 'toggle'))
      editor.commands.command(selectImmersiveAxes(0, 'row', 6, 'toggle'))
      expect(immersiveAxisSelection(editor.state)?.indices).toEqual([2, 4, 6])
      editor.commands.command(formatImmersiveText('italic'))
      expect(markMatrix(editor, 'italic')).toEqual(
        Array.from({ length: 7 }, (_, row) => [
          row % 2 === 0 && row > 0,
          row % 2 === 0 && row > 0,
          row % 2 === 0 && row > 0,
        ]),
      )
      const markdown = readEditorMarkdown(editor, editor.getMarkdown())
      expect(markdown).toContain('$2^{i-1}$')
      expect(markdown).toContain('/blog')
      editor.commands.command(formatImmersiveText('clear'))
      expect(markMatrix(editor, 'italic').flat().some(Boolean)).toBe(false)
    } finally {
      editor.destroy()
    }
  })
  it('非连续批量删除精确删除目标行，一次撤销恢复全部原文', () => {
    const editor = createEditor()
    try {
      const before = editor.state.doc
      for (const [index, mode] of [
        [2, 'replace'],
        [4, 'toggle'],
        [6, 'toggle'],
      ] as const) {
        editor.commands.command(selectImmersiveAxes(0, 'row', index, mode))
      }
      editor.view.dispatch(closeHistory(editor.state.tr))
      expect(editor.commands.command(editImmersiveAxes('delete'))).toBe(true)
      expect(
        editor.state.doc.firstChild!.children.map((row) => row.firstChild!.textContent),
      ).toEqual(['姓名', '张三', '王五', '陈七'])
      expect(() => readEditorMarkdown(editor, editor.getMarkdown())).not.toThrow()
      editor.commands.undo()
      expect(editor.state.doc.eq(before)).toBe(true)
    } finally {
      editor.destroy()
    }
  })
  it('Shift 连选与非连续列集合移动顺序确定，清空不改变表格结构', () => {
    const editor = createEditor()
    try {
      editor.commands.command(selectImmersiveAxes(0, 'row', 2))
      editor.commands.command(selectImmersiveAxes(0, 'row', 4, 'extend'))
      expect(immersiveAxisSelection(editor.state)?.indices).toEqual([2, 3, 4])
      editor.commands.command(selectImmersiveAxes(0, 'column', 0))
      editor.commands.command(selectImmersiveAxes(0, 'column', 2, 'toggle'))
      expect(editor.commands.command(editImmersiveAxes('move', 3))).toBe(true)
      expect(
        editor.state.doc.firstChild!.firstChild!.children.map((cell) => cell.textContent),
      ).toEqual(['技术', '姓名', '备注'])
      expect(immersiveAxisSelection(editor.state)?.indices).toEqual([1, 2])
      expect(readEditorMarkdown(editor, editor.getMarkdown())).toContain('$2^{i-1}$')
      expect(editor.commands.command(editImmersiveAxes('clear'))).toBe(true)
      expect(
        editor.state.doc.firstChild!.child(1).children.map((cell) => cell.textContent),
      ).toEqual(['Java', '', ''])
      expect(editor.state.doc.firstChild!.childCount).toBe(7)
      expect(() => readEditorMarkdown(editor, editor.getMarkdown())).not.toThrow()
    } finally {
      editor.destroy()
    }
  })
  it('表头与删除全部列的拒绝路径不改变正文，公式原子节点不被加粗', () => {
    const editor = createEditor()
    try {
      const before = editor.state.doc
      editor.commands.command(selectImmersiveAxes(0, 'row', 0))
      expect(immersiveAxisProblem(editor.state, 'delete')).toContain('表头')
      expect(editor.commands.command(editImmersiveAxes('delete'))).toBe(false)
      expect(editor.state.doc.eq(before)).toBe(true)
      editor.commands.command(selectImmersiveAxes(0, 'column', 0))
      editor.commands.command(selectImmersiveAxes(0, 'column', 2, 'extend'))
      expect(editor.commands.command(editImmersiveAxes('delete'))).toBe(false)
      editor.commands.command(selectImmersiveAxes(0, 'row', 1))
      editor.commands.command(formatImmersiveText('bold'))
      const formula = editor.state.doc.firstChild!.child(1).child(2).firstChild!.firstChild!
      expect(formula.attrs.source).toBe('$2^{i-1}$')
      expect(formula.marks).toEqual([])
      expect(() => readEditorMarkdown(editor, editor.getMarkdown())).not.toThrow()
    } finally {
      editor.destroy()
    }
  })
})
