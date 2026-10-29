import { Extension } from '@tiptap/core'
import { Plugin, PluginKey } from '@tiptap/pm/state'
import { isHistoryTransaction, undoDepth, redoDepth } from '@tiptap/pm/history'
import { scrollTextRect } from '../../search/domSearch'

type SearchScroll = 'keep' | 'locate'
interface SearchHistoryState {
  entries: ReadonlyMap<number, SearchScroll>
  scroll?: SearchScroll
}
interface SearchHistoryMeta {
  replacement?: boolean
  depth?: number
  shift?: number
  scroll?: SearchScroll
}
export const CANVAS_SEARCH_HISTORY = new PluginKey<SearchHistoryState>('canvasSearchHistory')

/** 仅给搜索替换历史附带视口策略；内容和历史分组仍由 PM history 唯一维护。 */
export const CanvasSearchHistory = Extension.create<{
  scrollRoot: () => HTMLElement | null | undefined
}>({
  name: 'canvasSearchHistory',
  addOptions: () => ({ scrollRoot: () => null }),
  addProseMirrorPlugins() {
    const root = this.options.scrollRoot
    return [
      new Plugin<SearchHistoryState>({
        key: CANVAS_SEARCH_HISTORY,
        state: {
          init: () => ({ entries: new Map() }),
          apply(transaction, state, oldState) {
            const meta = transaction.getMeta(CANVAS_SEARCH_HISTORY) as SearchHistoryMeta | undefined
            let entries = state.entries
            const shift = meta?.shift ?? 0
            if (shift) {
              entries = new Map(
                [...entries]
                  .map(([index, all]) => [index + shift, all] as const)
                  .filter(([index]) => index > 0),
              )
            }
            if (meta?.replacement !== undefined && meta.depth !== undefined) {
              entries = new Map(entries).set(meta.depth, meta.replacement ? 'keep' : 'locate')
            } else if (
              transaction.docChanged &&
              !isHistoryTransaction(transaction) &&
              transaction.getMeta('addToHistory') !== false &&
              redoDepth(oldState)
            ) {
              // 撤销后开始新输入，旧的 redo 分支随 PM history 一起失效。
              const depth = undoDepth(oldState)
              entries = new Map([...entries].filter(([index]) => index <= depth))
            }
            return { entries, scroll: meta?.scroll }
          },
        },
        appendTransaction(transactions, oldState, newState) {
          const before = undoDepth(oldState)
          const after = undoDepth(newState)
          const replacement = transactions
            .map(
              (transaction) =>
                transaction.getMeta(CANVAS_SEARCH_HISTORY) as SearchHistoryMeta | undefined,
            )
            .find((meta) => meta?.replacement !== undefined && meta.depth === undefined)
          if (replacement) {
            return newState.tr
              .setMeta(CANVAS_SEARCH_HISTORY, {
                replacement: replacement.replacement,
                depth: after,
                shift: after - before - 1,
              })
              .setMeta('addToHistory', false)
          }
          if (transactions.some(isHistoryTransaction)) {
            const index = after < before ? before : after
            const policy = CANVAS_SEARCH_HISTORY.getState(newState)?.entries.get(index)
            return policy === undefined
              ? null
              : newState.tr
                  .setMeta(CANVAS_SEARCH_HISTORY, { scroll: policy })
                  .setMeta('addToHistory', false)
          }
          // history 会按容量裁剪最早分组；滚动标记跟随其实际深度平移。
          return after < before && transactions.some((transaction) => transaction.docChanged)
            ? newState.tr
                .setMeta(CANVAS_SEARCH_HISTORY, { shift: after - before - 1 })
                .setMeta('addToHistory', false)
            : null
        },
        props: {
          handleScrollToSelection(view) {
            const policy = CANVAS_SEARCH_HISTORY.getState(view.state)?.scroll
            if (policy === undefined) {
              return false
            }
            if (policy === 'locate') {
              const rect = view.coordsAtPos(view.state.selection.head)
              scrollTextRect({ ...rect, height: rect.bottom - rect.top }, view.dom, root())
            }
            return true
          },
        },
      }),
    ]
  },
})
