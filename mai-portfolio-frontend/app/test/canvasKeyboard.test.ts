import { describe, expect, it } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import {
  CANVAS_EDITOR_EXTENSIONS,
  CANVAS_STARTER_KIT,
  createCanvasMarkdownOptions,
} from '../utils/editor/canvasMarkdown'
import { newImmersiveParagraph } from '../utils/editor/immersiveParagraphInput'

function createEditor(source: string) {
  return new Editor({
    element: null,
    extensions: [
      StarterKit.configure(CANVAS_STARTER_KIT),
      ...CANVAS_EDITOR_EXTENSIONS,
      Markdown.configure(createCanvasMarkdownOptions()),
    ],
    content: source,
    contentType: 'markdown',
  })
}
describe('Enter 根据光标和结构决定格式继承', () => {
  it('Enter 导航到已有加粗内容的单元格时，不清除目标单元格格式', () => {
    const editor = createEditor('| 起点 | **目标** |\n|---|---|\n| A | B |')
    try {
      let position = 0
      editor.state.doc.descendants((node, from) => {
        if (node.isText && node.text === '起点') {
          position = from + node.nodeSize
        }
      })
      editor.commands.setTextSelection(position)
      expect(editor.commands.command(newImmersiveParagraph)).toBe(true)
      editor.view.dispatch(editor.state.tr.insertText('继续'))
      expect(
        editor.state.selection.$from.nodeBefore?.marks.some((mark) => mark.type.name === 'bold'),
      ).toBe(true)
    } finally {
      editor.destroy()
    }
  })
  it.each(['**加粗内容**', '## **加粗内容**', '- **加粗内容**', '> **加粗内容**'])(
    '在格式文字中间拆段不丢掉后续连续输入的样式：%s',
    (source) => {
      const editor = createEditor(source)
      try {
        let from = 0
        editor.state.doc.descendants((node, position) => {
          if (node.isText) {
            from = position + 2
          }
        })
        editor.commands.setTextSelection(from)
        expect(editor.commands.command(newImmersiveParagraph)).toBe(true)
        editor.view.dispatch(editor.state.tr.insertText('继续'))
        expect(
          editor.state.selection.$from.nodeBefore?.marks.some((mark) => mark.type.name === 'bold'),
        ).toBe(true)
      } finally {
        editor.destroy()
      }
    },
  )
  it('在完整加粗段落末尾结束该段后，下一段不意外继承加粗', () => {
    const editor = createEditor('**完整段落**')
    try {
      editor.commands.setTextSelection(editor.state.doc.content.size - 1)
      editor.commands.command(newImmersiveParagraph)
      editor.view.dispatch(editor.state.tr.insertText('普通段落'))
      expect(editor.state.selection.$from.nodeBefore?.marks).toEqual([])
    } finally {
      editor.destroy()
    }
  })
})
