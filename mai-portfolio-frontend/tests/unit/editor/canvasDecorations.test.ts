import { describe, expect, it } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import {
  CANVAS_EDITOR_EXTENSIONS,
  CANVAS_STARTER_KIT,
  createCanvasMarkdownOptions,
} from '../../../app/utils/editor/markdown/canvasMarkdown'
import { alignImmersiveColumns } from '../../../app/utils/editor/interaction/immersiveTables'
import { ImmersiveDecorations } from '../../../app/utils/editor/interaction/immersiveDecorations'
import { serializeEditorDocument } from '../../../app/utils/editor/markdown/markdownOutput'
import { ImmersiveMarkdownGuard } from '../../../app/utils/editor/interaction/immersiveCapabilities'
import type { Decoration } from '@tiptap/pm/view'

function editorFor(source: string) {
  return new Editor({
    element: null,
    extensions: [
      StarterKit.configure(CANVAS_STARTER_KIT),
      ...CANVAS_EDITOR_EXTENSIONS,
      Markdown.configure(createCanvasMarkdownOptions()),
      ImmersiveDecorations,
      ImmersiveMarkdownGuard,
    ],
    content: source,
    contentType: 'markdown',
  })
}
describe('画布局部装饰与相邻节点交互', () => {
  it('普通正文输入复用既有代码高亮装饰，代码自身输入仍更新内容', () => {
    const editor = editorFor('前文\n\n~~~ts [a.ts]\nconst x = 1\n~~~')
    editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
    try {
      const layer = editor.state.plugins.find((plugin) =>
        String((plugin.spec.key as unknown as { key?: string })?.key || '').startsWith(
          'immersive-paint',
        ),
      )!
      const codeDecoration = () =>
        layer
          .getState(editor.state)
          .find()
          .find((item: Decoration) => item.spec.immersiveBlockId)
      editor.commands.setTextSelection(1)
      const original = codeDecoration()
      editor.commands.insertContent({ type: 'text', text: '新的正文' })
      expect(codeDecoration().spec).toBe(original.spec)
      const codeStart = editor.state.doc.firstChild!.nodeSize + 1
      editor.commands.insertContentAt(codeStart, { type: 'text', text: '// 注释\n' })
      expect(codeDecoration().spec.immersiveBlockId).toBe(original.spec.immersiveBlockId)
      expect(editor.getMarkdown()).toContain('// 注释')
    } finally {
      editor.destroy()
    }
  })
  it('合法的加粗行内代码及链接组合通过 Schema 和 Markdown 往返', () => {
    const editor = editorFor('正文 **\x60编号\x60** 和 [**\x60链接\x60**](https://example.com)。')
    try {
      expect(() => editor.state.doc.check()).not.toThrow()
      expect(() => serializeEditorDocument(editor, editor.state.doc)).not.toThrow()
    } finally {
      editor.destroy()
    }
  })
  it('代码身份在前文长度变化和块内输入后保持稳定', () => {
    const editor = editorFor('前文\n\n~~~ts\nconst x = 1\n~~~')
    editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
    try {
      const decorations = () => {
        const plugin = editor.state.plugins.find((item) =>
          String((item.spec.key as unknown as { key?: string })?.key || '').startsWith(
            'immersive-paint',
          ),
        )!
        return plugin
          .getState(editor.state)
          .find()
          .find((item: { spec: { immersiveBlockId?: string } }) => item.spec.immersiveBlockId)?.spec
          .immersiveBlockId
      }
      const original = decorations()
      editor.commands.insertContentAt(1, { type: 'text', text: '增加前文' })
      expect(decorations()).toBe(original)
      const codeStart = editor.state.doc.firstChild!.nodeSize + 1
      editor.commands.insertContentAt(codeStart, { type: 'text', text: '// 注释\n' })
      expect(decorations()).toBe(original)
    } finally {
      editor.destroy()
    }
  })
  it('段落边界的左右键进入相邻块公式', () => {
    const editor = editorFor('前文\n\n$$\nx^2\n$$\n\n后文')
    const shortcuts = ImmersiveDecorations.config.addKeyboardShortcuts!.call({ editor } as never)
    try {
      editor.commands.setTextSelection(editor.state.doc.firstChild!.nodeSize - 1)
      expect(shortcuts.ArrowRight!({ editor })).toBe(true)
      expect(editor.state.selection.$from.nodeAfter?.type.name).toBe('preservedMathBlock')
      const end = editor.state.doc.content.size - editor.state.doc.lastChild!.nodeSize + 1
      editor.commands.setTextSelection(end)
      expect(shortcuts.ArrowLeft!({ editor })).toBe(true)
      expect(editor.state.selection.$from.nodeAfter?.type.name).toBe('preservedMathBlock')
    } finally {
      editor.destroy()
    }
  })
  it('GFM 列对齐覆盖表头和同列单元格，行列修改后仍可校验 Markdown', () => {
    const editor = editorFor('| A | B |\n|---|---|\n| x | y |')
    try {
      editor.commands.setTextSelection(3)
      expect(editor.commands.command(alignImmersiveColumns('center'))).toBe(true)
      editor.commands.addRowAfter()
      // 添加行后显式继承列属性，与工作台相同。
      editor.commands.command(alignImmersiveColumns('center'))
      expect(serializeEditorDocument(editor, editor.state.doc)).toContain(':---:')
      editor.commands.addColumnAfter()
      expect(() => serializeEditorDocument(editor, editor.state.doc)).not.toThrow()
      editor.commands.deleteColumn()
      expect(() => serializeEditorDocument(editor, editor.state.doc)).not.toThrow()
    } finally {
      editor.destroy()
    }
  })
})
