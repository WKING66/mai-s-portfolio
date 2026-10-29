import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import { describe, expect, it } from 'vitest'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT } from '../../../app/utils/editor/markdown/markdownExtensions'
import {
  createImmersiveMarkdownManager,
  createImmersiveMarkdownOptions,
} from '../../../app/utils/editor/markdown/immersiveMarkdown'
import { immersiveMathSource } from '../../../app/utils/editor/math/immersiveMath'
import { ImmersiveMarkdownGuard } from '../../../app/utils/editor/interaction/immersiveCapabilities'
import { serializeEditorDocument } from '../../../app/utils/editor/markdown/markdownOutput'

describe('公式编辑与空内容的实际 Markdown 边界', () => {
  it.each([
    ['$x$', false],
    ['\\(x\\)', false],
    ['$$\nx\n$$', true],
    ['\\[x\\]', true],
  ] as const)('清空 %s 后仍能无损导出为原类型并继续编辑', (original, block) => {
    const editor = new Editor({
      element: null,
      extensions: [
        StarterKit.configure(EDITOR_STARTER_KIT),
        ...BLOG_EDITOR_EXTENSIONS,
        Markdown.configure(createImmersiveMarkdownOptions()),
      ],
      content: original,
      contentType: 'markdown',
    })
    try {
      let position = 0
      editor.state.doc.descendants((node, pos) => {
        if (node.type.name.startsWith('preservedMath')) {
          position = pos
        }
      })
      const node = editor.state.doc.nodeAt(position)!
      expect(node.type.name).toBe(block ? 'preservedMathBlock' : 'preservedMathInline')
      editor.commands.setNodeSelection(position)
      const cleared = immersiveMathSource(original, '', block)
      editor.view.dispatch(editor.state.tr.setNodeAttribute(position, 'source', cleared))
      expect(editor.state.doc.nodeAt(position)?.type).toBe(node.type)
      expect(() => serializeEditorDocument(editor, editor.state.doc)).not.toThrow()
      const parsed = editor.schema.nodeFromJSON(
        editor.markdown!.parse(serializeEditorDocument(editor, editor.state.doc)),
      )
      expect(parsed.nodeAt(position)?.type.name).toBe(node.type.name)
      editor.view.dispatch(
        editor.state.tr.setNodeAttribute(
          position,
          'source',
          immersiveMathSource(cleared, 'x+1', block),
        ),
      )
      expect(serializeEditorDocument(editor, editor.state.doc)).toContain('x+1')
    } finally {
      editor.destroy()
    }
  })
  it('保留已有反斜线分隔符', () => {
    expect(immersiveMathSource('\\(x\\)', 'y', false)).toBe('\\(y\\)')
    expect(immersiveMathSource('\\[x\\]', 'y', true)).toBe('\\[\ny\n\\]')
  })
})

it('重复解析不共享可变 token，缓存不会污染后续往返校验', () => {
  const manager = createImmersiveMarkdownManager()
  const source = '## 标题\n\n正文 **强调** 与 $x$\n\n- 子项'
  const expected = manager.parse(source)
  const tokens = manager.instance.lexer(source)
  tokens[0]!.raw = '损坏'
  manager.instance.walkTokens(tokens, (token) => {
    if ('text' in token) {
      token.text = '污染'
    }
  })
  expect(manager.parse(source)).toEqual(expected)
  manager.parse('另一份文档')
  manager.parse('第三份文档')
  expect(manager.parse(source)).toEqual(expected)
})

it.each([
  ['普通段落', 'paragraph'],
  ['[链接文字](https://example.com)', 'paragraph'],
  ['~~~ts\nconst a = 1\n~~~', 'codeBlock'],
  ['~~~mermaid\nflowchart LR\nA-->B\n~~~', 'codeBlock'],
  ['| 表头 |\n| --- |\n| 单元文字 |', 'paragraph'],
] as const)('清空 %s 的可编辑文字仍能通过实际事务门禁', (source, kind) => {
  const rejected: unknown[] = []
  const editor = new Editor({
    element: null,
    extensions: [
      StarterKit.configure(EDITOR_STARTER_KIT),
      ...BLOG_EDITOR_EXTENSIONS,
      Markdown.configure(createImmersiveMarkdownOptions()),
      ImmersiveMarkdownGuard.configure({ reject: (failure) => rejected.push(failure) }),
    ],
    content: source,
    contentType: 'markdown',
  })
  editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
  try {
    let target: { from: number; to: number } | undefined
    editor.state.doc.descendants((node, position) => {
      if (node.type.name === kind && node.content.size) {
        target = { from: position + 1, to: position + 1 + node.content.size }
      }
    })
    expect(target).toBeDefined()
    editor.commands.setTextSelection(target!)
    expect(editor.commands.deleteSelection()).toBe(true)
    expect(editor.state.doc.nodeAt(target!.from - 1)?.textContent).toBe('')
    expect(rejected).toHaveLength(0)
    expect(() => serializeEditorDocument(editor, editor.state.doc)).not.toThrow()
    expect(editor.isEditable).toBe(true)
  } finally {
    editor.destroy()
  }
})
