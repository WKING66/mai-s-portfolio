import { describe, expect, it } from 'vitest'
import type { ElementNode, Node } from 'comark'
import { parseBlogMarkdown } from '../utils/markdown'
import { renderBlogMath, compactBlogMath, MATH_LIMITS } from '../utils/blogMath'
import { parseDocument, DomUtils } from 'htmlparser2'
import { BLOG_MATH_FIXTURE } from './fixtures/blogMathFixture'
import { textContent } from 'comark/utils'
const elements = (nodes: Node[]): ElementNode[] => nodes.flatMap(node => Array.isArray(node) ? [node as ElementNode, ...elements(node.slice(2) as Node[])] : [])

describe('Comark math with bounded, untrusted KaTeX', () => {
  it('renders required LaTeX fixtures to safe HTML/MathML AST and leaves code dollars unchanged', async () => {
    const tree = await parseBlogMarkdown(BLOG_MATH_FIXTURE), nodes = elements(tree.nodes)
    expect(nodes.filter(node => node[0] === 'blog-math').length).toBeGreaterThanOrEqual(10)
    const math = parseDocument(nodes.filter(node => node[0] === 'blog-math').map(node => node[1].html).join(''))
    expect(DomUtils.findAll(node => node.name === 'mfrac', math.children).length).toBeGreaterThan(2)
    expect(DomUtils.findAll(node => node.name === 'mtable', math.children).length).toBeGreaterThanOrEqual(2)
    expect(DomUtils.findAll(node => ['script', 'img', 'iframe', 'a'].includes(node.name) || Object.keys(node.attribs).some(key => /^on|^href$|^src$/i.test(key)), math.children)).toHaveLength(0)
    expect(nodes.find(node => node[0] === 'pre')?.[1].code).toBe("const formula = '$a^2+b^2=c^2$'")
    expect(nodes.some(node => ['script', 'img', 'iframe', 'a'].includes(node[0]))).toBe(false)
    expect(nodes.some(node => Object.keys(node[1]).some(key => /^on|^href$|^src$/i.test(key)))).toBe(false)
  })
  it('keeps entities and original TeX intact in compact static MathML, without exposing author HTML', async () => {
    const source = String.raw`\text{\&lt; <> " '}`
    const compact = await compactBlogMath(await renderBlogMath(source, false))
    expect(compact[0]).toBe('blog-math')
    expect(compact).toHaveLength(2)
    const dom = parseDocument(String(compact[1].html))
    const annotation = DomUtils.findAll(node => node.name === 'annotation', dom.children)[0]!
    expect(DomUtils.textContent(annotation)).toBe(source)
    expect(DomUtils.findAll(node => node.name === 'math', dom.children)).toHaveLength(1)
    const authored = elements((await parseBlogMarkdown('<blog-math html="<img src=x onerror=alert(1)>">x</blog-math>')).nodes)
    expect(authored.some(node => node[0] === 'blog-math' || node[0] === 'img')).toBe(false)
  })
  it('does not generate layout-only whitespace between MathML nodes and preserves authored text spaces', async () => {
    const source = String.raw`\text{a b} + \text{ } + x_1^2`
    const ast = await renderBlogMath(source, false)
    const expectedText = elements([ast])
      .filter((node) => node[0] === 'mtext')
      .map(node => textContent(node))
    const compact = await compactBlogMath(ast)
    const dom = parseDocument(String(compact[1].html))
    const containers = DomUtils.findAll(
      (node) => ['math', 'semantics', 'mrow', 'mfrac', 'msup', 'msubsup'].includes(node.name),
      dom.children,
    )
    const formattingWhitespace = containers
      .flatMap((node) => node.children)
      .filter((node) => node.type === 'text' && /^\s+$/.test(node.data))
    expect(formattingWhitespace).toHaveLength(0)
    expect(
      DomUtils.findAll((node) => node.name === 'mtext', dom.children).map(DomUtils.textContent),
    ).toEqual(expectedText)
    expect(
      DomUtils.textContent(
        DomUtils.findAll((node) => node.name === 'annotation', dom.children)[0]!,
      ),
    ).toBe(source)
  })
  it('keeps invalid/oversized formulas as escaped source and does not throw or inject HTML', async () => {
    for (const source of [String.raw`\invalid{<script>alert(1)</script>}`, 'x'.repeat(MATH_LIMITS.characters + 1), String.raw`\def\loop{\loop}\loop`]) {
      const node = await renderBlogMath(source, true)
      expect(node[0]).toBe('span')
      expect(textContent(node)).toBe(source)
      expect(node[1].class).toContain('blog-math-fallback')
    }
  })
  it('disallows URL/HTML extensions and isolates macro definitions between calls', async () => {
    const sources = [String.raw`\href{javascript:alert(1)}{click}`, String.raw`\includegraphics{https://example.com/x.png}`, String.raw`\htmlStyle{position:fixed}{x}`, String.raw`\htmlClass{attack}{x}`]
    for (const source of sources) {
      const nodes = elements([await renderBlogMath(source, false)])
      expect(nodes.some(node => ['a', 'img', 'script'].includes(node[0]))).toBe(false)
      expect(nodes.some(node => String(node[1].class || '').includes('attack'))).toBe(false)
      expect(nodes.some(node => String(node[1].style || '').includes('position:fixed'))).toBe(false)
    }
    await renderBlogMath(String.raw`\gdef\private{123}\private`, false)
    expect((await renderBlogMath(String.raw`\private`, false))[1].class).toContain('blog-math-fallback')
  })
  it('keeps escaped dollar symbols and code literal and limits a document full of formulas', async () => {
    const source = String.raw`\$not-math\$` + '\n\n`$inline-code$`\n\n' + '$x$ '.repeat(MATH_LIMITS.formulas + 3)
    const nodes = elements((await parseBlogMarkdown(source)).nodes)
    expect(nodes.filter(node => node[0] === 'blog-math')).toHaveLength(MATH_LIMITS.formulas)
    expect(nodes.filter(node => String(node[1].class || '').includes('blog-math-fallback'))).toHaveLength(3)
    expect(textContent(nodes.find(node => node[0] === 'code')!)).toBe('$inline-code$')
  })
})
