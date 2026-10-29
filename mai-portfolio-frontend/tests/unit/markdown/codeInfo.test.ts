import { describe, expect, it } from 'vitest'
import type { ElementNode, Node } from 'comark'
import { formatCodeInfo, parseCodeInfo } from '../../../app/utils/markdown/code/codeInfo'
import { parseBlogMarkdown } from '../../../app/utils/markdown/parser'

function codeBlock(nodes: Node[]): ElementNode | undefined {
  for (const node of nodes) {
    if (!Array.isArray(node)) {
      continue
    }
    if (node[0] === 'pre') {
      return node as ElementNode
    }
    const nested = codeBlock(node.slice(2) as Node[])
    if (nested) {
      return nested
    }
  }
}

describe('阅读与编辑共用代码围栏文件名约定', () => {
  it.each(['article.ts', '中文 名称.ts', 'a"b.ts', 'a\\b.ts', '[a].ts'])(
    '编辑后 title 形式在公开阅读器中保持文件名：%s',
    async (filename) => {
      const info = formatCodeInfo('typescript', filename)
      expect(parseCodeInfo(info)).toEqual({ language: 'typescript', filename })
      const block = codeBlock((await parseBlogMarkdown('```' + info + '\nconst a = 1\n```')).nodes)!
      expect(block[1].filename).toBe(filename)
      expect(block[1].code).toBe('const a = 1')
    },
  )
  it('仍读取既有方括号形式，不猜测无效 JSON title', async () => {
    expect(parseCodeInfo('ts [legacy.ts]').filename).toBe('legacy.ts')
    expect(parseCodeInfo('ts title="bad\\q.ts"').filename).toBe('')
    expect(
      codeBlock((await parseBlogMarkdown('```ts [legacy.ts]\nx\n```')).nodes)?.[1].filename,
    ).toBe('legacy.ts')
  })
})
