import type { ElementNode, Node } from 'comark'
import { parseDocument } from 'htmlparser2'
import { countBlogReading } from './blogReadingProbe.ts'
import { render } from 'comark/render'
import { escapeHtml } from 'comark/utils'

export const MATH_LIMITS = { characters: 4000, formulas: 128, documentCharacters: 32000, maxExpand: 200, maxSize: 20 } as const
const tags = new Set('span math semantics annotation mrow mi mo mn mtext mspace msup msub msubsup mfrac msqrt mroot mstyle mtable mtr mtd mover munder munderover mpadded menclose mphantom svg path line'.split(' '))
const attributes = new Set('class style xmlns aria-hidden encoding display mathvariant stretchy fence separator accent accentunder largeop movablelimits symmetric lspace rspace minsize maxsize scriptlevel displaystyle columnalign columnspacing rowspacing width height depth voffset viewBox preserveAspectRatio d fill stroke stroke-width x1 x2 y1 y2'.split(' '))

/** 只序列化上面白名单过滤后的 KaTeX AST；不接受作者 HTML 或组件属性。
 * 静态公式不需要为每一个排版 span/MathML 节点创建 Vue 实例和更新路径。
 * Comark HTML 文本处理保留实体，因此先转义文本，避免实体改变可见内容。 */
export async function compactBlogMath(node: ElementNode): Promise<ElementNode> {
  if (node[0] !== 'blog-math') return node
  function escaped(child: Node): Node {
    return typeof child === 'string' ? escapeHtml(child.replaceAll('&', '&amp;')) : [child[0], child[1], ...child.slice(2).map(item => escaped(item as Node))] as ElementNode
  }
  const html = await render({ nodes: node.slice(2).map(child => escaped(child as Node)) }, { format: 'text/html' })
  return ['blog-math', { block: node[1].block, html: '<span>' + html.trim() + '</span>' }]
}

export function mathFallback(source: string, block: boolean): ElementNode {
  return ['span', { class: 'blog-math-fallback' + (block ? ' blog-math-block' : ''), title: '公式无法渲染，保留 LaTeX 原文' }, source]
}

/** 仅解析 KaTeX 生成物，不解析作者 HTML；输出允许的 MathML/SVG/排版节点，不使用 v-html。 */
export async function renderBlogMath(source: string, block: boolean): Promise<ElementNode> {
  if (source.length > MATH_LIMITS.characters) return mathFallback(source, block)
  try {
    const katex = (await import('katex')).default
    countBlogReading('katexRender')
    const html = katex.renderToString(source, {
      displayMode: block, output: 'htmlAndMathml', throwOnError: true,
      trust: false, strict: 'ignore', maxExpand: MATH_LIMITS.maxExpand, maxSize: MATH_LIMITS.maxSize,
      // 每次新的宏对象；\gdef 不会污染其他公式、文章或 SSR 请求。
      macros: {}, globalGroup: false,
    })
    function convert(node: ReturnType<typeof parseDocument>['children'][number]): Node | null {
      if (node.type === 'text') return node.data
      if (!('attribs' in node) || !tags.has(node.name)) return null
      const props = Object.fromEntries(Object.entries(node.attribs).filter(([key]) => attributes.has(key)))
      return [node.name, props, ...node.children.map(convert).filter((child): child is Node => child !== null)]
    }
    const nodes = parseDocument(html, { xmlMode: true, decodeEntities: true }).children.map(convert).filter((node): node is Node => node !== null)
    return ['blog-math', { block }, ...nodes]
  } catch { return mathFallback(source, block) }
}
