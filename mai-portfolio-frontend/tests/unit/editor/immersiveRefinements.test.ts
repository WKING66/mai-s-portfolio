import { describe, expect, it } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT } from '../../../app/utils/editor/markdown/markdownExtensions'
import {
  createBlogMarkdownOptions,
  inspectBlogMarkdown,
} from '../../../app/utils/editor/markdown/markdownCompatibility'
import { buildImmersiveSourceMap, sourceRangeAt } from '../../../app/utils/editor/legacy/immersiveSourceMap'
import { alignImmersiveColumns } from '../../../app/utils/editor/interaction/immersiveTables'
import { ImmersiveDecorations } from '../../../app/utils/editor/interaction/immersiveDecorations'
import { patchImmersiveDocument } from '../../../app/utils/editor/legacy/immersiveDraft'
import { readEditorMarkdown } from '../../../app/utils/editor/markdown/markdownOutput'
import { ImmersiveMarkdownGuard } from '../../../app/utils/editor/interaction/immersiveCapabilities'
import type { Decoration } from '@tiptap/pm/view'

function editorFor(source: string) {
  return new Editor({
    element: null,
    extensions: [
      StarterKit.configure(EDITOR_STARTER_KIT),
      ...BLOG_EDITOR_EXTENSIONS,
      Markdown.configure(createBlogMarkdownOptions()),
      ImmersiveDecorations,
      ImmersiveMarkdownGuard,
    ],
    content: source,
    contentType: 'markdown',
  })
}
describe('Showcase 第二轮定向回归', () => {
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
      expect(
        inspectBlogMarkdown('正文 **\x60编号\x60** 和 [**\x60链接\x60**](https://example.com)。'),
      ).toBe('')
      expect(() => editor.state.doc.check()).not.toThrow()
      expect(() => readEditorMarkdown(editor, editor.getMarkdown())).not.toThrow()
      expect(buildImmersiveSourceMap(editor.getMarkdown(), editor.state.doc)).toHaveLength(1)
    } finally {
      editor.destroy()
    }
  })
  it('光标空段落不使整篇目录和双栏映射失效，实际内容不匹配仍拒绝', () => {
    const source = '# 起点\n\n第一段\n\n## 中间\n\n目标正文'
    const editor = editorFor(source)
    try {
      editor.commands.insertContentAt(0, { type: 'paragraph' })
      editor.commands.insertContentAt(editor.state.doc.content.size, { type: 'paragraph' })
      const ranges = buildImmersiveSourceMap(source, editor.state.doc)
      expect(ranges.map((range) => range.type)).toEqual([
        'heading',
        'paragraph',
        'heading',
        'paragraph',
      ])
      expect(ranges[0]!.from).toBe(2)
      expect(sourceRangeAt(ranges, source.indexOf('目标正文'), true)?.type).toBe('paragraph')
      expect(() =>
        buildImmersiveSourceMap(source.replace('第一段', '其他正文'), editor.state.doc),
      ).toThrow()
    } finally {
      editor.destroy()
    }
  })
  it('标题、段落、代码、表格、公式和资源的 raw 范围与真实块一一对应', () => {
    const source =
      '# 同名\n\n第一段\n\n~~~ts [a.ts]\nconst x = 1\n~~~\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n$$\nx^2\n$$\n\n![图](resource://key)\n\n# 同名\n\n结尾'
    const editor = editorFor(source)
    try {
      const ranges = buildImmersiveSourceMap(source, editor.state.doc)
      expect(ranges.map((range) => range.type)).toEqual([
        'heading',
        'paragraph',
        'codeBlock',
        'table',
        'preservedMathBlock',
        'paragraph',
        'heading',
        'paragraph',
      ])
      const code = ranges[2]!
      expect(source.slice(code.sourceFrom, code.sourceTo)).toContain('~~~ts [a.ts]')
      expect(source.slice(code.contentSourceFrom, code.contentSourceFrom! + 11)).toBe('const x = 1')
      expect(sourceRangeAt(ranges, source.lastIndexOf('# 同名'), true)?.from).toBe(ranges[6]!.from)
      expect(sourceRangeAt(ranges, ranges[3]!.from + 4)?.type).toBe('table')
    } finally {
      editor.destroy()
    }
  })
  it('源码尚未应用时拒绝用过期内容猜测位置', () => {
    const editor = editorFor('旧段落')
    try {
      expect(() => buildImmersiveSourceMap('新段落', editor.state.doc)).toThrow()
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
  it('同时修改两段源码时保留各代码块独立的主题身份', () => {
    const source =
      '~~~ts [a.ts]\nconst x = 1\n~~~\n\n中间段落\n\n~~~mermaid\nflowchart LR\nA --> B\n~~~'
    const editor = editorFor(source)
    editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
    try {
      const ids = () =>
        editor.state.plugins
          .find((plugin) =>
            String((plugin.spec.key as unknown as { key?: string })?.key || '').startsWith(
              'immersive-paint',
            ),
          )!
          .getState(editor.state)
          .find()
          .filter((item: { spec: { immersiveBlockId?: string } }) => item.spec.immersiveBlockId)
          .map((item: { spec: { immersiveBlockId: string } }) => item.spec.immersiveBlockId)
      const before = ids()
      const middle = editor.state.doc.child(1)
      patchImmersiveDocument(
        editor,
        source.replace('const x = 1', 'const x = 2').replace('A --> B', 'A --> C'),
      )
      expect(ids()).toEqual(before)
      expect(editor.state.doc.child(1)).toBe(middle)
      patchImmersiveDocument(editor, '# 新前文\n\n' + source.replace('const x = 1', 'const x = 3'))
      expect(ids()).toEqual(before)
      editor.commands.selectAll()
      editor.commands.deleteSelection()
      editor.commands.undo()
      expect(ids()).toEqual(before)
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
      expect(readEditorMarkdown(editor, editor.getMarkdown())).toContain(':---:')
      editor.commands.addColumnAfter()
      expect(() => readEditorMarkdown(editor, editor.getMarkdown())).not.toThrow()
      editor.commands.deleteColumn()
      expect(() => readEditorMarkdown(editor, editor.getMarkdown())).not.toThrow()
    } finally {
      editor.destroy()
    }
  })
})
