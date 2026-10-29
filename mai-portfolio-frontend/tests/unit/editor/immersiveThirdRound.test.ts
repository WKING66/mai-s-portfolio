import { directImmersiveValidator } from '../../support/immersiveValidationFixture'
import { describe, expect, it, vi } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import { ref } from 'vue'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT } from '../../../app/utils/editor/markdown/markdownExtensions'
import { createBlogMarkdownOptions } from '../../../app/utils/editor/markdown/markdownCompatibility'
import { readEditorMarkdown } from '../../../app/utils/editor/markdown/markdownOutput'
import {
  ImmersiveMarkdownGuard,
  immersiveInsertionProblem,
  immersiveDocumentProblem,
} from '../../../app/utils/editor/interaction/immersiveCapabilities'
import { useImmersiveDraft } from '../../../app/composables/useImmersiveDraft'
import { highlightCode } from '../../../app/utils/markdown/code/codeHighlight'

const fixture = [
  '## 标题',
  '前文 **粗体** 与链接 [站点](https://example.com)',
  '- 第一项\n- 第二项',
  '表格之前',
  '| A | B |\n|---|---|\n| one | two |',
  '表格之后',
  '~~~typescript [article.ts]\nconst x = 1\n~~~',
  '## 图表',
  '~~~mermaid\nflowchart LR\nA --> B\n~~~',
  '行内公式 $x^2$ 后文',
  '$$\nx^2\n$$',
  '![图片](resource://image)',
  '末尾正文',
].join('\n\n')
function createEditor(source = fixture, reject = vi.fn()) {
  const editor = new Editor({
    element: null,
    extensions: [
      StarterKit.configure(EDITOR_STARTER_KIT),
      ...BLOG_EDITOR_EXTENSIONS,
      Markdown.configure(createBlogMarkdownOptions()),
      ImmersiveMarkdownGuard.configure({ reject }),
    ],
    content: source,
    contentType: 'markdown',
  })
  editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
  return editor
}
describe('Showcase 第三轮内容正确性', () => {
  it('空标题仍以 ATX 标题序列化，保留继续输入时的标题级别', () => {
    const editor = createEditor('## 标题\n\n后文')
    try {
      editor.commands.setTextSelection({ from: 1, to: 3 })
      editor.commands.deleteSelection()
      expect(editor.state.doc.firstChild?.type.name).toBe('heading')
      expect(readEditorMarkdown(editor, editor.getMarkdown())).toMatch(/^##\n/)
    } finally {
      editor.destroy()
    }
  })
  it('跨标题、列表、整张表格、代码及公式删除后仍可往返，且能撤销', () => {
    const baseline = createEditor()
    const positions: number[] = []
    baseline.state.doc.forEach((node, position) => {
      if (node.isTextblock) {
        positions.push(position + 1)
      }
    })
    baseline.destroy()
    for (let start = 0; start < positions.length - 1; start += 2) {
      const rejected = vi.fn()
      const editor = createEditor(fixture, rejected)
      try {
        const original = editor.state.doc
        editor.commands.setTextSelection({
          from: positions[start]!,
          to: positions[Math.min(start + 3, positions.length - 1)]!,
        })
        editor.commands.deleteSelection()
        expect(editor.state.doc.eq(original)).toBe(false)
        expect(() => readEditorMarkdown(editor, editor.getMarkdown())).not.toThrow()
        editor.commands.undo()
        expect(editor.state.doc.eq(original)).toBe(true)
        expect(rejected).not.toHaveBeenCalled()
      } finally {
        editor.destroy()
      }
    }
  })
  it('表格内插入块级公式会在事务提交前拒绝，保留原文及具体原因', async () => {
    const rejected = vi.fn()
    const editor = createEditor('| A | B |\n|---|---|\n| one | two |', rejected)
    try {
      editor.commands.setTextSelection(3)
      expect(immersiveInsertionProblem(editor, 'math')).toContain('单元格')
      const before = editor.state.doc
      const paragraph = before.firstChild!.firstChild!.firstChild!.firstChild!
      const candidate = editor.state.tr.replaceWith(
        3,
        3 + paragraph.nodeSize,
        editor.schema.nodes.preservedMathBlock!.create({ source: '$$\nx^2\n$$' }),
      )
      candidate.doc.check()
      editor.view.dispatch(candidate)
      expect(editor.state.doc.eq(before)).toBe(true)
      await Promise.resolve()
      // 无 DOM 的 Editor 被框架视为已销毁，通知不派发；此处验证真正的拒绝结果与具体原因。
      expect(immersiveDocumentProblem(candidate.doc)?.reason).toContain('表格单元格')
      expect(readEditorMarkdown(editor, editor.getMarkdown())).toContain('one')
    } finally {
      editor.destroy()
    }
  })
  it('合法剪切和撤销通过后台校验后同步 Markdown 草稿，不保留旧版正文', async () => {
    const editor = createEditor('## 标题\n\n前文\n\n后文')
    const session = useImmersiveDraft(
      '## 标题\n\n前文\n\n后文',
      vi.fn(),
      ref(true),
      undefined,
      directImmersiveValidator(),
    )
    vi.spyOn(editor, 'isDestroyed', 'get').mockReturnValue(false)
    session.attach(editor)
    try {
      editor.commands.setTextSelection({ from: 1, to: 9 })
      editor.commands.deleteSelection()
      await session.flushRich()
      expect(session.source.value).toBe(editor.getMarkdown())
      expect(session.issue.value).toBe('')
      editor.commands.undo()
      await session.flushRich()
      expect(session.source.value).toContain('前文')
    } finally {
      session.dispose()
      editor.destroy()
    }
  })
  it('行内公式在段落和 GFM 单元格中保留行内语义', () => {
    const editor = createEditor('满足 $a^2+b^2=c^2$，示例。\n\n| A |\n|---|\n| $x^2$ |')
    try {
      expect(readEditorMarkdown(editor, editor.getMarkdown())).toContain('$a^2+b^2=c^2$')
      expect(editor.state.doc.firstChild?.child(1).type.name).toBe('preservedMathInline')
    } finally {
      editor.destroy()
    }
  })
  it('HTML 与 CSS 仍使用同一个 Shiki 实例进行真实分词', async () => {
    const results = await Promise.all([
      highlightCode('<p>Hello</p>', 'html'),
      highlightCode('.demo { color: red }', 'css'),
    ])
    for (const node of results) {
      expect(JSON.stringify(node)).toContain('--shiki-dark')
      expect(JSON.stringify(node)).toContain('span')
    }
  })
})
