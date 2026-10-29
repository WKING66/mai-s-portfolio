import { describe, expect, it } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import {
  BLOG_EDITOR_EXTENSIONS,
  EDITOR_STARTER_KIT,
} from '../../../app/utils/editor/markdown/markdownExtensions'
import { createCanvasMarkdownOptions } from '../../../app/utils/editor/markdown/canvasMarkdown'
import { ImmersiveMarkdownGuard } from '../../../app/utils/editor/interaction/immersiveCapabilities'
import {
  IMMERSIVE_COMMANDS,
  immersiveCommandProblem,
  executeImmersiveCommand,
  nextImmersiveMenuIndex,
} from '../../../app/utils/editor/interaction/immersiveCommands'
import { immersiveLinkRange } from '../../../app/utils/editor/interaction/immersiveLinks'
import { serializeEditorDocument } from '../../../app/utils/editor/markdown/markdownOutput'

export function interactionEditor(source: string) {
  return new Editor({
    element: null,
    extensions: [
      StarterKit.configure(EDITOR_STARTER_KIT),
      ...BLOG_EDITOR_EXTENSIONS,
      ImmersiveMarkdownGuard,
      Markdown.configure(createCanvasMarkdownOptions()),
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
      expect(serializeEditorDocument(editor, editor.state.doc)).not.toContain('/正文')
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
      expect(serializeEditorDocument(editor, editor.state.doc)).not.toContain('/')
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
      expect(serializeEditorDocument(editor, editor.state.doc)).toContain('$x^2$')
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
      expect(() => serializeEditorDocument(editor, editor.state.doc)).not.toThrow()
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
      expect(serializeEditorDocument(editor, editor.state.doc)).toBe(source)
    } finally {
      editor.destroy()
    }
  })
})
