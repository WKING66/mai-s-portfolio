import { describe, expect, it } from 'vitest'
import type { ElementNode, Node } from 'comark'
import contract from '../../../resources/contracts/markdown-math.json'
import { parseBlogMarkdown } from '../utils/markdown'

const elements = (nodes: Node[]): ElementNode[] =>
  nodes.flatMap((node) =>
    Array.isArray(node) ? [node as ElementNode, ...elements(node.slice(2) as Node[])] : [],
  )
const unique = (values: string[]) => [...new Set(values)].sort()

describe('数学与资源的前后端交换契约', () => {
  it.each([
    ...contract.cases,
    {
      id: 'quote-cannot-close-outside',
      source: '> $$\n> x\n\n[real](resource://real)\n\n$$',
      math: 0,
      targets: ['resource://real'],
      mathLinks: [],
    },
    {
      id: 'list-cannot-close-outside',
      source: '- $$\n\n[real](resource://real)\n\n$$',
      math: 0,
      targets: ['resource://real'],
      mathLinks: [],
    },
    {
      id: 'unmatched-escapes',
      source: '$' + '\\'.repeat(2048),
      math: 0,
      targets: [],
      mathLinks: [],
    },
  ])('$id：公开阅读不把公式内容作为资源', async ({ source, math, targets, mathLinks }) => {
    const nodes = elements((await parseBlogMarkdown(source)).nodes)
    const readerMath = nodes.filter(
      (node) => node[0] === 'blog-math' || String(node[1].class).includes('blog-math-fallback'),
    )
    expect(readerMath.length, '阅读器公式数').toBe(math)
    const readerMathLinks = nodes
      .filter(
        (node) =>
          node[0] === 'a' &&
          elements(node.slice(2) as Node[]).some(
            (child) =>
              child[0] === 'blog-math' || String(child[1].class).includes('blog-math-fallback'),
          ),
      )
      .map((node) => String(node[1].href))
    expect(unique(readerMathLinks), '阅读器外层链接范围').toEqual(unique(mathLinks ?? []))
    expect(
      unique(
        nodes.flatMap((node) => {
          if (node[0] === 'a') {
            return [String(node[1].href)]
          }
          return node[0] === 'img' ? [String(node[1].src)] : []
        }),
      ),
      '阅读器真实资源',
    ).toEqual(unique(targets))
  })
})
