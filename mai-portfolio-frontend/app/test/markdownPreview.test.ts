import { describe, expect, it } from 'vitest'
import { parseBlogMarkdown, markdownSignature } from '../utils/markdown'
import { codeLabel } from '../constants/codeThemes'
import { MARKDOWN_READING_SAMPLE } from './fixtures/markdownReading'
import { textContent } from 'comark/utils'
import { highlightCode } from '../utils/codeHighlight'
import type { Node, ElementNode } from 'comark'

const elements = (nodes: Node[]): ElementNode[] =>
  nodes.flatMap((node) =>
    Array.isArray(node) ? [node as ElementNode, ...elements(node.slice(2) as Node[])] : [],
  )
describe('Markdown rendering and format comparison', () => {
  it('loads C and C++ used by real algorithm articles through the same Shiki renderer', async () => {
    for (const language of ['C', 'C++']) {
      const tree = await parseBlogMarkdown('```' + language + '\nint main() { return 0; }\n```')
      const block = elements(tree.nodes).find((node) => node[0] === 'pre')!
      expect(block[1]['data-highlight']).toBe('pending')
      expect(elements([block]).some((node) => node[0] === 'span')).toBe(false)
      const colored = await highlightCode(String(block[1].code), language)
      expect(elements([colored]).some((node) => node[0] === 'span' && node[1].style)).toBe(true)
      expect(textContent(block)).toBe('int main() { return 0; }')
    }
  })
  it('retains tables, deletion, task state, labels and internal resources without enabling author components', async () => {
    const tree = await parseBlogMarkdown(
      '| A | B |\n|:---|---:|\n| ~~x~~ | y |\n\n- [x] done\n- [ ] todo\n\n![a](resource://image)\n\n[file](asset://9)',
    )
    const nodes = elements(tree.nodes)
    expect(nodes.some((node) => node[0] === 'table')).toBe(true)
    expect(nodes.filter((node) => node[0] === 'th').map((node) => node[1].align)).toEqual([
      'left',
      'right',
    ])
    expect(nodes.some((node) => ['s', 'del'].includes(String(node[0])))).toBe(true)
    expect(nodes.filter((node) => node[0] === 'input')).toHaveLength(2)
    expect(nodes.find((node) => node[0] === 'img')?.[1].src).toBe('resource://image')
    expect(nodes.find((node) => node[0] === 'a')?.[1].href).toBe('asset://9')
    expect(await markdownSignature('- [x] done')).not.toBe(await markdownSignature('- [ ] done'))
    expect(await markdownSignature('| A |\n|---:|\n| x |')).not.toBe(
      await markdownSignature('| A |\n|:---|\n| x |'),
    )
  })
  it('delivers large code as complete text before any language/highlight work', async () => {
    const code = 'const long = "' + 'a'.repeat(15000) + '";'
    const tree = await parseBlogMarkdown('```typescript [long.ts]\n' + code + '\n```')
    const block = elements(tree.nodes).find((node) => node[0] === 'pre')!
    expect(block[1]['data-highlight']).toBe('pending')
    expect(block[1].filename).toBe('long.ts')
    expect(textContent(block)).toBe(code)
    expect(elements([block])).toHaveLength(2)
  })
  it('preserves Chinese heading anchors and disambiguates repeated headings', async () => {
    const tree = await parseBlogMarkdown('## 中文标题\n\n## 中文标题\n\n## 中文标题-1')
    expect(
      elements(tree.nodes)
        .filter((node) => node[0] === 'h2')
        .map((node) => node[1].id),
    ).toEqual(['中文标题', '中文标题-1', '中文标题-1-1'])
    expect(tree.meta.toc.links.map((link) => link.id)).toEqual([
      '中文标题',
      '中文标题-1',
      '中文标题-1-1',
    ])
  })
  it('provides usable optional code labels', () => {
    expect(codeLabel(' UserService.java ', 'java')).toBe('UserService.java')
    expect(codeLabel('', 'typescript')).toBe('typescript')
    expect(codeLabel(' ', '')).toBe('代码')
  })
  it('highlights all seven languages with five token palettes and safe fallback', async () => {
    const tree = await parseBlogMarkdown(MARKDOWN_READING_SAMPLE)
    const blocks = elements(tree.nodes).filter((node) => node[0] === 'pre')
    expect(blocks).toHaveLength(9)
    for (const block of blocks.slice(0, 7)) {
      expect(block[1]['data-highlight']).toBe('pending')
      const colored = await highlightCode(String(block[1].code), String(block[1].language))
      const spans = elements([colored]).filter((node) => node[0] === 'span' && node[1].style)
      expect(spans.length).toBeGreaterThan(1)
      expect(String(spans[0]![1].style)).toContain('--shiki-light:')
      expect(String(spans[0]![1].style)).toContain('--shiki-dracula:')
      expect(new Set(spans.map((span) => span[1].style)).size).toBeGreaterThan(1)
      expect(textContent(block)).toBe(block[1].code)
    }
    expect(textContent(blocks.at(-1)!)).toContain('<safe-fallback>')
    expect(elements(tree.nodes).some((node) => node[0] === 'safe-fallback')).toBe(false)
  })
  it('does not execute HTML or expose arbitrary Vue components and unsafe protocols', async () => {
    const tree = await parseBlogMarkdown(
      '<script>alert(1)</script>\n\n<img src=x onerror=alert(2)>\n\n::u-button\nunsafe\n::\n\n[x](javascript:alert(3))',
    )
    const nodes = elements(tree.nodes)
    expect(nodes.some((node) => ['script', 'u-button', 'img'].includes(String(node[0])))).toBe(
      false,
    )
    expect(nodes.some((node) => Object.keys(node[1]).some((key) => /^on/i.test(key)))).toBe(false)
    expect(nodes.some((node) => String(node[1].href || '').startsWith('javascript:'))).toBe(false)
  })
  it('compares format semantics while accepting whitespace and nested mark ordering', async () => {
    expect(await markdownSignature('**_嵌套_**')).toBe(await markdownSignature('_**嵌套**_'))
    expect(await markdownSignature('| A | B |\n|---|---|\n| 1 | 2 |')).toBe(
      await markdownSignature('| A | B |\n| --- | --- |\n| 1 | 2 |\n'),
    )
    expect(await markdownSignature('~~保留~~')).not.toBe(await markdownSignature('保留'))
    expect(await markdownSignature('```java\nint a = 1;\n```')).not.toBe(
      await markdownSignature('```\nint a = 1;\n```'),
    )
    expect(await markdownSignature('- a\n  - b')).not.toBe(await markdownSignature('- a\n- b'))
    expect(await markdownSignature('[x](https://example.com "标题")')).not.toBe(
      await markdownSignature('[x](https://example.com)'),
    )
    expect(await markdownSignature('```typescript [Roundtrip.ts]\nconst x = 1\n```')).not.toBe(
      await markdownSignature('```typescript\nconst x = 1\n```'),
    )
  })
})
