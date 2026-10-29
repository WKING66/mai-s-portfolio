import { getSchema, Node, type Mark, type JSONContent } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { OrderedList } from '@tiptap/extension-list'
import { CanvasMarkdownWriter } from './canvasMarkdownOutput'
import { Marked, type Token, type marked } from 'marked'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT, isEditorUrl } from './markdownExtensions'
import { serializeEditorDocument } from './markdownOutput'
import type { DocumentResource } from '../../../api/blogs'

export const ALERT_KINDS = ['NOTE', 'TIP', 'IMPORTANT', 'WARNING', 'CAUTION'] as const
export const CANVAS_STARTER_KIT = { ...EDITOR_STARTER_KIT, orderedList: false } as const

// 保留列表 Schema/命令，只让标准数字列表交给 Marked 的 CommonMark tokenizer。
// 库的扩展 tokenizer 支持非标准字母编号，但续行扣除宽度少算点号，不能用于交换正文。
const CanvasOrderedList = OrderedList.extend({
  markdownTokenizer: {
    name: 'orderedList',
    level: 'block',
    start: () => -1,
    tokenize: () => undefined,
  },
  parseMarkdown(token, helpers) {
    const start = token.start ?? 1
    // 原生 listItem 已正确区分紧凑列表中的行内 token，避免父类把它当字面文本。
    return helpers.createNode(
      'orderedList',
      start === 1 ? undefined : { start },
      helpers.parseChildren(token.items ?? []),
    )
  },
})

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

// 画布沿用既有节点、表格与数学扩展，不引入只读原文封装节点。
export const CANVAS_EDITOR_EXTENSIONS = [
  CanvasOrderedList,
  ...BLOG_EDITOR_EXTENSIONS.filter((extension) => extension.name !== 'preservedMarkdownBlock').map(
    (extension) =>
      extension.name === 'link'
        ? (extension as Mark).extend({
            parseMarkdown(token, helpers) {
              // 库默认只给 text 加链接，导致公式/图片原子从真实外层链接中脱落。
              return helpers.parseInline(token.tokens ?? []).map((node) => ({
                ...node,
                marks: [
                  ...(node.marks ?? []),
                  { type: 'link', attrs: { href: token.href, title: token.title || null } },
                ],
              }))
            },
          })
        : extension,
  ),
  CanvasAlert,
]
export const canvasSchema = getSchema([
  StarterKit.configure(CANVAS_STARTER_KIT),
  ...CANVAS_EDITOR_EXTENSIONS,
])

export function createCanvasMarkdownOptions() {
  const options = { marked: new Marked() as unknown as typeof marked, markedOptions: { gfm: true } }
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
  return new CanvasMarkdownWriter({
    extensions: [StarterKit.configure(CANVAS_STARTER_KIT), ...CANVAS_EDITOR_EXTENSIONS],
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
  const imported = canvasSchema.nodeFromJSON(parsed)
  // 空草稿没有 Markdown token，但编辑文档必须保留一个可落光标的段落。
  const document = imported.childCount ? imported : canvasSchema.topNodeType.createAndFill()!
  document.check()
  // 返回 Schema 规范化后的快照；同一组 Marks 不因 Markdown 嵌套顺序产生假差异。
  return document.toJSON()
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
  try {
    return serializeEditorDocument(
      { markdown: createCanvasMarkdownManager(), schema: canvasSchema },
      document,
    )
  } catch (cause) {
    if (cause instanceof Error && cause.message.includes('源码模式')) {
      throw new Error('当前内容无法可靠转换为 Markdown，未保存；正文仍保留在画布中。', { cause })
    }
    throw cause
  }
}
