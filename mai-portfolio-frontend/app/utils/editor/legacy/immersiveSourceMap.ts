import type { JSONContent } from '@tiptap/core'
import { editorContentMeaning, isEditorPlaceholderParagraph } from '../markdown/markdownOutput'
import type { Node } from '@tiptap/pm/model'
import { createImmersiveMarkdownManager } from '../markdown/immersiveMarkdown'

export interface ImmersiveSourceRange {
  from: number
  to: number
  sourceFrom: number
  sourceTo: number
  contentSourceFrom?: number
  type: string
}
function meaning(node: Node): string {
  return JSON.stringify(editorContentMeaning(node.toJSON()))
}
/** 同一 lexer 的 raw token 范围对应顶层 ProseMirror 块；语义不匹配时拒绝猜测位置。 */
export interface PreparedImmersiveSourceMap {
  source: string
  document: Node
  ranges: ImmersiveSourceRange[]
}
export function buildImmersiveSourceMap(
  source: string,
  document: Node,
  manager = createImmersiveMarkdownManager(),
  parsedBlocks = new Map<string, JSONContent>(),
): ImmersiveSourceRange[] {
  const present = new Set<string>()
  const tokens = manager.instance.lexer(source)
  const ranges: ImmersiveSourceRange[] = []
  let sourceFrom = 0
  let documentFrom = 0
  let index = 0
  function skipEmptyParagraphs() {
    // 空段落可承载当前光标，但 Markdown 不持久化空白节点；保留 PM 位置，继续匹配真实来源块。
    let node = document.maybeChild(index)
    while (node && isEditorPlaceholderParagraph(node.toJSON())) {
      documentFrom += node.nodeSize
      index++
      node = document.maybeChild(index)
    }
  }
  for (const token of tokens) {
    const raw = token.raw || ''
    const sourceTo = sourceFrom + raw.length
    if (token.type !== 'space') {
      present.add(raw)
      let parsed = parsedBlocks.get(raw)
      if (!parsed) {
        parsed = manager.parse(raw)
        parsedBlocks.set(raw, parsed)
      }
      for (const child of (parsed.content ?? []).filter(
        (node) => node.type !== 'paragraph' || node.content?.length,
      )) {
        skipEmptyParagraphs()
        const actual = document.maybeChild(index)
        if (!actual || meaning(actual) !== meaning(document.type.schema.nodeFromJSON(child))) {
          throw new Error('当前块尚不能可靠映射，保留两侧原位置。')
        }
        ranges.push({
          from: documentFrom,
          to: documentFrom + actual.nodeSize,
          sourceFrom,
          sourceTo,
          type: actual.type.name,
          contentSourceFrom: token.type === 'code' ? sourceFrom + raw.indexOf('\n') + 1 : undefined,
        })
        documentFrom += actual.nodeSize
        index++
      }
    }
    sourceFrom = sourceTo
  }
  skipEmptyParagraphs()
  if (index !== document.childCount && !(source === '' && document.textContent === '')) {
    throw new Error('源码与画布尚未同步，暂停对侧定位。')
  }
  for (const raw of parsedBlocks.keys()) {
    if (!present.has(raw)) {
      parsedBlocks.delete(raw)
    }
  }
  return ranges
}
export function sourceRangeAt(ranges: ImmersiveSourceRange[], position: number, source = false) {
  return (
    ranges.find((range) =>
      source
        ? position >= range.sourceFrom && position < range.sourceTo
        : position >= range.from && position < range.to,
    ) ??
    ranges.findLast((range) => (source ? range.sourceFrom <= position : range.from <= position))
  )
}
