import { Node, type JSONContent } from '@tiptap/core'
import CodeBlock from '@tiptap/extension-code-block'
import Image from '@tiptap/extension-image'
import Link from '@tiptap/extension-link'
import { TaskList, TaskItem } from '@tiptap/extension-list'
import { TableKit } from '@tiptap/extension-table'
import { safeBlogUrl } from '../blogResources'

/** 内部引用只进入 Markdown 属性；DOM 上不挂临时地址，也不直接导航内部协议。 */
export function isEditorUrl(uri: string): boolean {
  return /^(?:asset:\/\/[1-9]\d*|resource:\/\/[\w-]+)$/.test(uri) || Boolean(safeBlogUrl(uri))
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

const StableImage = Image.extend({
  renderHTML({ node }) {
    // 资源在编辑器中显示稳定身份占位，签名地址的续签仍由已保存预览承担。
    // 这也避免未获确认的外部图片在打开草稿时被自动请求。
    return [
      'span',
      {
        class: 'editor-resource',
        contenteditable: 'false',
        role: 'img',
        'aria-label': node.attrs.alt || '图片',
      },
      '图片：' + (node.attrs.alt || node.attrs.src),
    ]
  },
}).configure({ inline: true, allowBase64: false })

export const EDITOR_STARTER_KIT = {
  codeBlock: false,
  link: false,
  underline: false,
  trailingNode: false,
} as const

/** UEditor 和保真预检共用扩展集合，不能各自维护不同 Markdown 方言。 */
export const BLOG_EDITOR_EXTENSIONS = [
  PreservedCodeBlock,
  StableImage,
  Link.extend({
    renderHTML({ HTMLAttributes }) {
      const uri = String(HTMLAttributes.href || '')
      if (/^(?:resource|asset):\/\//.test(uri)) {
        return ['span', { class: 'editor-resource', title: uri }, 0]
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
