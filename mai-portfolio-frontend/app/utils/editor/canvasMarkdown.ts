import { getSchema, Node, type JSONContent } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { MarkdownManager } from '@tiptap/markdown'
import type { Token } from 'marked'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT, isEditorUrl } from './markdownExtensions'
import { createBlogMarkdownOptions } from './markdownCompatibility'
import { serializeEditorDocument } from './markdownOutput'
import type { DocumentResource } from '../../api/blogs'

export const ALERT_KINDS = ['NOTE', 'TIP', 'IMPORTANT', 'WARNING', 'CAUTION'] as const

export const CanvasAlert = Node.create({
  name: 'canvasAlert',
  group: 'block',
  content: 'block+',
  defining: true,
  addAttributes: () => ({ kind: { default: 'NOTE' } }),
  parseHTML: () => [
    {
      tag: 'aside[data-canvas-alert]',
      getAttrs: (element) => ({ kind: element.getAttribute('data-canvas-alert') }),
    },
  ],
  renderHTML: ({ node }) => [
    'aside',
    { 'data-canvas-alert': node.attrs.kind, class: 'canvas-alert' },
    0,
  ],
  markdownTokenizer: {
    name: 'canvasAlert',
    level: 'block',
    start: (source) => source.search(/^ {0,3}>\s*\[!(?:NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]/m),
    tokenize(source, _tokens, lexer) {
      const first = source.match(
        /^ {0,3}>\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\][ \t]*(.*)(?:\n|$)/,
      )
      if (!first) {
        return
      }
      let raw = first[0]
      const body = [first[2]!]
      for (const line of source.slice(raw.length).match(/[^\n]*(?:\n|$)/g) ?? []) {
        if (!line || !/^ {0,3}>/.test(line)) {
          break
        }
        raw += line
        body.push(line.replace(/^ {0,3}> ?/, '').replace(/\n$/, ''))
      }
      return {
        type: 'canvasAlert',
        raw,
        kind: first[1],
        tokens: lexer.blockTokens(body.join('\n').trim()),
      }
    },
  },
  parseMarkdown(token, helpers) {
    const content = helpers.parseChildren(token.tokens ?? [])
    return helpers.createNode(
      'canvasAlert',
      { kind: token.kind },
      content.length ? content : [helpers.createNode('paragraph')],
    )
  },
  renderMarkdown(node, helpers) {
    return (
      '> [!' +
      (node.attrs?.kind ?? 'NOTE') +
      ']\n' +
      helpers
        .renderChildren(node.content ?? [], '\n\n')
        .split('\n')
        .map((line) => '> ' + line)
        .join('\n')
    )
  },
})

// 原有节点、表格与数学扩展保持；Showcase 不再引入只读原文封装节点。
export const CANVAS_EDITOR_EXTENSIONS = [
  ...BLOG_EDITOR_EXTENSIONS.filter((extension) => extension.name !== 'preservedMarkdownBlock'),
  CanvasAlert,
]
export const canvasSchema = getSchema([
  StarterKit.configure(EDITOR_STARTER_KIT),
  ...CANVAS_EDITOR_EXTENSIONS,
])

export function createCanvasMarkdownOptions() {
  const options = createBlogMarkdownOptions()
  const instance = options.marked
  // 不支持脚注：阻止其定义被普通引用链接规则吞掉，保留成可编辑正文。
  instance.use({
    tokenizer: {
      def(source) {
        return /^ {0,3}\[\^[^\]\n]+\]:/.test(source) ? undefined : false
      },
    },
  })
  const BaseLexer = instance.Lexer
  class CanvasLexer<ParserOutput = string, RendererOutput = string> extends BaseLexer<
    ParserOutput,
    RendererOutput
  > {
    override lex(source: string) {
      const tokens = super.lex(source)
      const normalize = (items: Token[], active = new Set<string>()) => {
        for (let index = 0; index < items.length; index++) {
          const token = items[index]!
          // HTML 仅成为字面文本，绝不交给可编辑 HTML DOM 或执行。
          if (token.type === 'html') {
            items[index] = token.block
              ? {
                  type: 'paragraph',
                  raw: token.raw,
                  text: token.raw.trimEnd(),
                  tokens: [{ type: 'text', raw: token.raw, text: token.raw.trimEnd() }],
                }
              : { type: 'text', raw: token.raw, text: token.raw }
            continue
          }
          if (token.type === 'list_item' && !token.tokens?.length) {
            token.tokens = [{ type: 'paragraph', raw: '', text: '', tokens: [] }]
          }
          if ('tokens' in token && token.tokens) {
            const next = new Set(active)
            const emphasis = ['strong', 'em', 'del'].includes(token.type)
            if (emphasis) {
              next.add(token.type)
            }
            normalize(token.tokens, next)
            if (emphasis && active.has(token.type)) {
              items.splice(index, 1, ...token.tokens)
              index += token.tokens.length - 1
            }
          }
          if (token.type === 'list') {
            token.items.forEach((item: Token) => normalize([item], active))
          }
          if (token.type === 'table') {
            ;[token.header, ...token.rows].forEach((row: { tokens: Token[] }[]) =>
              row.forEach((cell) => normalize(cell.tokens, active)),
            )
          }
        }
      }
      normalize(tokens)
      return tokens
    }
  }
  instance.Lexer = CanvasLexer
  instance.lexer = (source, lexerOptions) =>
    new CanvasLexer(lexerOptions ?? instance.defaults).lex(source)
  return options
}

export function createCanvasMarkdownManager() {
  return new MarkdownManager({
    extensions: [StarterKit.configure(EDITOR_STARTER_KIT), ...CANVAS_EDITOR_EXTENSIONS],
    ...createCanvasMarkdownOptions(),
  })
}

/** 一次性导入边界；本地测试资源转为清单中的稳定引用，不让文件系统地址进入编辑链接。 */
export function importCanvasMarkdown(
  source: string,
  resources: DocumentResource[] = [],
): JSONContent {
  const manager = createCanvasMarkdownManager()
  const parsed = manager.parse(source)
  const visit = (node: JSONContent) => {
    if (node.type === 'image') {
      const uri = String(node.attrs?.src ?? '')
      const resource = resources.find(
        (item) => item.originalUri === uri || item.originalUri === safelyDecode(uri),
      )
      if (resource) {
        node.attrs = { ...node.attrs, src: 'resource://' + resource.key }
      }
    }
    node.content?.forEach(visit)
  }
  visit(parsed)
  const document = canvasSchema.nodeFromJSON(parsed)
  document.check()
  return document.childCount ? parsed : canvasSchema.topNodeType.createAndFill()!.toJSON()
}
function safelyDecode(value: string) {
  try {
    return decodeURI(value)
  } catch {
    return value
  }
}

/** 全面语义往返只在导入/保存/导出边界执行。 */
export function exportCanvasMarkdown(json: JSONContent): string {
  const document = canvasSchema.nodeFromJSON(json)
  document.check()
  document.descendants((node) => {
    const urls = [
      node.type.name === 'image' ? node.attrs.src : undefined,
      ...node.marks.filter((mark) => mark.type.name === 'link').map((mark) => mark.attrs.href),
    ]
    if (urls.some((url) => url && !isEditorUrl(String(url)))) {
      throw new Error('存在未处理的资源或不安全链接，当前内容未保存。')
    }
  })
  return serializeEditorDocument(
    { markdown: createCanvasMarkdownManager(), schema: canvasSchema },
    document,
  )
}
