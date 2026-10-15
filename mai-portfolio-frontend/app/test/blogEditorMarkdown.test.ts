import { inheritTableColumnAlignment } from '../utils/editor/tableCommands'
import { Editor } from '@tiptap/core'
import { Markdown } from '@tiptap/markdown'
import StarterKit from '@tiptap/starter-kit'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT } from '../utils/editor/markdownExtensions'
import { readEditorMarkdown } from '../utils/editor/markdownOutput'
import { describe, expect, it } from 'vitest'
import {
  createBlogMarkdownManager,
  createBlogMarkdownOptions,
  inspectBlogMarkdown,
} from '../utils/editor/markdownCompatibility'

const matrix = {
  空正文: '',
  软换行与换行: '第一行\n第二行\n\n第三行  \n第四行',
  常规格式:
    '# 标题\n\n正文 **加粗**、*斜体*、~~删除~~和 [链接](https://example.com)。\n\n> 引用\n\n- 甲\n- 乙\n\n1. 一\n2. 二',
  'GFM 表格': '| 名称 | 结果 |\n| :--- | ---: |\n| **语义** | 通过 |',
  多层列表: '- 一级\n  - 二级 **加粗**\n    - 三级 ~~删除线~~\n\n1. 有序一级\n   1. 有序二级',
  任务列表: '- [ ] 待办\n- [x] 完成\n  - [ ] 子任务',
  代码与文件名: '~~~java [Article.java]\n  public void run() {\n\tgo();\n  }\n~~~',
  嵌套围栏: '\x60\x60\x60\x60markdown\n\x60\x60\x60ts\nconst x = 1\n\x60\x60\x60\n\x60\x60\x60\x60',
  Mermaid: '\x60\x60\x60mermaid\nflowchart TD\n  A --> B\n\x60\x60\x60',
  公式: '行内 $x^2 + y^2$ 及 \\(a+b\\)。\n\n$$\n\\frac{1}{2}\n$$\n',
  行内图片: '图片在这里 ![图](resource://inline-image) 并继续正文。',
  稳定资源: '![图片](resource://image-key)\n\n[附件](asset://42)\n\n![待绑定](asset://43)',
}

describe('UEditor Markdown compatibility contract', () => {
  it.each(Object.entries(matrix))('%s 可往返且再次转换稳定', (_, markdown) => {
    const manager = createBlogMarkdownManager()
    expect(inspectBlogMarkdown(markdown, manager)).toBe('')
    const document = manager.parse(markdown)
    const output = manager.serialize(document)
    expect(manager.parse(output)).toEqual(document)
    expect(inspectBlogMarkdown(output, manager)).toBe('')
  })

  it('修改普通段落后原始代码、公式和稳定引用不变', () => {
    const manager = createBlogMarkdownManager()
    const code = matrix['代码与文件名']
    const source = '修改前\n\n' + code + '\n\n' + matrix['公式'] + '\n\n' + matrix['稳定资源']
    const document = manager.parse(source)
    document.content![0]!.content![0]!.text = '修改后'
    const output = manager.serialize(document)
    expect(output).toContain(code)
    expect(output).toContain('\\frac{1}{2}')
    expect(output).toContain('$x^2 + y^2$')
    expect(output).toContain('resource://image-key')
    expect(output).toContain('asset://42')
  })

  it.each([
    '- 普通\n- [x] 完成\n- [ ] 待办',
    '<script>alert(1)</script>',
    '文本 <span onclick="alert(1)">不可信</span>',
    '[危险](javascript:alert%281%29)',
    '![危险](data:image/svg+xml;base64,PHN2Zz4=)',
    '[引用][id]\n\n[id]: https://example.com',
    '脚注[^note]\n\n[^note]: 原文',
    'a'.repeat(60001),
  ])('无法可靠编辑的原文明确降级源码：%s', (markdown) => {
    expect(inspectBlogMarkdown(markdown)).not.toBe('')
  })
})

