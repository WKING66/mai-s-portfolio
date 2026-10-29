import type { ElementNode, Node } from 'comark'
import { parseDocument, DomUtils } from 'htmlparser2'
import { render } from 'comark/render'
import { escapeHtml } from 'comark/utils'

/** 仅记录本模块过滤后的生成物；通用 Markdown HTML 渲染会向 MathML 额外插入换行。 */
const compactMathHtml = new WeakMap<ElementNode, string>()

export const MATH_LIMITS = {
  characters: 4000,
  formulas: 128,
  documentCharacters: 32000,
  maxExpand: 200,
  maxSize: 20,
} as const
const allowedMathTags = new Set(
  'span math semantics annotation mrow mi mo mn mtext mspace msup msub msubsup mfrac msqrt mroot mstyle mtable mtr mtd mover munder munderover mpadded menclose mphantom svg path line'.split(
    ' ',
  ),
)
const allowedMathAttributes = new Set(
  'class style xmlns aria-hidden encoding display mathvariant stretchy fence separator accent accentunder largeop movablelimits symmetric lspace rspace minsize maxsize scriptlevel displaystyle columnalign columnspacing rowspacing width height depth voffset viewBox preserveAspectRatio d fill stroke stroke-width x1 x2 y1 y2'.split(
    ' ',
  ),
)

/** 只序列化上面白名单过滤后的 KaTeX AST；不接受作者 HTML 或组件属性。
 * 静态公式不需要为每一个排版 span/MathML 节点创建 Vue 实例和更新路径。
 * Comark HTML 文本处理保留实体，因此先转义文本，避免实体改变可见内容。 */
export async function compactBlogMath(node: ElementNode): Promise<ElementNode> {
  if (node[0] !== 'blog-math') {
    return node
  }
  const compact = compactMathHtml.get(node)
  if (compact !== undefined) {
    return ['blog-math', { block: node[1].block, html: compact }]
  }
  function escapeMathNode(child: Node): Node {
    return typeof child === 'string'
      ? escapeHtml(child.replaceAll('&', '&amp;'))
      : ([
          child[0],
          child[1],
          ...child.slice(2).map((item) => escapeMathNode(item as Node)),
        ] as ElementNode)
  }
  const html = await render(
    { nodes: node.slice(2).map((child) => escapeMathNode(child as Node)) },
    { format: 'text/html' },
  )
  return ['blog-math', { block: node[1].block, html: '<span>' + html.trim() + '</span>' }]
}

export function mathFallback(source: string, block: boolean): ElementNode {
  return [
    'span',
    {
      class: 'blog-math-fallback' + (block ? ' blog-math-block' : ''),
      title: '公式无法渲染，保留 LaTeX 原文',
    },
    source,
  ]
}

/** 仅解析 KaTeX 生成物，不解析作者 HTML；输出允许的 MathML/SVG/排版节点，不使用 v-html。 */
export async function renderBlogMath(source: string, block: boolean): Promise<ElementNode> {
  if (source.length > MATH_LIMITS.characters) {
    return mathFallback(source, block)
  }
  try {
    // 1. 按需加载 KaTeX；每个公式使用独立宏环境和固定安全限额。
    const katex = (await import('katex')).default
    const html = katex.renderToString(source, {
      displayMode: block,
      output: 'htmlAndMathml',
      throwOnError: true,
      trust: false,
      strict: 'ignore',
      maxExpand: MATH_LIMITS.maxExpand,
      maxSize: MATH_LIMITS.maxSize,
      // 每次新的宏对象；\gdef 不会污染其他公式、文章或 SSR 请求。
      macros: {},
      globalGroup: false,
    })
    // 2. 只转换 KaTeX 生成物的白名单标签及属性，不信任作者 HTML。
    function convert(node: ReturnType<typeof parseDocument>['children'][number]): Node | null {
      if (node.type === 'text') {
        return node.data
      }
      if (!('attribs' in node) || !allowedMathTags.has(node.name)) {
        return null
      }
      const safeAttributes = Object.fromEntries(
        Object.entries(node.attribs).filter(([key]) => allowedMathAttributes.has(key)),
      )
      // 同一份白名单同时约束 AST 与紧凑 HTML，不重复解析，也不以正则删除真实公式空格。
      const children: Node[] = []
      node.attribs = safeAttributes
      node.children = node.children.filter((child) => {
        const converted = convert(child)
        if (converted === null) {
          return false
        }
        children.push(converted)
        return true
      })
      return [node.name, safeAttributes, ...children]
    }
    const nodes: Node[] = []
    const generated = parseDocument(html, { xmlMode: true, decodeEntities: true })
    generated.children = generated.children.filter((child) => {
      const converted = convert(child)
      if (converted === null) {
        return false
      }
      nodes.push(converted)
      return true
    })
    const result: ElementNode = ['blog-math', { block }, ...nodes]
    compactMathHtml.set(result, '<span>' + DomUtils.getOuterHTML(generated.children) + '</span>')
    return result
  } catch {
    return mathFallback(source, block)
  }
}
