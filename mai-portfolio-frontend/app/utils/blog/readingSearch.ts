import { createTextHighlights, findDOMText, scrollTextRange } from '../search/domSearch'
import type { DocumentSearchAdapter } from '../search/textSearch'

export function createReadingSearch(options: {
  root: () => HTMLElement | null | undefined
  reveal: (element: Element) => Promise<void>
}): DocumentSearchAdapter {
  let matches: Range[] = []
  let revision = 0
  const highlight = createTextHighlights(options.root)
  return {
    find(query) {
      revision++
      const root = options.root()
      matches = root ? findDOMText(root, query) : []
      return matches.length
    },
    async activate(index, locate) {
      const range = matches[index]
      const current = revision
      if (locate && range?.startContainer.parentElement) {
        await options.reveal(range.startContainer.parentElement)
      }
      if (current !== revision) {
        return
      }
      highlight.paint(matches, range)
      if (locate && range) {
        scrollTextRange(range)
      }
    },
    observe(changed) {
      const root = options.root()
      if (!root) {
        return () => {}
      }
      const observer = new MutationObserver(changed)
      observer.observe(root, { childList: true, characterData: true, subtree: true })
      return () => observer.disconnect()
    },
    selectedText() {
      const selection = options.root()?.ownerDocument.getSelection()
      return selection?.anchorNode && options.root()?.contains(selection.anchorNode)
        ? selection.toString().slice(0, 200)
        : ''
    },
    clear() {
      revision++
      matches = []
      highlight.clear()
    },
  }
}
