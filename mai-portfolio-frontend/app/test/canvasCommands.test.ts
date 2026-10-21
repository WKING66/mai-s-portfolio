import { describe, expect, it } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import {
  CANVAS_EDITOR_EXTENSIONS,
  ALERT_KINDS,
  createCanvasMarkdownOptions,
  importCanvasMarkdown,
  exportCanvasMarkdown,
} from '../utils/editor/canvasMarkdown'
import { EDITOR_STARTER_KIT } from '../utils/editor/markdownExtensions'
import { IMMERSIVE_COMMANDS, executeImmersiveCommand } from '../utils/editor/immersiveCommands'
import { editorContentMeaning } from '../utils/editor/markdownOutput'

describe('Showcase 新语法的真实编辑命令', () => {
  it.each(ALERT_KINDS)('%s 提示块从菜单插入后可直接输入并输出标准引用标记', (kind) => {
    const editor = new Editor({
      element: null,
      extensions: [
        StarterKit.configure(EDITOR_STARTER_KIT),
        ...CANVAS_EDITOR_EXTENSIONS,
        Markdown.configure(createCanvasMarkdownOptions()),
      ],
      content: { type: 'doc', content: [{ type: 'paragraph' }] },
    })
    try {
      const item = IMMERSIVE_COMMANDS.find(
        (item) => item.kind === 'alert' && item.alertKind === kind,
      )!
      expect(
        executeImmersiveCommand(editor, { kind: 'block', from: 0, to: 0, query: '' }, item),
      ).toBe('')
      let position = 0
      editor.state.doc.descendants((node, from) => {
        if (node.type.name === 'paragraph' && !position) {
          position = from + 1
        }
      })
      editor.view.dispatch(editor.state.tr.insertText('可编辑提示正文', position))
      const output = exportCanvasMarkdown(editor.state.doc.toJSON())
      expect(output).toContain('> [!' + kind + ']')
      expect(output).toContain('> 可编辑提示正文')
      expect(editorContentMeaning(importCanvasMarkdown(output))).toEqual(
        editorContentMeaning(editor.state.doc.toJSON()),
      )
    } finally {
      editor.destroy()
    }
  })
})
