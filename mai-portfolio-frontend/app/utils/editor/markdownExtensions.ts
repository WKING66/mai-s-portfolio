import Paragraph from '@tiptap/extension-paragraph'
import { Node, type JSONContent } from '@tiptap/core'
import CodeBlock from '@tiptap/extension-code-block'
import Image from '@tiptap/extension-image'
import Link from '@tiptap/extension-link'
import { TaskList, TaskItem } from '@tiptap/extension-list'
import { TableKit } from '@tiptap/extension-table'
import { safeBlogUrl } from '../blogResources'

/** 稳定身份可以进入 DOM 恢复标记，但不直接作为可导航 URL。 */
function isStableReference(uri: string): boolean {
  return /^(?:asset:\/\/[1-9]\d*|resource:\/\/[\w-]+)$/.test(uri)
}
export function isEditorUrl(uri: string): boolean {
  return isStableReference(uri) || Boolean(safeBlogUrl(uri))
}

function nodeText(node: JSONContent): string {
  return node.text ?? node.content?.map(nodeText).join('') ?? ''
}

export const PreservedCodeBlock = CodeBlock.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      originalFence: { default: null, rendered: false },
      originalText: { default: null, rendered: false },
      originalLanguage: { default: null, rendered: false },
    }
  },
  parseMarkdown(token, helpers) {
    return helpers.createNode(
      'codeBlock',
      {
        language: token.lang || null,
        originalFence: token.raw?.trimEnd(),
        originalText: token.text || '',
        originalLanguage: token.lang || null,
      },
      token.text ? [helpers.createTextNode(token.text)] : [],
    )
  },
  renderMarkdown(node) {
    const text = nodeText(node)
    const language = node.attrs?.language || ''
    // 1. 未修改的代码逐字保留围栏、文件名等 info string，不能经格式化器重写。
    if (
      node.attrs?.originalFence &&
      text === node.attrs.originalText &&
      language === (node.attrs.originalLanguage || '')
    ) {
      return node.attrs.originalFence
    }
    // 2. 编辑后选择比正文反引号更长的围栏，避免嵌套代码截断正文。
    const longestRun = Math.max(
      2,
      ...Array.from(text.matchAll(/\x60+/g), (match) => match[0].length),
    )
    const fence = String.fromCharCode(96).repeat(longestRun + 1)
    return fence + language.replace(/[\r\n]/g, '') + '\n' + text + '\n' + fence
  },
}).configure({ enableTabIndentation: true })

function preservedMath(block: boolean) {
  const name = block ? 'preservedMathBlock' : 'preservedMathInline'
  const pattern = block
    ? /^(?:\$\$[\s\S]+?\$\$|\\\[[\s\S]+?\\\])(?:\n|$)/
    : /^(?:\$(?!\$)(?:\\.|[^$\n])+?\$(?!\$)|\\\([\s\S]+?\\\))/
  return Node.create({
    name,
    group: block ? 'block' : 'inline',
    inline: !block,
    atom: true,
    selectable: true,
    addAttributes() {
      return { source: { default: '', rendered: false } }
    },
    parseHTML() {
      return []
    },
    renderHTML({ node }) {
      // 公式作为不可编辑的原文节点显示；修改表达式使用源码模式，绝不执行作者 HTML。
      return [
        block ? 'div' : 'span',
        {
          class: 'editor-preserved-math',
          contenteditable: 'false',
          title: '公式原文；请在源码模式修改',
        },
        node.attrs.source,
      ]
    },
    markdownTokenizer: {
      name,
      level: block ? 'block' : 'inline',
      start(source) {
        return source.search(block ? /\$\$|\\\[/ : /\$|\\\(/)
      },
      tokenize(source) {
        const match = source.match(pattern)
        if (match) {
          return { type: name, raw: match[0], text: match[0].trimEnd() }
        }
      },
    },
    parseMarkdown(token, helpers) {
      return helpers.createNode(name, { source: token.text })
    },
    renderMarkdown(node) {
      return node.attrs?.source || ''
    },
  })
}

// DOM 恢复规则与占位渲染成对维护，保证输入法重读段落时仍能恢复稳定资源身份。
const StableImage = Image.extend({
  parseHTML() {
    return [
      {
        tag: 'span[data-editor-image-ref]',
        getAttrs(element) {
          const src = element.getAttribute('data-editor-image-ref') || ''
          return isEditorUrl(src)
            ? {
                src,
                alt: element.getAttribute('data-editor-image-alt'),
                title: element.getAttribute('title'),
              }
            : false
        },
      },
    ]
  },
  renderHTML({ node }) {
    // 资源在编辑器中显示稳定身份占位，签名地址的续签仍由已保存预览承担。
    // 这也避免未获确认的外部图片在打开草稿时被自动请求。
    return [
      'span',
      {
        class: 'editor-resource',
        'data-editor-image-ref': node.attrs.src,
        'data-editor-image-alt': node.attrs.alt,
        title: node.attrs.title,
        contenteditable: 'false',
        role: 'img',
        'aria-label': node.attrs.alt || '图片',
      },
      '图片：' + (node.attrs.alt || node.attrs.src),
    ]
  },
}).configure({ inline: true, allowBase64: false })

export const EDITOR_STARTER_KIT = {
  paragraph: false,
  codeBlock: false,
  link: false,
  underline: false,
  trailingNode: false,
} as const

/** UEditor 和保真预检共用扩展集合，不能各自维护不同 Markdown 方言。 */
export const BLOG_EDITOR_EXTENSIONS = [
  Paragraph.extend({
    parseMarkdown(token, helpers) {
      // TipTap 默认会把独立图片从段落中提到根部；行内图片必须留在段落内，保证 Schema 有效。
      if (token.tokens?.length === 1 && token.tokens[0]?.type === 'image') {
        return helpers.createNode('paragraph', undefined, helpers.parseInline(token.tokens))
      }
      return (
        this.parent?.(token, helpers) ??
        helpers.createNode('paragraph', undefined, helpers.parseInline(token.tokens || []))
      )
    },
  }),
  PreservedCodeBlock,
  StableImage,
  Link.extend({
    parseHTML() {
      return [
        {
          tag: 'span[data-editor-resource-link]',
          getAttrs(element) {
            const href = element.getAttribute('data-editor-resource-link') || ''
            return isStableReference(href) ? { href, title: element.getAttribute('title') } : false
          },
        },
        ...(this.parent?.() || []),
      ]
    },
    renderHTML({ HTMLAttributes }) {
      const uri = String(HTMLAttributes.href || '')
      if (isStableReference(uri)) {
        return [
          'span',
          { class: 'editor-resource', 'data-editor-resource-link': uri, title: uri },
          0,
        ]
      }
      return [
        'a',
        { href: safeBlogUrl(uri), title: HTMLAttributes.title, rel: 'noopener noreferrer' },
        0,
      ]
    },
  }).configure({ openOnClick: false, autolink: false, isAllowedUri: isEditorUrl }),
  TaskList,
  TaskItem.configure({ nested: true }),
  TableKit.configure({ table: { resizable: false, renderWrapper: true } }),
  preservedMath(true),
  preservedMath(false),
]
