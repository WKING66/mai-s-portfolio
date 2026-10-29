import type { Editor } from '@tiptap/core'
import type { Node as DocumentNode } from '@tiptap/pm/model'
import { closeHistory, undoNoScroll, redoNoScroll } from '@tiptap/pm/history'
import { TextSelection } from '@tiptap/pm/state'
import {
  findTextMatches,
  type TextMatch,
  type DocumentSearchAdapter,
} from '../../search/textSearch'
import { createTextHighlights, scrollTextRange } from '../../search/domSearch'
import { CANVAS_SEARCH_HISTORY } from './canvasSearchHistory'
import { canvasCodeInputEntries } from '../code/canvasCodeInput'

export interface CanvasTextMatch extends TextMatch {
  code?: number
}
/** 每个文本块独立查找；相邻文本样式可跨越，原子节点和软换行作为边界。 */
export function findCanvasText(document: DocumentNode, query: string): CanvasTextMatch[] {
  if (!query) {
    return []
  }
  const matches: CanvasTextMatch[] = []
  document.descendants((node, position) => {
    if (!node.isTextblock) {
      return !node.isAtom
    }
    const code = node.type.name === 'codeBlock'
    if (code && String(node.attrs.language || '').split(/\s/)[0] === 'mermaid') {
      return false
    }
    let text = ''
    let start = position + 1
    function flush() {
      for (const match of findTextMatches(text, query)) {
        matches.push({
          from: start + match.from,
          to: start + match.to,
          ...(code ? { code: position } : {}),
        })
      }
      text = ''
    }
    node.forEach((child, offset) => {
      if (child.isText) {
        if (!text) {
          start = position + 1 + offset
        }
        text += child.text
      } else {
        flush()
      }
    })
    flush()
    return false
  })
  return matches
}

/** 一次批量替换只提交一个原文档事务，前后都隔开历史分组；不重建 Markdown/JSON。 */
export function replaceCanvasText(
  editor: Editor,
  document: DocumentNode,
  matches: TextMatch[],
  value: string,
  all = false,
) {
  if (editor.isDestroyed || !editor.isEditable || editor.state.doc !== document) {
    throw new Error('正文已变化，请等待查找更新后重试。')
  }
  if (/[\r\n]/.test(value)) {
    throw new Error('替换内容支持空格，不支持换行。')
  }
  const changes = matches.filter((match) => document.textBetween(match.from, match.to) !== value)
  if (!changes.length) {
    return 0
  }
  const transaction = closeHistory(editor.state.tr).setMeta(CANVAS_SEARCH_HISTORY, {
    replacement: all,
  })
  for (const match of [...changes].sort((a, b) => b.from - a.from)) {
    const marks =
      document.resolve(match.from).nodeAfter?.marks ?? document.resolve(match.from).marks()
    if (value) {
      transaction.replaceWith(match.from, match.to, editor.schema.text(value, marks))
    } else {
      transaction.delete(match.from, match.to)
    }
  }
  editor.view.dispatch(transaction)
  if (editor.state.doc !== transaction.doc) {
    throw new Error('本次替换无法安全应用，正文未改变。')
  }
  editor.view.dispatch(closeHistory(editor.state.tr).setMeta('addToHistory', false))
  return changes.length
}

