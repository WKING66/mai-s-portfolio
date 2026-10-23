import { describe, expect, it } from 'vitest'
import type { JSONContent } from '@tiptap/core'
import {
  exportCanvasMarkdown,
  importCanvasMarkdown,
  canvasSchema,
  createCanvasMarkdownManager,
} from '../utils/editor/canvasMarkdown'
import { editorContentMeaning } from '../utils/editor/markdownOutput'
import { parseBlogMarkdown } from '../utils/markdown'
import type { Node as ReaderNode } from 'comark'

const text = (value: string, ...marks: string[]): JSONContent => ({
  type: 'text',
  text: value,
  ...(marks.length ? { marks: marks.map((type) => ({ type })) } : {}),
})
const paragraph = (...content: JSONContent[]): JSONContent => ({ type: 'paragraph', content })
const readerStyles = (
  nodes: ReaderNode[],
  marks: string[] = [],
): { text: string; marks: string[] }[] =>
  nodes.flatMap((node) => {
    if (typeof node === 'string') {
      return Array.from(node, (text) => ({ text, marks: [...marks].sort() }))
    }
    if (!Array.isArray(node)) {
      return []
    }
    const style = { strong: 'bold', em: 'italic' }[String(node[0]) as 'strong' | 'em']
    return readerStyles(node.slice(2) as ReaderNode[], style ? [...marks, style] : marks)
  })
const cases: [string, JSONContent][] = [
  [
    '公式原子后直接输入数字仍可保存',
    paragraph({ type: 'preservedMathInline', attrs: { source: '$x$' } }, text('2026')),
  ],
  [
    '公式前后的软换行保持原样',
    paragraph(
      text('前\n'),
      { type: 'preservedMathInline', attrs: { source: '\\(x\\)' } },
      text('\n后'),
    ),
  ],
  [
    '引用中的字面编号不能变成列表',
    { type: 'blockquote', content: [paragraph(text('5. 普通文字'))] },
  ],
  [
    '斜体内嵌加粗不能丢失斜体',
    paragraph(text('前 ', 'italic'), text('重点', 'bold', 'italic'), text('后', 'italic')),
  ],
  [
    '公式两侧的嵌套格式保持一致',
    paragraph(
      text('前', 'italic'),
      { type: 'preservedMathInline', attrs: { source: '\\(x\\)' } },
      text('后 ', 'italic'),
      text('重点', 'bold', 'italic'),
      text('结束', 'italic'),
    ),
  ],
  [
    '列表续行保留实际缩进',
    {
      type: 'orderedList',
      content: [{ type: 'listItem', content: [paragraph(text('首行\n        续行'))] }],
    },
  ],
]

describe('保存边界的四类长文输出回归', () => {
  it.each(cases)('%s', (_name, block) => {
    // 实际保存入口接受 PM 文档，Schema 会补齐列表起始值并规范化 Marks 顺序。
    const document = canvasSchema.nodeFromJSON({ type: 'doc', content: [block] }).toJSON()
    const output = exportCanvasMarkdown(document)
    expect(editorContentMeaning(importCanvasMarkdown(output))).toEqual(
      editorContentMeaning(document),
    )
  })
  it('中英文连续文字的加粗/斜体相邻与交叉边界在编辑器和公开阅读器保持一致', async () => {
    const styles = [[], ['bold'], ['italic'], ['bold', 'italic']]
    const manager = createCanvasMarkdownManager()
    for (const words of [
      ['甲', '乙', '丙'],
      ['a', 'b', 'c'],
    ]) {
      for (const first of styles) {
        for (const second of styles) {
          for (const third of styles) {
            const document: JSONContent = {
              type: 'doc',
              content: [
                paragraph(
                  text('左'),
                  text(words[0]!, ...first),
                  text(words[1]!, ...second),
                  text(words[2]!, ...third),
                  text('右'),
                ),
              ],
            }
            const output = manager.serialize(document)
            expect(
              editorContentMeaning(
                canvasSchema.nodeFromJSON(importCanvasMarkdown(output)).toJSON(),
              ),
              JSON.stringify({ first, second, third, output }),
            ).toEqual(editorContentMeaning(canvasSchema.nodeFromJSON(document).toJSON()))
            expect(readerStyles((await parseBlogMarkdown(output)).nodes)).toEqual([
              { text: '左', marks: [] },
              { text: words[0], marks: [...first].sort() },
              { text: words[1], marks: [...second].sort() },
              { text: words[2], marks: [...third].sort() },
              { text: '右', marks: [] },
            ])
          }
        }
      }
    }
  })
  it.each([
    '0. **零**\n1. *一*\n\n   后续段落\n\n   - 嵌套 **文字**',
    '9. 首行\n   续行\n10. 次行\n\n    > 嵌套引用\n    > 文字',
    '- [x] 任务\n  - [ ] 子任务\n\n- [ ] 下一项',
    '> 2\\. 普通编号\n>\n> - [x] **任务**',
    '- 父项\n\n  ```ts title="a.ts"\n  const a = 1\n  ```\n\n  1. 有序子项\n  2. 有序子项二',
    '**[加粗链接](https://example.com "标题")** *`x * y`* ~~删除~~',
    '*公式前* $x^2$ ***公式后*** ![图](resource://image) [附件](resource://file)',
    '| A | B |\n|:---|---:|\n| ***样式*** | `a\\|b` |',
  ])('列表宽度、混排及复杂节点保留语义：%s', (source) => {
    const document = importCanvasMarkdown(source)
    const output = exportCanvasMarkdown(document)
    expect(editorContentMeaning(importCanvasMarkdown(output))).toEqual(
      editorContentMeaning(document),
    )
  })
})
