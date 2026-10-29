import { findTextMatches } from './textSearch'

interface TextPart {
  node: Text
  offset: number
}
interface TextRun {
  text: string
  parts: TextPart[]
}
const IGNORED =
  'button, input, textarea, select, img, svg, math, .blog-image-frame, .blog-math, .blog-math-fallback, .blog-mermaid, .blog-code-toolbar, [data-document-search-ignore], [aria-hidden="true"]'
const BLOCK = 'p, h1, h2, h3, h4, h5, h6, li, td, th, pre'

/** 保持正文 DOM 原样；样式节点间可匹配，跨块、换行或原子内容不拼接。 */
export function findDOMText(root: HTMLElement, query: string): Range[] {
  if (!query) {
    return []
  }
  const runs: TextRun[] = []
  let current: TextRun | undefined
  let block: Element | null = null
  function visit(node: Node) {
    if (node.nodeType === 1) {
      const element = node as Element
      // 折叠章节虽 aria-hidden 仍属于搜索正文；控件/数学辅助 DOM 则排除。
      if (element.matches(IGNORED) && !element.matches('.blog-chapter-body')) {
        current = undefined
        return
      }
      if (element.tagName === 'BR') {
        current = undefined
        return
      }
    }
    if (node.nodeType === 3) {
      const text = node as Text
      if (!text.data) {
        return
      }
      const nextBlock = text.parentElement?.closest(BLOCK) ?? root
      if (!current || nextBlock !== block) {
        current = { text: '', parts: [] }
        runs.push(current)
        block = nextBlock
      }
      current.parts.push({ node: text, offset: current.text.length })
      current.text += text.data
      return
    }
    node.childNodes.forEach(visit)
  }
  visit(root)
  return runs.flatMap((run) =>
    findTextMatches(run.text, query).map((match) => {
      const start = run.parts.findLast((part) => part.offset <= match.from)!
      const end = run.parts.findLast((part) => part.offset < match.to)!
      const range = root.ownerDocument.createRange()
      range.setStart(start.node, match.from - start.offset)
      range.setEnd(end.node, match.to - end.offset)
      return range
    }),
  )
}

const highlightOwners = new WeakMap<Document, object>()
/** 原生文本范围高亮，不包裹 span、不改 PM/Vue 管理的 DOM，也不改变浏览器选区。 */
export function createTextHighlights(root: () => HTMLElement | null | undefined) {
  const owner = {}
  let document: Document | undefined
  function clear() {
    if (document && highlightOwners.get(document) === owner) {
      document.defaultView?.CSS.highlights?.delete('document-search')
      document.defaultView?.CSS.highlights?.delete('document-search-active')
      highlightOwners.delete(document)
    }
  }
  return {
    paint(ranges: Range[], active?: Range) {
      const next = root()?.ownerDocument
      const view = next?.defaultView
      if (!next || !view?.CSS.highlights || !view.Highlight) {
        return
      }
      if (document !== next) {
        clear()
      }
      document = next
      highlightOwners.set(document, owner)
      const all = new view.Highlight()
      ranges.forEach((range) => all.add(range))
      const selected = new view.Highlight()
      if (active) {
        selected.add(active)
      }
      selected.priority = 1
      view.CSS.highlights.set('document-search', all)
      view.CSS.highlights.set('document-search-active', selected)
    },
    clear,
  }
}

/** 只测量当前命中，不为所有搜索结果强制布局。 */
export function scrollTextRange(range: Range, scrollRoot?: HTMLElement | null) {
  const element = range.startContainer.parentElement
  const rect = range.getBoundingClientRect()
  if (!rect.height || !element) {
    return
  }
  if (scrollRoot) {
    const pane = scrollRoot.getBoundingClientRect()
    scrollRoot.scrollTop += rect.top - pane.top - Math.min(pane.height / 3, 160)
  } else {
    const view = element.ownerDocument.defaultView!
    view.scrollBy({ top: rect.top - Math.min(view.innerHeight / 3, 200), behavior: 'instant' })
  }
}
