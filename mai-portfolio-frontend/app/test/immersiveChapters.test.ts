import { describe, it, expect } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT } from '../utils/editor/markdownExtensions'
import { createBlogMarkdownOptions } from '../utils/editor/markdownCompatibility'
import {
  ImmersiveChapters,
  immersiveChapters,
  toggleImmersiveChapter,
  revealImmersivePosition,
} from '../utils/editor/immersiveChapters'
import { patchImmersiveDocument } from '../utils/editor/immersiveDraft'
const source =
  '# 第一章\n\n首段\n\n## 1.1\n\n子段\n\n### 更深\n\n~~~ts\nconst a = 1\n~~~\n\n## 1.2\n\n同级\n\n# 第二章\n\n末段'
function make() {
  const editor = new Editor({
    element: null,
    extensions: [
      StarterKit.configure(EDITOR_STARTER_KIT),
      ...BLOG_EDITOR_EXTENSIONS,
      Markdown.configure(createBlogMarkdownOptions()),
      ImmersiveChapters,
    ],
    content: source,
    contentType: 'markdown',
  })
  editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
  return editor
}
describe('章节折叠与源码补丁', () => {
  it('H1-H6 同级边界、子章节边界及祖先展开与阅读规则一致', () => {
    const editor = make()
    try {
      const chapters = immersiveChapters(editor.state)!.chapters
      expect(chapters[0]!.end).toBe(chapters[4]!.position)
      expect(chapters[1]!.end).toBe(chapters[3]!.position)
      expect(chapters[2]!.end).toBe(chapters[3]!.position)
      const doc = editor.state.doc
      const markdown = editor.getMarkdown()
      editor.commands.command(toggleImmersiveChapter(chapters[0]!.id))
      editor.commands.command(toggleImmersiveChapter(chapters[1]!.id))
      expect(editor.state.doc).toBe(doc)
      expect(editor.getMarkdown()).toBe(markdown)
      editor.commands.command(revealImmersivePosition(chapters[2]!.position + 1))
      expect(immersiveChapters(editor.state)!.collapsed.size).toBe(0)
    } finally {
      editor.destroy()
    }
  })
  it('章节正文中的光标先移到标题；跨章节选区不静默收起，折叠不进入撤销历史', () => {
    const editor = make()
    try {
      const chapters = immersiveChapters(editor.state)!.chapters
      editor.commands.setTextSelection(chapters[1]!.bodyFrom + 1)
      editor.commands.command(toggleImmersiveChapter(chapters[0]!.id))
      expect(editor.state.selection.from).toBe(chapters[0]!.bodyFrom - 1)
      editor.commands.command(toggleImmersiveChapter(chapters[0]!.id))
      const range = { from: chapters[1]!.bodyFrom + 1, to: chapters[4]!.bodyFrom + 2 }
      editor.commands.setTextSelection(range)
      expect(editor.commands.command(toggleImmersiveChapter(chapters[0]!.id))).toBe(false)
      expect(editor.state.selection.from).toBe(range.from)
      expect(editor.state.selection.to).toBe(range.to)
    } finally {
      editor.destroy()
    }
  })
  it('源码代码补丁保留块起点及章节折叠身份，原文严格等价', () => {
    const editor = make()
    try {
      const chapter = immersiveChapters(editor.state)!.chapters[3]!
      editor.commands.command(toggleImmersiveChapter(chapter.id))
      let oldCode = 0
      editor.state.doc.forEach((node, pos) => {
        if (node.type.name === 'codeBlock') {
          oldCode = pos
        }
      })
      let removed = true
      editor.on('transaction', ({ transaction }) => {
        if (transaction.docChanged) {
          removed = transaction.mapping.mapResult(oldCode).deleted
        }
      })
      patchImmersiveDocument(editor, source.replace('const a = 1', 'const a = 100\n// 注释'))
      expect(removed).toBe(false)
      expect(immersiveChapters(editor.state)!.collapsed.has(chapter.id)).toBe(true)
      expect(editor.state.doc.textContent).toContain('const a = 100\n// 注释')
    } finally {
      editor.destroy()
    }
  })
})
