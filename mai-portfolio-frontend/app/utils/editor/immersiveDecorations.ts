import { Extension } from '@tiptap/core'
import {
  NodeSelection,
  Plugin,
  PluginKey,
  type EditorState,
  type Transaction,
} from '@tiptap/pm/state'
import { Decoration, DecorationSet } from '@tiptap/pm/view'
import { isHistoryTransaction } from '@tiptap/pm/history'
import { parseCodeInfo } from '../codeInfo'
import type { Node as DocumentNode } from '@tiptap/pm/model'
import type { ElementNode, Node } from 'comark'
import { highlightCodeInWorker, nextCodePaint } from '../codeHighlightClient'
import { CODE_FOLD } from '../../constants/codeThemes'

interface CodePaint {
  partial?: boolean
  style: string
  tokens: { from: number; to: number; style: string }[]
}
/** 现有 Shiki AST 转为原生文本装饰，不复制代码 DOM，不使用 HTML 或第二套高亮器。 */
export function codePaint(node: ElementNode): CodePaint {
  const tokens: CodePaint['tokens'] = []
  let offset = 0
  function visit(child: Node, style = '') {
    if (typeof child === 'string') {
      if (style && child.length) {
        tokens.push({ from: offset, to: offset + child.length, style })
      }
      offset += child.length
      return
    }
    const currentStyle = String(child[1].style || style)
    for (const nested of child.slice(2)) {
      visit(nested as Node, currentStyle)
    }
  }
  for (const child of node.slice(2)) {
    visit(child as Node)
  }
  return { style: String(node[1].style || ''), tokens }
}

/** 真选区完整覆盖复杂节点时显示主题色边界；文字选择仍由原生模型管理。 */
export function complexSelectionDecorations(state: EditorState): Decoration[] {
  if (state.selection.empty) {
    return []
  }
  const decorations: Decoration[] = []
  const { from, to } = state.selection
  state.doc.nodesBetween(from, to, (node, position) => {
    if (
      [
        'table',
        'image',
        'codeBlock',
        'preservedMathBlock',
        'preservedMathInline',
        'preservedMarkdownBlock',
      ].includes(node.type.name) &&
      from <= position &&
      to >= position + node.nodeSize
    ) {
      decorations.push(
        Decoration.node(
          position,
          position + node.nodeSize,
          { class: 'immersive-selected-block' },
          { immersiveComplexSelection: true },
        ),
      )
    }
  })
  return decorations
}

