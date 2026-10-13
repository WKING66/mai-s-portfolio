import { createMarkdownParser, type ElementNode, type Node, type ComarkPlugin } from 'comark'
import security from 'comark/plugins/security'
import toc from 'comark/plugins/toc'
import taskList from 'comark/plugins/task-list'
import math from 'comark/plugins/math'
import mermaid from 'comark/plugins/mermaid'
import { MATH_LIMITS, mathFallback, renderBlogMath, compactBlogMath } from './blogMath.ts'
import { textContent } from 'comark/utils'
import { countBlogReading } from './blogReadingProbe.ts'

// 不注册 HTML、属性、组件、Frontmatter 插件，Markdown 只能渲染允许的正文元素。
const options = { registerDefaultPlugins: false, autoClose: false, linkify: false } as const
const sanitize = security({
  allowedTags: ['h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'p', 'a', 'strong', 'em', 's', 'del', 'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'span', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'img', 'hr', 'br', 'input', 'math', 'mermaid'],
  // 内部引用只交给受控的图片/链接组件，经当前版本资源列表解析后才能请求。
  allowedProtocols: ['http', 'https', 'mailto', 'asset', 'resource'], allowDataImages: false,
})
const attributes: Record<string, string[]> = {
  a: ['href', 'title'], img: ['src', 'alt', 'title'], pre: ['language', 'filename'], code: ['class'],
  ul: ['class'], ol: ['start', 'class'], li: ['class'], input: ['type', ':checked', ':disabled'],
  th: ['align'], td: ['align'],
  mermaid: ['content'],
}
const highlight: ComarkPlugin = {
  name: 'blog-shiki-themes',
  async post({ tree }) {
    const headingIds = new Set<string>()
    let highlightedCharacters = 0, highlightedNodes = 0
    let formulas = 0, mathCharacters = 0
    async function visit(node: Node): Promise<void> {
      if (!Array.isArray(node)) return
      if (node[0] === 'math') {
        // 该节点只能由 Comark 数学规则产生；作者 HTML / 组件插件仍关闭。
        const source = textContent(node), block = String(node[1].class).includes('block')
        mathCharacters += source.length
        const rendered = ++formulas > MATH_LIMITS.formulas || mathCharacters > MATH_LIMITS.documentCharacters
          ? mathFallback(source, block) : await renderBlogMath(source, block)
        node.splice(0, node.length, ...await compactBlogMath(rendered))
        return
      }
      if (node[0] === 'th' || node[0] === 'td') {
        const alignment = String(node[1].style || '').match(/^text-align:\s*(left|center|right);?$/)
        if (alignment) node[1].align = alignment[1]
      }
      // 只保留标准 Markdown / task-list 生成的属性；高亮样式在此边界之后由 Shiki 生成。
      for (const name of Object.keys(node[1])) if (!attributes[String(node[0])]?.includes(name)) delete node[1][name]
      if (/^h[1-6]$/.test(String(node[0]))) {
        // Comark 0.7 的默认 slug 会剥离中文；保留 Unicode，重复标题仍获得唯一锚点。
        const label = textContent(node)
        const base = label.toLowerCase().trim().replace(/[^\p{L}\p{N}\s_-]/gu, '').replace(/\s+/g, '-') || 'heading'
        let id = base, suffix = 1
        while (headingIds.has(id)) id = base + '-' + suffix++
        headingIds.add(id); node[1].id = id; node[1]['data-toc-label'] = label
      }
      if (node[0] === 'pre') {
        const code = textContent(node)
        const { highlightCode, codeNodeCount, CODE_HIGHLIGHT_LIMITS: limits } = await import('./codeHighlight')
        const plain = highlightedCharacters + code.length > limits.documentCharacters || highlightedNodes >= limits.documentNodes
        let highlighted = await highlightCode(code, String(node[1].language || '').toLowerCase(), plain)
        if (highlightedNodes + codeNodeCount(highlighted) > limits.documentNodes)
          highlighted = await highlightCode(code, String(node[1].language || '').toLowerCase(), true)
        highlightedCharacters += code.length
        highlightedNodes += codeNodeCount(highlighted)
        // 保留原文供复制；高亮仅生成安全 AST，永远不注入原始 HTML。
        node.splice(2, node.length - 2, ...highlighted.slice(2))
        node[1] = { ...node[1], ...highlighted[1], code }
        return
      }
      for (const child of node.slice(2)) await visit(child as Node)
    }
    for (const node of tree.nodes) await visit(node)
  },
}
const parse = createMarkdownParser({ ...options, plugins: [taskList(), math(), mermaid(), sanitize, highlight, toc({ depth: 5 })] })
// 缓存仅包含资源原始引用的安全 AST，不缓存签名 URL。版本正文相同才复用。
export const BLOG_PARSE_CACHE_LIMITS = { entries: 4, characters: 200000 } as const
const parsed = new Map<string, ReturnType<typeof parse>>()
let cachedCharacters = 0
export const parseBlogMarkdown: typeof parse = (...args) => {
  // 额外解析选项不参与共享缓存，避免不同方言参数复用错误结果。
  const source = args[0]
  if (args.length > 1 || typeof source !== 'string' || source.length > BLOG_PARSE_CACHE_LIMITS.characters) { countBlogReading('markdownParse'); return parse(...args) }
  const cached = parsed.get(source)
  if (cached) { parsed.delete(source); parsed.set(source, cached); countBlogReading('parseCacheHit'); return cached }
  countBlogReading('markdownParse')
  const result = parse(...args)
  parsed.set(source, result); cachedCharacters += source.length
  while (parsed.size > BLOG_PARSE_CACHE_LIMITS.entries || cachedCharacters > BLOG_PARSE_CACHE_LIMITS.characters) {
    const first = parsed.keys().next().value!; parsed.delete(first); cachedCharacters -= first.length
  }
  void result.catch(() => { if (parsed.get(source) === result) { parsed.delete(source); cachedCharacters -= source.length } })
  return result
}
const parsePlain = createMarkdownParser({ ...options, plugins: [taskList()] })

// 比较正文结构而非空白/分隔线写法；遇到转换差异时保留源码并阻止进入富文本。
export async function markdownSignature(source: string) {
  const tree = await parsePlain(source)
  function normalize(node: Node): unknown {
    if (typeof node === 'string') return node
    const [tag, attributes, ...children] = node as ElementNode
    const props = Object.fromEntries(Object.entries(attributes).filter(([key]) => ['href', 'src', 'alt', 'title', 'language', 'filename', 'align', 'start', ':checked', ':disabled', 'type'].includes(key)))
    if (tag === 'th' || tag === 'td') {
      const alignment = String(attributes.style || '').match(/^text-align:\s*(left|center|right);?$/)
      if (alignment) props.align = alignment[1]
    }
    if (['strong', 'em', 's', 'del'].includes(String(tag))) {
      const marks = [tag === 'del' ? 's' : tag]
      let body = children
      while (body.length === 1 && Array.isArray(body[0]) && ['strong', 'em', 's', 'del'].includes(String(body[0][0]))) {
        const next = body[0] as ElementNode
        marks.push(next[0] === 'del' ? 's' : next[0]); body = next.slice(2) as Node[]
      }
      return [marks.sort(), ...body.map(child => normalize(child as Node))]
    }
    return [tag, props, ...children.map(child => normalize(child as Node))]
  }
  return JSON.stringify(tree.nodes.map(normalize))
}
