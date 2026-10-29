import { describe, it, expect } from 'vitest'
import type { ElementNode, Node } from 'comark'
import { parseBlogMarkdown } from '../../../app/utils/markdown/parser'
import { MERMAID_LIMITS, validateMermaidSource } from '../../../app/utils/markdown/blogMermaid'
import { BLOG_MERMAID_FIXTURE } from '../../../app/constants/blogMermaidFixture'
const elements = (nodes: Node[]): ElementNode[] => nodes.flatMap(node => Array.isArray(node) ? [node as ElementNode, ...elements(node.slice(2) as Node[])] : [])

describe('official Comark Mermaid fences and safe rendering boundary', () => {
  it('routes six Mermaid fences away from Shiki without changing code/source', async () => {
    const nodes = elements((await parseBlogMarkdown(BLOG_MERMAID_FIXTURE)).nodes)
    const diagrams = nodes.filter(node => node[0] === 'mermaid')
    expect(diagrams).toHaveLength(6)
    expect(diagrams[0]![1].content).toContain('A[上传文档] --> B[Markdown解析]')
    expect(nodes.filter(node => node[0] === 'pre')).toHaveLength(1)
    expect(nodes.find(node => node[0] === 'pre')![1].code).toBe('const source = "flowchart TD; A --> B"')
  })
  it('drops author attributes and HTML while preserving fenced diagram source', async () => {
    const nodes = elements((await parseBlogMarkdown('```mermaid {onclick="alert(1)" theme="dark"}\nflowchart TD; A-->B\n```\n\n<mermaid content="attack" />')).nodes)
    const diagram = nodes.find(node => node[0] === 'mermaid')!
    expect(Object.keys(diagram[1])).toEqual(['content'])
    expect(diagram[1].content).toContain('A-->B')
    expect(nodes.filter(node => node[0] === 'mermaid')).toHaveLength(1)
  })
  it('rejects oversized diagrams, configuration override and unsafe CSS before rendering', () => {
    for (const source of ['x'.repeat(MERMAID_LIMITS.characters + 1), '%%{init: {"securityLevel":"loose"}}%%\nflowchart TD; A-->B', '---\nconfig:\n  securityLevel: loose\n---\nflowchart TD; A-->B', 'flowchart TD\nclassDef attack fill:url(https://evil.test/x)', 'flowchart TD\nstyle A fill:red}body{background:red', 'flowchart TD\nA@{img: "https://evil.test/image"}']) expect(() => validateMermaidSource(source)).toThrow()
    expect(() => validateMermaidSource('flowchart TD\nA-->B\nclassDef marked fill:#fff,stroke:#36776d')).not.toThrow()
  })
})