export function createCanvasSearch(options: {
  editor: () => Editor | null
  reveal: (position: number) => Promise<void>
  scrollRoot: () => HTMLElement | null | undefined
}): DocumentSearchAdapter {
  let matches: CanvasTextMatch[] = []
  let snapshot: DocumentNode | undefined
  let query = ''
  let revision = 0
  let lastReplacementWasAll = false
  const highlight = createTextHighlights(() => options.editor()?.view.dom)
  function paint(index: number) {
    const editor = options.editor()
    if (!editor) {
      return
    }
    const ranges: Range[] = []
    let active: Range | undefined
    const entries = canvasCodeInputEntries(editor)
    entries.forEach(({ input }) => {
      const range = input.range()
      if (range) {
        const local = matches.flatMap((match, order) =>
          match.from >= range.from && match.to <= range.to
            ? [
                {
                  from: match.from - range.from,
                  to: match.to - range.from,
                  active: order === index,
                },
              ]
            : [],
        )
        input.search(local)
      }
    })
    matches.forEach((match, order) => {
      if (
        match.code !== undefined &&
        entries.some(({ input }) => {
          const range = input.range()
          return range && match.from >= range.from && match.to <= range.to
        })
      ) {
        return
      }
      const start = editor.view.domAtPos(match.from, 1)
      const end = editor.view.domAtPos(match.to, -1)
      const range = editor.view.dom.ownerDocument.createRange()
      range.setStart(start.node, start.offset)
      range.setEnd(end.node, end.offset)
      ranges.push(range)
      if (order === index) {
        active = range
      }
    })
    highlight.paint(ranges, active)
    return active
  }
  return {
    find(value) {
      revision++
      query = value
      snapshot = options.editor()?.state.doc
      matches = snapshot ? findCanvasText(snapshot, value) : []
      return matches.length
    },
    async activate(index, locate) {
      const editor = options.editor()
      const match = matches[index]
      const current = revision
      if (!editor || editor.state.doc !== snapshot) {
        return
      }
      if (locate && match) {
        await options.reveal(match.from)
        if (current !== revision || editor.state.doc !== snapshot) {
          return
        }
        if (match.code !== undefined) {
          editor.view
            .nodeDOM(match.code)
            ?.dispatchEvent(new Event('document-search-reveal', { bubbles: true }))
          // 节点展开交由 Vue；让布局先应用，保留搜索框焦点。
          await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
        }
      }
      if (current !== revision || editor.state.doc !== snapshot) {
        return
      }
      const range = paint(index)
      if (locate && match?.code !== undefined) {
        const entry = canvasCodeInputEntries(editor).find(({ input }) => {
          const range = input.range()
          return range && match.from >= range.from && match.to <= range.to
        })
        if (entry) {
          const view = entry.host.ownerDocument.defaultView!
          const outside = { top: view.scrollY, left: view.scrollX }
          entry.input.revealSearch(match.from - entry.input.range()!.from)
          await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
          if (current !== revision || editor.state.doc !== snapshot) {
            return
          }
          // CodeMirror 可滚动自己的代码视口；外层页面位置由画布定位持有。
          view.scrollTo({ ...outside, behavior: 'instant' })
          const start = entry.input.domAtPosition(match.from)
          const end = entry.input.domAtPosition(match.to)
          if (start && end) {
            const located = entry.host.ownerDocument.createRange()
            located.setStart(start.node, start.offset)
            located.setEnd(end.node, end.offset)
            scrollTextRange(located, options.scrollRoot())
          }
        } else if (range) {
          scrollTextRange(range, options.scrollRoot())
        }
      } else if (locate && range) {
        scrollTextRange(range, options.scrollRoot())
      }
    },
    replace(index, value, all) {
      const editor = options.editor()
      if (!editor || !snapshot) {
        return { replaced: 0, next: -1, total: 0 }
      }
      const match = matches[index]
      const nextPosition = match ? match.from + value.length : 0
      if (match && editor.isEditable && editor.state.doc === snapshot) {
        // 历史记录当前命中，而不是打开查找前遗留的旧光标；Undo 不再拉回文首。
        editor.view.dispatch(
          editor.state.tr
            .setSelection(TextSelection.near(editor.state.doc.resolve(match.from)))
            .setMeta('addToHistory', false),
        )
      }
      const selected = match ? [match] : []
      const replaced = replaceCanvasText(editor, snapshot, all ? matches : selected, value, all)
      lastReplacementWasAll = all
      snapshot = editor.state.doc
      matches = findCanvasText(snapshot, query)
      revision++
      const position = all ? editor.state.selection.from : nextPosition
      const next = matches.findIndex((item) => item.from >= position)
      return { replaced, next: next < 0 ? matches.length - 1 : next, total: matches.length }
    },
    observe(changed) {
      const editor = options.editor()
      if (!editor) {
        return () => {}
      }
      const listener = ({
        transaction,
      }: {
        transaction: import('@tiptap/pm/state').Transaction
      }) => {
        if (transaction.docChanged) {
          changed()
        }
      }
      editor.on('transaction', listener)
      return () => editor.off('transaction', listener)
    },
    history(direction, value) {
      const editor = options.editor()
      if (
        !editor?.isEditable ||
        !(direction === 'undo' ? undoNoScroll : redoNoScroll)(editor.state, editor.view.dispatch)
      ) {
        return null
      }
      query = value
      snapshot = editor.state.doc
      matches = findCanvasText(snapshot, query)
      revision++
      const next = matches.findIndex((match) => match.from >= editor.state.selection.from)
      return {
        next: next < 0 ? matches.length - 1 : next,
        total: matches.length,
        // 全部替换的 Undo/Redo 留在当前视口；单处替换才定位恢复的命中。
        locate: !lastReplacementWasAll,
      }
    },
    focus(index) {
      const editor = options.editor()
      const match = matches[index]
      if (!editor || editor.isDestroyed) {
        return
      }
      if (match && editor.state.doc === snapshot) {
        editor.view.dispatch(
          editor.state.tr
            .setSelection(TextSelection.near(editor.state.doc.resolve(match.from)))
            .setMeta('addToHistory', false),
        )
      }
      editor.view.focus()
    },
    clear() {
      revision++
      matches = []
      snapshot = undefined
      highlight.clear()
      const pane = options.scrollRoot()
      pane?.scrollTo({ top: pane.scrollTop, left: pane.scrollLeft, behavior: 'instant' })
      const editor = options.editor()
      if (editor) {
        canvasCodeInputEntries(editor).forEach(({ input }) => input.search([]))
      }
    },
  }
}