describe('真实 TipTap 编辑状态的 Markdown 输出', () => {
  function editorFor(content: string) {
    return new Editor({
      element: null,
      extensions: [
        StarterKit.configure(EDITOR_STARTER_KIT),
        ...BLOG_EDITOR_EXTENSIONS,
        Markdown.configure(createBlogMarkdownOptions()),
      ],
      content: content || { type: 'doc', content: [{ type: 'paragraph' }] },
      contentType: content ? 'markdown' : 'json',
    })
  }
  it.each(Object.entries(matrix))('%s 的编辑状态可校验', (_, markdown) => {
    const editor = editorFor(markdown)
    try {
      expect(() => editor.state.doc.check()).not.toThrow()
      expect(readEditorMarkdown(editor, editor.getMarkdown())).toBe(editor.getMarkdown())
    } finally {
      editor.destroy()
    }
  })
  it('拒绝 UEditor 的纯文本降级输出', () => {
    const editor = editorFor('**加粗**')
    expect(() => readEditorMarkdown(editor, '加粗')).toThrow()
    editor.destroy()
  })
})

it('清空正文后仍可保存空 Markdown 草稿', () => {
  const editor = new Editor({
    element: null,
    extensions: [StarterKit.configure(EDITOR_STARTER_KIT), ...BLOG_EDITOR_EXTENSIONS, Markdown],
    content: { type: 'doc', content: [{ type: 'paragraph' }] },
  })
  expect(readEditorMarkdown(editor, editor.getMarkdown())).toBe('')
  editor.destroy()
})

it('多个会话的 lexer 和 tokenizer 注册彼此隔离', () => {
  const first = createBlogMarkdownManager()
  const second = createBlogMarkdownManager()
  expect(first.instance).not.toBe(second.instance)
  expect(inspectBlogMarkdown(matrix['公式'], first)).toBe('')
  expect(inspectBlogMarkdown(matrix['公式'], second)).toBe('')
})

it('段落末尾 Enter 的空段落不误报转换失败', () => {
  const editor = new Editor({
    element: null,
    extensions: [
      StarterKit.configure(EDITOR_STARTER_KIT),
      ...BLOG_EDITOR_EXTENSIONS,
      Markdown.configure(createBlogMarkdownOptions()),
    ],
    content: {
      type: 'doc',
      content: [
        { type: 'paragraph', content: [{ type: 'text', text: '继续写作' }] },
        { type: 'paragraph' },
      ],
    },
  })
  expect(readEditorMarkdown(editor, editor.getMarkdown())).toContain('继续写作')
  editor.destroy()
})

it('有列对齐的表格插入空行后仍可保存并恢复', () => {
  const editor = new Editor({
    element: null,
    extensions: [
      StarterKit.configure(EDITOR_STARTER_KIT),
      ...BLOG_EDITOR_EXTENSIONS,
      Markdown.configure(createBlogMarkdownOptions()),
    ],
    content: matrix['GFM 表格'],
    contentType: 'markdown',
  })
  let cellPosition = 0
  editor.state.doc.descendants((node, position) => {
    if (node.type.name === 'tableCell' && node.textContent === '通过') {
      cellPosition = position + 2
    }
  })
  editor.commands.setTextSelection(cellPosition)
  expect(editor.chain().addRowAfter().command(inheritTableColumnAlignment).run()).toBe(true)
  expect(() => editor.state.doc.check()).not.toThrow()
  expect(readEditorMarkdown(editor, editor.getMarkdown())).toContain('通过')
  editor.destroy()
})

it('资源占位在 DOM 上保留稳定身份，附件不直接导航内部协议', () => {
  const editor = new Editor({
    element: null,
    extensions: [
      StarterKit.configure(EDITOR_STARTER_KIT),
      ...BLOG_EDITOR_EXTENSIONS,
      Markdown.configure(createBlogMarkdownOptions()),
    ],
    content: matrix['稳定资源'],
    contentType: 'markdown',
  })
  const image = editor.schema.nodes.image!.create({ src: 'resource://image-key', alt: '图片' })
  const imageDom = image.type.spec.toDOM!(image) as [string, Record<string, string>]
  expect(imageDom[1]['data-editor-image-ref']).toBe('resource://image-key')
  const link = editor.schema.marks.link!.create({ href: 'asset://42' })
  const linkDom = link.type.spec.toDOM!(link, true) as [string, Record<string, string>, number]
  expect(linkDom[1]['data-editor-resource-link']).toBe('asset://42')
  expect(linkDom[1].href).toBeUndefined()
  editor.destroy()
})
