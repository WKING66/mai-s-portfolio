import { describe, expect, it } from 'vitest'
import type { ElementNode, Node } from 'comark'
import contract from '../../../resources/contracts/markdown-math.json'
import {
  canvasSchema,
  importCanvasMarkdown,
  exportCanvasMarkdown,
} from '../utils/editor/canvasMarkdown'
import { editorContentMeaning } from '../utils/editor/markdownOutput'
import { parseBlogMarkdown } from '../utils/markdown'

const elements = (nodes: Node[]): ElementNode[] =>
  nodes.flatMap((node) =>
    Array.isArray(node) ? [node as ElementNode, ...elements(node.slice(2) as Node[])] : [],
  )
const unique = (values: string[]) => [...new Set(values)].sort()

describe('数学与资源的前后端交换契约', () => {
  it.each(contract.cases)(
    '$id：编辑与公开阅读不把公式内容作为资源',
    async ({ source, math, targets, mathLinks }) => {
      const json = importCanvasMarkdown(source)
      const document = canvasSchema.nodeFromJSON(json)
      const editorTargets: string[] = []
      const linkedMath: string[] = []
      let editorMath = 0
      document.descendants((node) => {
        if (node.type.name.startsWith('preservedMath')) {
          editorMath++
          linkedMath.push(
            ...node.marks
              .filter((mark) => mark.type.name === 'link')
              .map((mark) => String(mark.attrs.href)),
          )
        }
        if (node.type.name === 'image') {
          editorTargets.push(String(node.attrs.src))
        }
        node.marks
          .filter((mark) => mark.type.name === 'link')
          .forEach((mark) => editorTargets.push(String(mark.attrs.href)))
      })
      expect(editorMath, '编辑器公式数').toBe(math)
      expect(unique(editorTargets), '编辑器真实资源').toEqual(unique(targets))
      expect(unique(linkedMath), '外层链接必须覆盖公式原子').toEqual(unique(mathLinks ?? []))
      const output = exportCanvasMarkdown(json)
      expect(editorContentMeaning(importCanvasMarkdown(output))).toEqual(editorContentMeaning(json))
      for (const markdown of [source, output]) {
        const nodes = elements((await parseBlogMarkdown(markdown)).nodes)
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
      }
    },
  )
})