export const IMMERSIVE_HIGHLIGHT_READY = 'immersive-highlight-ready'
export const IMMERSIVE_CODE_EXPANDED = 'immersive-code-expanded'
export const ImmersiveDecorations = Extension.create({
  name: 'immersiveDecorations',
  priority: 1000,
  addOptions() {
    return { ready: () => true }
  },
  addKeyboardShortcuts() {
    const enterMath = (direction: -1 | 1) => {
      const { state } = this.editor
      if (!state.selection.empty) {
        return false
      }
      let position = state.selection.from
      let resolved = state.doc.resolve(position)
      let candidate = direction === 1 ? resolved.nodeAfter : resolved.nodeBefore
      // 段落边界同样允许进入相邻的块公式，不要求先用鼠标选中原子节点。
      if (!candidate && resolved.depth > 0 && resolved.parent.isTextblock) {
        const atEdge =
          direction === 1
            ? resolved.parentOffset === resolved.parent.content.size
            : resolved.parentOffset === 0
        if (atEdge) {
          position = direction === 1 ? resolved.after() : resolved.before()
          resolved = state.doc.resolve(position)
          candidate = direction === 1 ? resolved.nodeAfter : resolved.nodeBefore
        }
      }
      if (!candidate?.type.name.startsWith('preservedMath')) {
        return false
      }
      const start = direction === 1 ? position : position - candidate.nodeSize
      this.editor.view.dispatch(state.tr.setSelection(NodeSelection.create(state.doc, start)))
      return true
    }
    return { ArrowRight: () => enterMath(1), ArrowLeft: () => enterMath(-1) }
  },
  addProseMirrorPlugins() {
    const readyForHighlight = this.options.ready
    const key = new PluginKey<DecorationSet>('immersive-paint')
    const cache = new WeakMap<DocumentNode, CodePaint>()
    const completed = new WeakSet<DocumentNode>()
    const expandedCodes = new WeakSet<DocumentNode>()
    let palette = ''
    const nodeIdentities = new WeakMap<DocumentNode, string>()
    // 删除会丢掉映射位置；仅历史恢复时使用唯一文件/语言身份，普通新建块不继承它。
    const historicalSources = new Map<string, string | null>()
    let identities = new Map<number, string>()
    let identity = 0
    let codeNodes = new Map<number, DocumentNode>()
    let codeRevision = 0
    function sourceKey(node: DocumentNode) {
      const info = String(node.attrs.language || '')
      const filename = parseCodeInfo(info).filename
      return filename ? 'file:' + filename : 'language:' + info
    }
    function preserveSourceIdentities(before: DocumentNode, after: DocumentNode) {
      const known = new Map<string, string | null>()
      const counts = new Map<string, number>()
      before.descendants((node, position) => {
        if (node.type.name === 'codeBlock') {
          const key = sourceKey(node)
          known.set(
            key,
            known.has(key) ? null : nodeIdentities.get(node) || identities.get(position) || null,
          )
          return false
        }
        return true
      })
      after.descendants((node) => {
        if (node.type.name === 'codeBlock') {
          const key = sourceKey(node)
          counts.set(key, (counts.get(key) || 0) + 1)
          return false
        }
        return true
      })
      after.descendants((node) => {
        if (node.type.name !== 'codeBlock') {
          return true
        }
        const key = sourceKey(node)
        const id = known.get(key)
        // 源码同时增删前文和修改代码时，宽范围替换不能靠位置保留身份；只恢复唯一可识别的块。
        if (id && counts.get(key) === 1) {
          nodeIdentities.set(node, id)
        }
        return false
      })
    }
    function paintCode(
      state: EditorState,
      node: DocumentNode,
      position: number,
      previous?: DecorationSet,
      restoring = false,
      expandedIds?: ReadonlySet<string>,
    ) {
      const decorations: Decoration[] = []
      const result = cache.get(node)
      const key = sourceKey(node)
      const id =
        nodeIdentities.get(node) ||
        (restoring ? historicalSources.get(key) : undefined) ||
        identities.get(position) ||
        'code-' + ++identity
      identities.set(position, id)
      nodeIdentities.set(node, id)
      if (expandedIds?.has(id)) {
        expandedCodes.add(node)
      }
      decorations.push(
        Decoration.node(
          position,
          position + node.nodeSize,
          { style: result?.style || palette },
          { immersiveBlockId: id, immersivePaletteStyle: result?.style || palette },
        ),
      )
      const inside =
        (state.selection.from > position && state.selection.from < position + node.nodeSize) ||
        (state.selection.to > position && state.selection.to < position + node.nodeSize)
      if (result) {
        const source = node.textContent
        const previewOnly =
          !expandedCodes.has(node) && !inside && source.split('\n').length > CODE_FOLD.threshold
        let end = node.content.size
        if (previewOnly) {
          end = 0
          for (let line = 0; line < CODE_FOLD.previewLines; line++) {
            const next = source.indexOf('\n', end)
            end = next < 0 ? source.length : next + 1
          }
        }
        for (const token of result.tokens) {
          if (token.from < end && token.to <= node.content.size) {
            decorations.push(
              Decoration.inline(
                position + 1 + token.from,
                position + 1 + Math.min(token.to, end),
                { style: token.style },
                {
                  immersiveToken: true,
                  immersiveTokenStyle: token.style,
                },
              ),
            )
          }
        }
        if (result.partial && !previewOnly && previous) {
          const paintedTo = position + 1 + (result.tokens.at(-1)?.to || 0)
          // 未处理到的旧颜色保持原位；分批补色不先删除整个尾部再下一帧重建。
          decorations.push(
            ...previous
              .find(paintedTo, position + node.nodeSize - 1, (spec) => Boolean(spec.immersiveToken))
              .filter((item) => item.from >= paintedTo),
          )
        }
      } else if (previous) {
        decorations.push(
          ...previous.find(position + 1, position + node.nodeSize - 1, (spec) =>
            Boolean(spec.immersiveToken),
          ),
        )
      }
      if (inside) {
        decorations.push(
          Decoration.node(
            position,
            position + node.nodeSize,
            { 'data-code-selection-active': 'true' },
            { immersiveCaret: true },
          ),
        )
      }
      return decorations
    }
    function paint(state: EditorState, previous?: DecorationSet, restoring = false) {
      const decorations = complexSelectionDecorations(state)
      const sources = new Map<string, string | null>()
      const expandedIds = new Set<string>()
      for (const node of codeNodes.values()) {
        const id = nodeIdentities.get(node)
        if (id && expandedCodes.has(node)) {
          expandedIds.add(id)
        }
      }
      codeRevision++
      codeNodes = new Map()
      state.doc.descendants((node, position) => {
        if (node.type.name !== 'codeBlock') {
          return true
        }
        codeNodes.set(position, node)
        decorations.push(...paintCode(state, node, position, previous, restoring, expandedIds))
        const key = sourceKey(node)
        sources.set(key, sources.has(key) ? null : nodeIdentities.get(node)!)
        return false
      })
      sources.forEach((id, key) => historicalSources.set(key, id))
      return DecorationSet.create(state.doc, decorations)
    }
    // 普通输入只映射已有装饰、更新发生变化的代码；其他块的 token 规则和 DOM 不重建。
    function replaceCodes(previous: DecorationSet, state: EditorState, positions: Set<number>) {
      let next = previous
      for (const position of positions) {
        const node = codeNodes.get(position)
        if (!node) {
          continue
        }
        const previousDecorations = next
          .find(position, position + node.nodeSize, (spec) =>
            Boolean(spec.immersiveBlockId || spec.immersiveToken || spec.immersiveCaret),
          )
          .filter((item) => item.from >= position && item.to <= position + node.nodeSize)
        const desired = paintCode(state, node, position, previous)
        const key = (item: Decoration) => {
          let style = 'caret'
          if (item.spec.immersiveToken) {
            style = 'token:' + item.spec.immersiveTokenStyle
          } else if (!item.spec.immersiveCaret) {
            style = 'block:' + item.spec.immersiveBlockId + ':' + item.spec.immersivePaletteStyle
          }
          return item.from + ':' + item.to + ':' + style
        }
        const existing = new Set(previousDecorations.map(key))
        const wanted = new Set(desired.map(key))
        // PM remove 会逐项比较；只移除实际变化的颜色，不每批删除数百个相同装饰。
        next = next.remove(previousDecorations.filter((item) => !wanted.has(key(item)))).add(
          state.doc,
          desired.filter((item) => !existing.has(key(item))),
        )
      }
      return next
    }
    function selectionCodes(state: EditorState) {
      const positions = new Set<number>()
      for (const resolved of [state.selection.$from, state.selection.$to]) {
        if (resolved.parent.type.name === 'codeBlock') {
          positions.add(resolved.before())
        }
      }
      return positions
    }
    function refreshSelection(
      previous: DecorationSet,
      old: EditorState,
      state: EditorState,
      transaction: Transaction,
    ) {
      if (old.selection.eq(state.selection) && !transaction.docChanged) {
        return previous
      }
      const from = transaction.mapping.map(old.selection.from, -1)
      const to = transaction.mapping.map(old.selection.to, 1)
      return previous
        .remove(previous.find(from, to, (spec) => Boolean(spec.immersiveComplexSelection)))
        .add(state.doc, complexSelectionDecorations(state))
    }
    function mapCodeNodes(transaction: Transaction) {
      const mapped = new Map<number, DocumentNode>()
      const restored = new Set<number>()
      for (const [position, node] of codeNodes) {
        const nextPosition = transaction.mapping.map(position, -1)
        const next = transaction.doc.nodeAt(nextPosition)
        if (next?.type.name !== 'codeBlock') {
          codeRevision++
          continue
        }
        if (node !== next) {
          codeRevision++
        }
        if (expandedCodes.has(node)) {
          expandedCodes.add(next)
        }
        const id = nodeIdentities.get(node)
        if (id) {
          nodeIdentities.set(next, id)
        }
        if (node !== next && cache.has(next)) {
          restored.add(nextPosition)
        }
        mapped.set(nextPosition, next)
      }
      codeNodes = mapped
      return restored
    }
    return [
      new Plugin({
        key,
        state: {
          init: (_, state) => paint(state),
          apply: (transaction, previous, old, state) => {
            const expanded = transaction.getMeta(IMMERSIVE_CODE_EXPANDED)
            if (expanded) {
              const node = codeNodes.get(expanded.position)
              if (node) {
                if (expanded.value) {
                  expandedCodes.add(node)
                } else {
                  expandedCodes.delete(node)
                }
              }
            }
            if (transaction.getMeta('immersive-source')) {
              preserveSourceIdentities(old.doc, state.doc)
            }
            if (transaction.docChanged) {
              identities = new Map(
                [...identities].map(([position, id]) => [
                  transaction.mapping.map(position, -1),
                  id,
                ]),
              )
              if (transaction.getMeta('immersive-plain-text')) {
                const restored = mapCodeNodes(transaction)
                const mapped = previous.map(transaction.mapping, state.doc)
                const before = new Set(
                  [...selectionCodes(old)].map((position) => transaction.mapping.map(position, -1)),
                )
                const after = selectionCodes(state)
                const changed = new Set(
                  [...before, ...after].filter(
                    (position) => before.has(position) !== after.has(position),
                  ),
                )
                for (const position of restored) {
                  changed.add(position)
                }
                // 同一代码块内输入只映射现有颜色，停笔后的后台结果再替换这个块的 token。
                return refreshSelection(
                  replaceCodes(mapped, state, changed),
                  old,
                  state,
                  transaction,
                )
              }
              return paint(
                state,
                previous.map(transaction.mapping, state.doc),
                isHistoryTransaction(transaction),
              )
            }
            if (expanded) {
              return replaceCodes(previous, state, new Set([expanded.position]))
            }
            const painted = transaction.getMeta(key)
            if (painted) {
              // 后台结果携带原节点身份；只替换仍存在的目标块，迟到结果不能重绘其他代码。
              const position = [...codeNodes].find(([, node]) => node === painted)?.[0]
              return position === undefined
                ? previous
                : replaceCodes(previous, state, new Set([position]))
            }
            if (transaction.selectionSet) {
              const before = selectionCodes(old)
              const after = selectionCodes(state)
              const changed = new Set(
                [...before, ...after].filter(
                  (position) => before.has(position) !== after.has(position),
                ),
              )
              return refreshSelection(
                replaceCodes(previous, state, changed),
                old,
                state,
                transaction,
              )
            }
            return previous
          },
        },
        props: { decorations: (state) => key.getState(state) },
        view(view) {
          let disposed = false
          let wasReady = readyForHighlight()
          let scheduledRevision = codeRevision
          const jobs = new Map<
            DocumentNode,
            { controller: AbortController; timer?: ReturnType<typeof setTimeout> }
          >()
          async function highlight(node: DocumentNode, signal: AbortSignal) {
            const language = String(node.attrs.language || '').split(/\s/)[0]!
            const result = codePaint(
              await highlightCodeInWorker(node.textContent, language, signal),
            )
            for (let count = 500; ; count += 500) {
              await nextCodePaint()
              if (disposed || signal.aborted) {
                return
              }
              cache.set(node, {
                style: result.style,
                tokens: result.tokens.slice(0, count),
                partial: count < result.tokens.length,
              })
              palette = result.style
              view.dispatch(view.state.tr.setMeta(key, node))
              if (count >= result.tokens.length) {
                break
              }
            }
            completed.add(node)
          }
          function schedule() {
            if (!readyForHighlight()) {
              return
            }
            // 索引由事务映射维护；调度不再遍历整篇正文，各代码块独立取消和防抖。
            const live = new Set(codeNodes.values())
            for (const [node, job] of jobs) {
              if (!live.has(node)) {
                job.controller.abort()
                clearTimeout(job.timer)
                jobs.delete(node)
              }
            }
            for (const node of live) {
              if (
                completed.has(node) ||
                jobs.has(node) ||
                String(node.attrs.language || '').startsWith('mermaid')
              ) {
                continue
              }
              const controller = new AbortController()
              const job = {
                controller,
                timer: setTimeout(() => {
                  void highlight(node, controller.signal).catch(() => {
                    /* 高亮不可用仍保留可编辑的完整代码；其他块任务独立继续。 */
                  })
                }, 90),
              }
              jobs.set(node, job)
            }
          }
          schedule()
          return {
            update: () => {
              const ready = readyForHighlight()
              const becameReady = ready && !wasReady
              wasReady = ready
              if (codeRevision !== scheduledRevision || becameReady) {
                scheduledRevision = codeRevision
                schedule()
              }
            },
            destroy: () => {
              disposed = true
              for (const job of jobs.values()) {
                job.controller.abort()
                clearTimeout(job.timer)
              }
              jobs.clear()
            },
          }
        },
      }),
    ]
  },
})
