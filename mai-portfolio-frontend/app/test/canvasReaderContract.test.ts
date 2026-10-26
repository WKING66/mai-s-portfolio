import { describe, expect, it } from 'vitest'
import type { ElementNode, Node } from 'comark'
import { textContent } from 'comark/utils'
import {
  importCanvasMarkdown,
  exportCanvasMarkdown,
  canvasSchema,
} from '../utils/editor/canvasMarkdown'
import { parseBlogMarkdown } from '../utils/markdown'

const elements = (nodes: Node[]): ElementNode[] =>
  nodes.flatMap((node) =>
    Array.isArray(node) ? [node as ElementNode, ...elements(node.slice(2) as Node[])] : [],
  )

describe('Showcase 与后端 Markdown 样例、公开阅读器的边界对照', () => {
  it('读取后端 DocumentPipelineTest 的方言保留样例，不丢代码、任务或普通未知语法', () => {
    // 对应 backend 66f524c 的 preservesMarkdownDialectAndWhitespaceWithoutResourceChanges。
    // 这是消费端回归样例，不冒充已经调用后端保存接口。
    const source =
      '## 原始标题 ##\r\n\r\n| A | B |\r\n|:---|---:|\r\n| ~~删除~~ | `asset://9` |\r\n\r\n' +
      '- [x] 完成\r\n- [ ] 未完成\r\n\r\n~~~typescript [demo.ts]\r\n![示例](asset://9)\r\n~~~\r\n\r\n' +
      '[普通链接](https://example.com "标题")\r\n\r\n自定义 :syntax: {属性}\r\n'
    const document = canvasSchema.nodeFromJSON(importCanvasMarkdown(source))
    const types: string[] = []
    document.descendants((node) => {
      types.push(node.type.name)
      if (node.type.name === 'codeBlock') {
        expect(node.textContent).toBe('![示例](asset://9)')
        expect(node.attrs.language).toBe('typescript [demo.ts]')
      }
    })
    expect(types).toEqual(expect.arrayContaining(['heading', 'table', 'taskList', 'codeBlock']))
    expect(document.textContent).toContain('自定义 :syntax: {属性}')
    expect(exportCanvasMarkdown(document.toJSON())).toContain('asset://9')
  })

  it('编辑器输出保留公开阅读页的 GFM 结构、资源引用和公式', async () => {
    const source =
      '| A | B |\n|:---|---:|\n| ~~旧~~ | $x^2$ |\n\n- [x] 完成\n- [ ] 待办\n\n' +
      '![图片](resource://image)\n\n[附件](resource://file)\n\n```md\nasset://9\n```'
    const output = exportCanvasMarkdown(importCanvasMarkdown(source))
    const nodes = elements((await parseBlogMarkdown(output)).nodes)
    expect(nodes.filter((node) => node[0] === 'th').map((node) => node[1].align)).toEqual([
      'left',
      'right',
    ])
    expect(nodes.filter((node) => node[0] === 'input')).toHaveLength(2)
    expect(nodes.some((node) => ['s', 'del'].includes(String(node[0])))).toBe(true)
    expect(nodes.find((node) => node[0] === 'img')?.[1].src).toBe('resource://image')
    expect(nodes.find((node) => node[0] === 'a')?.[1].href).toBe('resource://file')
    const math = nodes.find((node) => node[0] === 'blog-math')
    expect(String(math?.[1].html)).toContain('katex')
    expect(String(math?.[1].html)).toContain('x^2')
    expect(textContent(nodes.find((node) => node[0] === 'pre')!)).toBe('asset://9')
  })

  it('新 title 文件名输出必须被公开阅读页识别', async () => {
    const output = exportCanvasMarkdown(
      importCanvasMarkdown('```ts title="article.ts"\nconst x = 1\n```'),
    )
    const code = elements((await parseBlogMarkdown(output)).nodes).find(
      (node) => node[0] === 'pre',
    )!
    expect(code[1].filename).toBe('article.ts')
    expect(textContent(code)).toBe('const x = 1')
  })

  it('不支持的脚注和提示块仍保留可见文字', async () => {
    const source = '正文[^note]\n\n[^note]: 脚注说明\n\n> [!NOTE]\n> 提示正文'
    const output = exportCanvasMarkdown(importCanvasMarkdown(source))
    const nodes = (await parseBlogMarkdown(output)).nodes
    const text = nodes
      .map((node) => (typeof node === 'string' ? node : textContent(node)))
      .join('\n')
    expect(text).toContain('正文')
    expect(text).toContain('脚注说明')
    expect(text).toContain('提示正文')
  })
})
