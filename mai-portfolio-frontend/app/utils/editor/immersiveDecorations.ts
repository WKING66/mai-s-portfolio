import { Extension } from '@tiptap/core'
import { NodeSelection, Plugin, PluginKey, type EditorState } from '@tiptap/pm/state'
import { Decoration, DecorationSet } from '@tiptap/pm/view'
import { isHistoryTransaction } from '@tiptap/pm/history'
import { parseCodeInfo } from '../codeInfo'
import type { Node as DocumentNode } from '@tiptap/pm/model'
import type { ElementNode, Node } from 'comark'
import { highlightCodeInWorker, nextCodePaint } from '../codeHighlightClient'
import { CODE_FOLD } from '../../constants/codeThemes'

interface CodePaint {
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
        Decoration.node(position, position + node.nodeSize, { class: 'immersive-selected-block' }),
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
    function paint(state: EditorState, previous?: DecorationSet, restoring = false) {
      const decorations = complexSelectionDecorations(state)
      const sources = new Map<string, string | null>()
      state.doc.descendants((node, position) => {
        if (node.type.name !== 'codeBlock') {
          return true
        }
        const result = cache.get(node)
        // 原节点随撤销重新出现时恢复会话身份；单纯映射位置无法识别先删除再撤销的节点。
        const key = sourceKey(node)
        const id =
          nodeIdentities.get(node) ||
          (restoring ? historicalSources.get(key) : undefined) ||
          identities.get(position) ||
          'code-' + ++identity
        sources.set(key, sources.has(key) ? null : id)
        identities.set(position, id)
        nodeIdentities.set(node, id)
        // 文本节点变更会使分词缓存失效，但调色板不能随它消失，避免背景闪回站点默认色。
        decorations.push(
          Decoration.node(
            position,
            position + node.nodeSize,
            {
              style: result?.style || palette,
            },
            { immersiveBlockId: identities.get(position) },
          ),
        )
        if (result) {
          const source = node.textContent
          const previewOnly =
            !expandedCodes.has(node) &&
            !(state.selection.from > position && state.selection.from < position + node.nodeSize) &&
            !(state.selection.to > position && state.selection.to < position + node.nodeSize) &&
            source.split('\n').length > CODE_FOLD.threshold
          let end = node.content.size
          if (previewOnly) {
            end = 0
            for (let line = 0; line < CODE_FOLD.previewLines; line++) {
              const next = source.indexOf('\n', end)
              end = next < 0 ? source.length : next + 1
            }
          }
          // 收起区的隐藏尾部保留同一份原生文本，展开/光标进入后才补它的颜色装饰。
          for (const token of result.tokens) {
            if (token.from < end && token.to <= node.content.size) {
              decorations.push(
                Decoration.inline(
                  position + 1 + token.from,
                  position + 1 + Math.min(token.to, end),
                  {
                    style: token.style,
                  },
                  { immersiveToken: true },
                ),
              )
            }
          }
        }
        if (!result && previous) {
          // 新分词尚未返回时映射旧 token；不把整块退回纯色，再突然全部着色。
          decorations.push(
            ...previous.find(position + 1, position + node.nodeSize - 1, (spec) =>
              Boolean(spec.immersiveToken),
            ),
          )
        }
        if (
          (state.selection.from > position && state.selection.from < position + node.nodeSize) ||
          (state.selection.to > position && state.selection.to < position + node.nodeSize)
        ) {
          decorations.push(
            Decoration.node(
              position,
              position + node.nodeSize,
              { 'data-code-selection-active': 'true' },
              { immersiveCaret: true },
            ),
          )
        }
        return false
      })
      sources.forEach((id, key) => historicalSources.set(key, id))
      return DecorationSet.create(state.doc, decorations)
    }
    return [
      new Plugin({
        key,
        state: {
          init: (_, state) => paint(state),
          apply: (transaction, previous, old, state) => {
            const expanded = transaction.getMeta(IMMERSIVE_CODE_EXPANDED)
            if (expanded) {
              const node = state.doc.nodeAt(expanded.position)
              if (node?.type.name === 'codeBlock') {
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
            }
            if (
              transaction.getMeta('immersive-plain-text') &&
              old.selection.empty &&
              state.selection.empty &&
              old.selection.$from.parent.type.name !== 'codeBlock' &&
              state.selection.$from.parent.type.name !== 'codeBlock'
            ) {
              // 普通正文输入只映射既有代码装饰，不重建整篇代码的高亮 token。
              return previous.map(transaction.mapping, state.doc)
            }
            return transaction.docChanged ||
              transaction.selectionSet ||
              transaction.getMeta(key) ||
              expanded
              ? paint(
                  state,
                  transaction.docChanged ? previous.map(transaction.mapping, state.doc) : previous,
                  isHistoryTransaction(transaction),
                )
              : previous
          },
        },
        props: { decorations: (state) => key.getState(state) },
        view(view) {
          let timer: ReturnType<typeof setTimeout> | undefined
          let disposed = false
          let revision = 0
          let controller: AbortController | undefined
          let activeNodes: DocumentNode[] = []
          let wasReady = readyForHighlight()
          function pendingNodes() {
            const pending: DocumentNode[] = []
            view.state.doc.descendants((node) => {
              if (
                node.type.name === 'codeBlock' &&
                !completed.has(node) &&
                !String(node.attrs.language || '').startsWith('mermaid')
              ) {
                pending.push(node)
              }
              return !node.isTextblock
            })
            return pending
          }
          async function highlight() {
            const current = ++revision
            controller = new AbortController()
            const signal = controller.signal
            const pending = activeNodes
            if (!pending.length) {
              return
            }
            for (const node of pending) {
              if (disposed || current !== revision) {
                return
              }
              const language = String(node.attrs.language || '').split(/\s/)[0]!
              const result = codePaint(
                await highlightCodeInWorker(node.textContent, language, signal),
              )
              // 逐批补充装饰。节点身份不变，不改正文/历史，不等待整篇分词完才统一显示。
              for (let count = 500; ; count += 500) {
                await nextCodePaint()
                if (disposed || current !== revision) {
                  return
                }
                cache.set(node, { style: result.style, tokens: result.tokens.slice(0, count) })
                palette = result.style
                view.dispatch(view.state.tr.setMeta(key, true))
                if (count >= result.tokens.length) {
                  break
                }
              }
              completed.add(node)
            }
          }
          function schedule() {
            if (!readyForHighlight()) {
              return
            }
            const pending = pendingNodes()
            const active = activeNodes.filter((node) => !completed.has(node))
            // 普通正文输入不取消正在补色的代码。只在代码节点本身改变/增删时丢弃旧任务。
            if (
              pending.length === active.length &&
              pending.every((node, index) => node === active[index])
            ) {
              return
            }
            activeNodes = pending
            revision++
            controller?.abort()
            clearTimeout(timer)
            timer = setTimeout(() => {
              void highlight().catch(() => {
                /* 高亮不可用仍保留可编辑的完整代码。 */
              })
            }, 90)
          }
          schedule()
          return {
            update: (current, previous) => {
              const ready = readyForHighlight()
              const becameReady = ready && !wasReady
              wasReady = ready
              if (current.state.doc !== previous.doc || becameReady) {
                schedule()
              }
            },
            destroy: () => {
              disposed = true
              revision++
              controller?.abort()
              clearTimeout(timer)
            },
          }
        },
      }),
    ]
  },
})
