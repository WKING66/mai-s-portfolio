import { describe, expect, it } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT } from '../utils/editor/markdownExtensions'
import { createBlogMarkdownOptions } from '../utils/editor/markdownCompatibility'
import {
  immersiveTableContext,
  changeImmersiveTable,
  selectImmersiveAxis,
  moveImmersiveAxis,
  isImmersiveTextSelection,
} from '../utils/editor/immersiveTables'
import { TextSelection } from '@tiptap/pm/state'
import { ImmersiveMarkdownGuard } from '../utils/editor/immersiveCapabilities'
import { closeHistory } from '@tiptap/pm/history'
import { CellSelection } from '@tiptap/pm/tables'
import {
  IMMERSIVE_COMMANDS,
  immersiveCommandProblem,
  executeImmersiveCommand,
  nextImmersiveMenuIndex,
} from '../utils/editor/immersiveCommands'
import { immersiveLinkRange } from '../utils/editor/immersiveLinks'
import { readEditorMarkdown } from '../utils/editor/markdownOutput'

export function interactionEditor(source: string) {
  return new Editor({
    element: null,
    extensions: [
      StarterKit.configure(EDITOR_STARTER_KIT),
      ...BLOG_EDITOR_EXTENSIONS,
      ImmersiveMarkdownGuard,
      Markdown.configure(createBlogMarkdownOptions()),
    ],
    content: source || { type: 'doc', content: [{ type: 'paragraph' }] },
    contentType: 'markdown',
  })
}
describe('Showcase 定向交互的 Markdown 边界', () => {
  it('表格单元格中的正文 Slash 成功清理命令，结构和其他单元格不变', () => {
    const editor = interactionEditor('| A | B |\n|---|---|\n| /正文 | 原文 |')
    try {
      let slash = 0
      editor.state.doc.descendants((node, pos) => {
        if (node.text === '/正文') {
          slash = pos
        }
      })
      editor.commands.setTextSelection(slash + 3)
      const target = { kind: 'slash' as const, from: slash, to: slash + 3, query: '正文' }
      const paragraph = IMMERSIVE_COMMANDS.find((item) => item.kind === 'paragraph')!
      const before = editor.state.doc
      expect(immersiveCommandProblem(editor, target, paragraph)).toBe('')
      expect(editor.state.doc).toBe(before)
      expect(executeImmersiveCommand(editor, target, paragraph)).toBe('')
      expect(editor.state.doc.firstChild!.childCount).toBe(2)
      expect(editor.state.doc.firstChild!.child(1).child(1).textContent).toBe('原文')
      expect(readEditorMarkdown(editor, editor.getMarkdown())).not.toContain('/正文')
    } finally {
      editor.destroy()
    }
  })
  it('加号菜单预检不改正文，确认后在目标前插入且没有临时斜杠', () => {
    const editor = interactionEditor('首段\n\n目标段\n\n末段')
    try {
      const target = {
        kind: 'block' as const,
        from: editor.state.doc.firstChild!.nodeSize,
        to: 0,
        query: '',
      }
      const item = IMMERSIVE_COMMANDS.find((entry) => entry.kind === 'codeBlock')!
      const before = editor.state.doc
      expect(immersiveCommandProblem(editor, target, item)).toBe('')
      expect(editor.state.doc).toBe(before)
      expect(executeImmersiveCommand(editor, target, item)).toBe('')
      expect(editor.state.doc.child(1).type.name).toBe('codeBlock')
      expect(editor.state.doc.child(2).textContent).toBe('目标段')
      expect(readEditorMarkdown(editor, editor.getMarkdown())).not.toContain('/')
    } finally {
      editor.destroy()
    }
  })
  it('空段落加号不生成多余占位；方向键跳过所有禁用项', () => {
    const editor = interactionEditor('')
    try {
      expect(
        executeImmersiveCommand(
          editor,
          { kind: 'block', from: 0, to: 0, query: '' },
          IMMERSIVE_COMMANDS.find((entry) => entry.kind === 'codeBlock')!,
        ),
      ).toBe('')
      expect(editor.state.doc.childCount).toBe(1)
      expect(nextImmersiveMenuIndex([false, true, false], 0, -1)).toBe(2)
      expect(nextImmersiveMenuIndex([false, true, false], 0, 1)).toBe(2)
      expect(nextImmersiveMenuIndex([true, true], 0, 1)).toBe(-1)
    } finally {
      editor.destroy()
    }
  })
  it('表格内允许行内公式，块级代码在实际执行前禁用且原文不改', () => {
    const editor = interactionEditor('| A | B |\n|---|---|\n| / | y |')
    try {
      let slash = 0
      editor.state.doc.descendants((node, position) => {
        if (node.isText && node.text === '/') {
          slash = position
        }
      })
      editor.commands.setTextSelection(slash + 1)
      const target = { kind: 'slash' as const, from: slash, to: slash + 1, query: '' }
      const code = IMMERSIVE_COMMANDS.find((entry) => entry.kind === 'codeBlock')!
      const before = editor.state.doc
      expect(executeImmersiveCommand(editor, target, code)).toContain('单元格')
      expect(editor.state.doc).toBe(before)
      expect(
        executeImmersiveCommand(
          editor,
          target,
          IMMERSIVE_COMMANDS.find((entry) => entry.kind === 'mathInline')!,
        ),
      ).toBe('')
      expect(readEditorMarkdown(editor, editor.getMarkdown())).toContain('$x^2$')
    } finally {
      editor.destroy()
    }
  })
  it('行列增删定位到新单元格；整行选择和原生重排可以往返及撤销', () => {
    const editor = interactionEditor('| A | B |\n|---|---|\n| one | two |\n| three | four |')
    editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
    try {
      let cell = 0
      editor.state.doc.descendants((node, position) => {
        if (node.isText && node.text === 'one') {
          cell = position
        }
      })
      editor.commands.setTextSelection(cell)
      expect(editor.commands.command(changeImmersiveTable('rowBefore'))).toBe(true)
      expect(immersiveTableContext(editor.state.doc, editor.state.selection)?.row).toBe(1)
      expect(editor.state.selection.$from.parent.textContent).toBe('')
      expect(editor.commands.command(changeImmersiveTable('columnAfter'))).toBe(true)
      expect(immersiveTableContext(editor.state.doc, editor.state.selection)?.column).toBe(1)
      editor.commands.insertContent({ type: 'text', text: '新增' })
      expect(readEditorMarkdown(editor, editor.getMarkdown())).toContain('新增')
      expect(editor.commands.command(selectImmersiveAxis('row', 2))).toBe(true)
      expect(editor.state.selection instanceof CellSelection).toBe(true)
      editor.view.dispatch(closeHistory(editor.state.tr))
      const before = editor.state.doc
      expect(editor.commands.command(moveImmersiveAxis('row', 2, 3))).toBe(true)
      expect(editor.state.doc.eq(before)).toBe(false)
      expect(() => readEditorMarkdown(editor, editor.getMarkdown())).not.toThrow()
      editor.commands.undo()
      expect(editor.state.doc.eq(before)).toBe(true)
      expect(editor.commands.command(moveImmersiveAxis('row', 1, 0))).toBe(false)
      expect(editor.commands.command(moveImmersiveAxis('column', 0, 2))).toBe(true)
      expect(() => readEditorMarkdown(editor, editor.getMarkdown())).not.toThrow()
    } finally {
      editor.destroy()
    }
  })
  it('点击链接起点也能得到完整链接范围及当前地址', () => {
    const editor = interactionEditor('前文 [链接](https://example.com) 后文')
    try {
      let position = 0
      editor.state.doc.descendants((node, offset) => {
        if (node.marks.some((mark) => mark.type.name === 'link')) {
          position = offset
        }
      })
      const range = immersiveLinkRange(editor, position)
      expect(range?.href).toBe('https://example.com')
      expect(editor.state.doc.textBetween(range!.from, range!.to)).toBe('链接')
    } finally {
      editor.destroy()
    }
  })
  it('表格内的幂、上下标均解析为行内数学节点，编辑后仍可序列化', () => {
    const source =
      '| 问题 | 结果 |\n| --- | --- |\n| A | $2^{i-1}$ |\n| B | $2^{h+1}-1$ |\n| C | $2n-1$ |\n| D | $2^h$ |'
    const editor = interactionEditor(source)
    try {
      const formulas: string[] = []
      editor.state.doc.descendants((node) => {
        if (node.type.name === 'preservedMathInline') {
          formulas.push(node.attrs.source)
        }
      })
      expect(formulas).toEqual(['$2^{i-1}$', '$2^{h+1}-1$', '$2n-1$', '$2^h$'])
      expect(() => readEditorMarkdown(editor, editor.getMarkdown())).not.toThrow()
    } finally {
      editor.destroy()
    }
  })
  it('块公式的下标、多行对齐和反斜线逐字保留', () => {
    const source = String.raw`$$
\begin{aligned}
n &= n_0+n_1+n_2 \\
  &= n_0+n_1+(n_0-1) \\
  &= 2n_0+n_1-1
\end{aligned}
$$`
    const editor = interactionEditor(source)
    try {
      expect(editor.state.doc.firstChild?.type.name).toBe('preservedMathBlock')
      expect(editor.state.doc.firstChild?.attrs.source).toBe(source)
      expect(readEditorMarkdown(editor, editor.getMarkdown())).toBe(source)
    } finally {
      editor.destroy()
    }
  })
  it('单击不触发格式菜单，含文字的单元格与结构选区支持格式操作', () => {
    const editor = interactionEditor('| A | B |\n|---|---|\n| 文本内容 | 其他单元 |')
    try {
      let position = 0
      let other = 0
      editor.state.doc.descendants((node, offset) => {
        if (node.text === '文本内容') {
          position = offset
        }
        if (node.text === '其他单元') {
          other = offset
        }
      })
      const doc = editor.state.doc
      expect(isImmersiveTextSelection(TextSelection.create(doc, position))).toBe(false)
      expect(isImmersiveTextSelection(TextSelection.create(doc, position, position + 3))).toBe(true)
      expect(isImmersiveTextSelection(TextSelection.create(doc, position, other + 2))).toBe(true)
      editor.commands.setTextSelection(position)
      editor.commands.command(selectImmersiveAxis('row', 1))
      expect(isImmersiveTextSelection(editor.state.selection)).toBe(true)
      editor.commands.command(selectImmersiveAxis('column', 0))
      expect(isImmersiveTextSelection(editor.state.selection)).toBe(true)
    } finally {
      editor.destroy()
    }
  })
  it('3列5行真实重排保留表头、列对齐、链接、加粗和公式，支持撤销重做', () => {
    const editor = interactionEditor(
      '| A | B | C |\n|:---|:---:|---:|\n| row1 | **粗体** | $2^{i-1}$ |\n| row2 | [链接](/blog) | $2^{h+1}-1$ |\n| row3 | 普通 | $2n-1$ |\n| row4 | 末行 | $2^h$ |',
    )
    editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
    try {
      editor.commands.setTextSelection(4)
      editor.commands.command(selectImmersiveAxis('row', 2))
      editor.view.dispatch(closeHistory(editor.state.tr))
      const before = editor.state.doc
      expect(editor.commands.command(moveImmersiveAxis('row', 2, 1))).toBe(true)
      const movedRow = editor.state.doc
      expect(movedRow.firstChild!.child(1).child(0).textContent).toBe('row2')
      expect(readEditorMarkdown(editor, editor.getMarkdown())).toContain('[链接](/blog)')
      expect(editor.commands.undo()).toBe(true)
      expect(editor.state.doc.eq(before)).toBe(true)
      expect(editor.commands.redo()).toBe(true)
      expect(editor.state.doc.eq(movedRow)).toBe(true)
      editor.view.dispatch(closeHistory(editor.state.tr))
      expect(editor.commands.command(moveImmersiveAxis('column', 2, 1))).toBe(true)
      const moved = editor.state.doc.firstChild!
      expect(moved.child(0).children.map((cell) => cell.textContent)).toEqual(['A', 'C', 'B'])
      expect(moved.child(0).children.map((cell) => cell.attrs.align)).toEqual([
        'left',
        'right',
        'center',
      ])
      expect(moved.child(1).child(1).firstChild!.firstChild!.attrs.source).toBe('$2^{h+1}-1$')
      const serialized = readEditorMarkdown(editor, editor.getMarkdown())
      expect(serialized).toContain('**粗体**')
      expect(serialized).toContain('$2^{i-1}$')
      expect(serialized).toContain('$2^{h+1}-1$')
      expect(editor.commands.undo()).toBe(true)
      expect(editor.state.doc.eq(movedRow)).toBe(true)
      expect(editor.commands.redo()).toBe(true)
      expect(editor.state.doc.firstChild!.eq(moved)).toBe(true)
      expect(editor.commands.command(moveImmersiveAxis('row', 1, 0))).toBe(false)
    } finally {
      editor.destroy()
    }
  })
})
