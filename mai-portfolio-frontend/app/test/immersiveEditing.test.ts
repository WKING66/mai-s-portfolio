import { indentMarkdownSource } from '../utils/editor/immersiveSourceIndent'
import { newImmersiveParagraph } from '../utils/editor/immersiveParagraphInput'
import { describe, it, expect } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT } from '../utils/editor/markdownExtensions'
import { createBlogMarkdownOptions } from '../utils/editor/markdownCompatibility'
import { serializeEditorDocument } from '../utils/editor/markdownOutput'

function editorFor() {
  return new Editor({
    element: null,
    extensions: [
      StarterKit.configure(EDITOR_STARTER_KIT),
      ...BLOG_EDITOR_EXTENSIONS,
      Markdown.configure(createBlogMarkdownOptions()),
    ],
    content: '正文',
    contentType: 'markdown',
  })
}
describe('Showcase 第六批编辑可靠性', () => {
  it.each([
    [{ type: 'text', text: '换行前', marks: [{ type: 'bold' }] }, { type: 'hardBreak' }],
    [
      { type: 'text', text: '换行前', marks: [{ type: 'bold' }] },
      { type: 'hardBreak', marks: [{ type: 'bold' }] },
      { type: 'text', text: '换行后', marks: [{ type: 'bold' }] },
    ],
    [{ type: 'text', text: '文字 ', marks: [{ type: 'bold' }] }],
    [{ type: 'text', text: ' 文字', marks: [{ type: 'italic' }] }],
    [{ type: 'text', text: '文字', marks: [{ type: 'bold' }, { type: 'italic' }] }],
    [
      { type: 'text', text: '文字', marks: [{ type: 'bold' }, { type: 'italic' }] },
      { type: 'text', text: ' ', marks: [{ type: 'bold' }, { type: 'italic' }] },
    ],
    [
      { type: 'text', text: '文字', marks: [{ type: 'bold' }] },
      { type: 'hardBreak' },
      { type: 'text', text: '换行', marks: [{ type: 'bold' }] },
    ],
  ])('合法行内格式和空格可转换 %#', (...content) => {
    const editor = editorFor()
    try {
      const doc = editor.schema.nodeFromJSON({
        type: 'doc',
        content: [{ type: 'paragraph', content }],
      })
      expect(() => serializeEditorDocument(editor, doc)).not.toThrow()
    } finally {
      editor.destroy()
    }
  })
})

describe('换段与源码缩进', () => {
  it.each(['正文', '# 标题', '- 列表', '> 引用', '| A | B |\n|---|---|\n| 表格 | 其他 |'])(
    '段末结束格式，导航到普通单元格后输入正常文字：%s',
    (source) => {
      const editor = editorFor()
      try {
        editor.commands.setContent(source, { contentType: 'markdown' })
        let end = 0
        editor.state.doc.descendants((node, position) => {
          if (node.isText && !end) {
            end = position + node.nodeSize
          }
        })
        editor.commands.setTextSelection(end)
        editor.commands.setMark('bold')
        editor.commands.insertContent({ type: 'text', text: '加粗', marks: [{ type: 'bold' }] })
        expect(editor.commands.command(newImmersiveParagraph)).toBe(true)
        editor.commands.insertContent({ type: 'text', text: '正常' })
        expect(() => serializeEditorDocument(editor, editor.state.doc)).not.toThrow()
        let normalMarks: string[] = []
        editor.state.doc.descendants((node) => {
          if (node.isText && node.text?.includes('正常')) {
            normalMarks = node.marks.map((mark) => mark.type.name)
          }
        })
        expect(normalMarks).toEqual([])
        expect(editor.getMarkdown()).toContain('**加粗**')
      } finally {
        editor.destroy()
      }
    },
  )
  it('两空格缩进、跨行反缩进与末端换行选区', () => {
    expect(indentMarkdownSource('a\nb\nc', 0, 4, false)).toEqual({
      from: 0,
      to: 3,
      text: '  a\n  b',
      anchor: 2,
      head: 8,
    })
    expect(indentMarkdownSource('  a\n  b\nc', 2, 8, true)).toEqual({
      from: 0,
      to: 7,
      text: 'a\nb',
      anchor: 0,
      head: 4,
    })
    expect(indentMarkdownSource('a', 1, 1, false)).toEqual({
      from: 1,
      to: 1,
      text: '  ',
      anchor: 3,
      head: 3,
    })
  })
})
