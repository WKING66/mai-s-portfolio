import {
  Compartment,
  EditorState,
  StateEffect,
  StateField,
  type ChangeSet,
} from '@codemirror/state'
import {
  Decoration,
  EditorView,
  drawSelection,
  keymap,
  lineNumbers,
  type DecorationSet,
} from '@codemirror/view'
import { defaultKeymap, indentWithTab } from '@codemirror/commands'
import type { Editor, NodeViewProps } from '@tiptap/core'
import type { Node } from '@tiptap/pm/model'
import { Selection, TextSelection } from '@tiptap/pm/state'
import { undo, redo } from '@tiptap/pm/history'
import { exitCode } from '@tiptap/pm/commands'
import { highlightCodeInWorker } from '../codeHighlightClient'
import { codePaint } from './immersiveDecorations'
import type { CanvasCodeInput } from './canvasCodeInput'

/** 代码的局部 ChangeSet 直接转换为原文章事务；不导出、替换整篇文档。 */
export function codeMirrorTransaction(
  editor: Editor,
  position: number,
  changes: ChangeSet,
  anchor: number,
  head: number,
) {
  const node = editor.state.doc.nodeAt(position)
  if (
    (!changes.empty && !editor.isEditable) ||
    node?.type.name !== 'codeBlock' ||
    node.content.size !== changes.length
  ) {
    return null
  }
  const tr = editor.state.tr
  let offset = position + 1
  changes.iterChanges((from, to, nextFrom, nextTo, text) => {
    if (text.length) {
      tr.replaceWith(offset + from, offset + to, editor.schema.text(text.toString()))
    } else {
      tr.delete(offset + from, offset + to)
    }
    offset += nextTo - nextFrom - (to - from)
  })
  const base = position + 1
  tr.setSelection(TextSelection.create(tr.doc, base + anchor, base + head))
  return tr
}

const colors = StateEffect.define<DecorationSet>()
const colorField = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update(value, transaction) {
    value = value.map(transaction.changes)
    for (const effect of transaction.effects) {
      if (effect.is(colors)) {
        value = effect.value
      }
    }
    return value
  },
  provide: (field) => EditorView.decorations.from(field),
})

/** CodeMirror 只拥有当前块的输入 DOM；正文事实与 Undo/Redo 仍由外层 PM 持有。 */
export function mountCanvasCodeMirror(host: HTMLElement, props: NodeViewProps): CanvasCodeInput {
  const editor = props.editor
  const editable = new Compartment()
  let node = props.node
  let synchronizing = false
  let destroyed = false
  let timer: ReturnType<typeof setTimeout> | undefined
  let highlight: AbortController | undefined
  let cancelPaint = () => {}
  let appliedPalette = ''
  function position() {
    return props.getPos()
  }
  function outside(direction: -1 | 1, unit: 'char' | 'line', extend = false) {
    const main = input.state.selection.main
    const edge =
      unit === 'line' ? input.state.doc.lineAt(main.head) : { from: main.head, to: main.head }
    if (
      (!extend && !main.empty) ||
      (direction < 0 ? edge.from > 0 : edge.to < input.state.doc.length)
    ) {
      return false
    }
    const start = position()
    if (typeof start !== 'number') {
      return false
    }
    const boundary = start + (direction < 0 ? 0 : node.nodeSize)
    const next = Selection.near(editor.state.doc.resolve(boundary), direction)
    const selection = extend
      ? TextSelection.create(editor.state.doc, start + 1 + main.anchor, next.head)
      : next
    editor.view.dispatch(editor.state.tr.setSelection(selection).scrollIntoView())
    editor.view.focus()
    return true
  }
  function runHistory(action: typeof undo) {
    if (!editor.isEditable) {
      return false
    }
    const handled = action(editor.state, editor.view.dispatch)
    if (handled) {
      const start = host.isConnected ? position() : undefined
      const selection = editor.state.selection
      if (
        typeof start !== 'number' ||
        selection.from < start + 1 ||
        selection.to > start + 1 + node.content.size
      ) {
        editor.view.focus()
      }
    }
    return handled
  }
  const input = new EditorView({
    parent: host,
    state: EditorState.create({
      doc: node.textContent,
      extensions: [
        editable.of([
          EditorView.editable.of(editor.isEditable),
          EditorState.readOnly.of(!editor.isEditable),
        ]),
        EditorState.allowMultipleSelections.of(false),
        EditorView.contentAttributes.of({ 'aria-label': '代码正文', spellcheck: 'false' }),
        lineNumbers(),
        drawSelection(),
        colorField,
        EditorView.theme({
          '&': {
            fontSize: '0.875rem',
            backgroundColor: 'transparent',
            color: 'var(--code-fg, inherit)',
          },
          '.cm-content': {
            fontFamily: 'var(--font-mono)',
            padding: '1rem 0',
            caretColor: 'currentColor',
          },
          '.cm-line': { padding: '0 1rem' },
          '.cm-gutters': {
            backgroundColor: 'transparent',
            borderRight: 'none',
            color: 'var(--ui-text-muted)',
          },
          '.cm-scroller': {
            fontFamily: 'var(--font-mono)',
            overflow: 'auto',
            lineHeight: '1.5rem',
          },
          '&.cm-focused': { outline: 'none' },
          '.cm-cursor': { borderLeftColor: 'currentColor' },
          '.cm-selectionBackground': {
            backgroundColor: 'color-mix(in srgb, var(--ui-primary) 20%, transparent) !important',
          },
        }),
        keymap.of([
          {
            key: 'Mod-z',
            run: () => runHistory(undo),
          },
          {
            key: 'Mod-Shift-z',
            run: () => runHistory(redo),
          },
          {
            key: 'Mod-y',
            run: () => runHistory(redo),
          },
          { key: 'ArrowUp', run: () => outside(-1, 'line') },
          { key: 'ArrowDown', run: () => outside(1, 'line') },
          { key: 'ArrowLeft', run: () => outside(-1, 'char') },
          { key: 'ArrowRight', run: () => outside(1, 'char') },
          { key: 'Shift-ArrowUp', run: () => outside(-1, 'line', true) },
          { key: 'Shift-ArrowDown', run: () => outside(1, 'line', true) },
          { key: 'Shift-ArrowLeft', run: () => outside(-1, 'char', true) },
          { key: 'Shift-ArrowRight', run: () => outside(1, 'char', true) },
          {
            key: 'Mod-Enter',
            run: () => {
              if (!editor.isEditable) {
                return false
              }
              const done = exitCode(editor.state, editor.view.dispatch)
              if (done) {
                editor.view.focus()
              }
              return done
            },
          },
          indentWithTab,
          ...defaultKeymap,
        ]),
        EditorView.domEventHandlers({
          dragover(event) {
            return Boolean(editor.view.dragging || event.dataTransfer?.types.includes('Files'))
          },
          drop(event) {
            if (!editor.isEditable) {
              return true
            }
            // 整块拖拽与文件仍由文章编辑器接收；不能在代码内降成纯文本插入。
            if (editor.view.dragging || event.dataTransfer?.files.length) {
              editor.view.dispatchEvent(event)
              return true
            }
            return false
          },
          copy(event) {
            return copyOuterSelection(event)
          },
          cut(event) {
            if (!copyOuterSelection(event)) {
              return false
            }
            if (editor.isEditable) {
              editor.view.dispatch(editor.state.tr.deleteSelection().scrollIntoView())
              editor.view.focus()
            }
            return true
          },
          paste(event) {
            if (editor.isEditable && event.clipboardData?.files.length) {
              editor.view.dispatchEvent(event)
              return true
            }
            if (!outerSelection() || !event.clipboardData || !editor.isEditable) {
              return false
            }
            event.preventDefault()
            const html = event.clipboardData.getData('text/html')
            if (html) {
              editor.view.pasteHTML(html, event)
            } else {
              editor.view.pasteText(event.clipboardData.getData('text/plain'), event)
            }
            editor.view.focus()
            return true
          },
        }),
        EditorView.updateListener.of((update) => {
          // 复用 CodeMirror 的可见区通知：未显示的代码不启动语法高亮，进入视口后再补色。
          if (
            !update.docChanged &&
            !highlight &&
            (update.viewportChanged || update.geometryChanged) &&
            input.inView
          ) {
            scheduleHighlight()
          }
          if (
            synchronizing ||
            (!update.docChanged && !update.selectionSet && !update.focusChanged) ||
            !input.hasFocus
          ) {
            return
          }
          if (!update.docChanged && outerSelection()) {
            return
          }
          const start = position()
          if (typeof start !== 'number') {
            return
          }
          const main = update.state.selection.main
          const selection = editor.state.selection
          if (
            !update.docChanged &&
            selection.anchor === start + 1 + main.anchor &&
            selection.head === start + 1 + main.head
          ) {
            return
          }
          const tr = codeMirrorTransaction(editor, start, update.changes, main.anchor, main.head)
          if (!tr) {
            reconcileDocument()
            return
          }
          synchronizing = true
          try {
            editor.view.dispatch(tr)
          } finally {
            synchronizing = false
          }
          if (editor.state.doc !== tr.doc) {
            reconcileDocument()
          }
          if (update.docChanged) {
            scheduleHighlight()
          }
        }),
      ],
    }),
  })
  function outerSelection() {
    const start = position()
    const selection = editor.state.selection
    return (
      typeof start === 'number' &&
      selection instanceof TextSelection &&
      !selection.empty &&
      (selection.from < start + 1 || selection.to > start + 1 + node.content.size)
    )
  }
  function copyOuterSelection(event: ClipboardEvent) {
    if (!outerSelection() || !event.clipboardData) {
      return false
    }
    const serialized = editor.view.serializeForClipboard(editor.state.selection.content())
    event.clipboardData.setData('text/plain', serialized.text)
    event.clipboardData.setData('text/html', serialized.dom.innerHTML)
    event.preventDefault()
    return true
  }
  function syncSelection() {
    const start = position()
    if (synchronizing || typeof start !== 'number') {
      return
    }
    const selection = editor.state.selection
    if (
      !(selection instanceof TextSelection) ||
      selection.to < start + 1 ||
      selection.from > start + 1 + node.content.size
    ) {
      return
    }
    const anchor = Math.max(0, Math.min(node.content.size, selection.anchor - start - 1))
    const head = Math.max(0, Math.min(node.content.size, selection.head - start - 1))
    if (input.state.selection.main.anchor === anchor && input.state.selection.main.head === head) {
      return
    }
    synchronizing = true
    try {
      input.dispatch({ selection: { anchor, head } })
    } finally {
      synchronizing = false
    }
  }
  function reconcileDocument() {
    // 只在拒绝/额外转换的输入后恢复本块；离开 CodeMirror 更新回调再派发，避免嵌套更新。
    const document = editor.state.doc
    queueMicrotask(() => {
      if (destroyed || editor.state.doc !== document) {
        return
      }
      const start = position()
      const current = typeof start === 'number' ? document.nodeAt(start) : null
      if (current?.type.name === 'codeBlock') {
        syncNode(current, true)
      }
    })
  }
  function syncNode(next: Node, force = false) {
    if (!force && next === node) {
      return
    }
    // 当前代码输入只更新节点身份；外部修改或拒绝的输入才比较、同步文本。
    if (synchronizing) {
      node = next
      return
    }
    const oldLanguage = String(node.attrs.language || '')
    node = next
    const source = next.textContent
    const current = input.state.doc.toString()
    if (source !== current) {
      let from = 0,
        oldEnd = current.length,
        newEnd = source.length
      while (
        from < oldEnd &&
        from < newEnd &&
        current.charCodeAt(from) === source.charCodeAt(from)
      ) {
        from++
      }
      while (
        oldEnd > from &&
        newEnd > from &&
        current.charCodeAt(oldEnd - 1) === source.charCodeAt(newEnd - 1)
      ) {
        oldEnd--
        newEnd--
      }
      synchronizing = true
      try {
        input.dispatch({ changes: { from, to: oldEnd, insert: source.slice(from, newEnd) } })
      } finally {
        synchronizing = false
      }
      scheduleHighlight()
    } else if (oldLanguage !== String(next.attrs.language || '')) {
      scheduleHighlight()
    }
    syncSelection()
  }
  function scheduleHighlight() {
    clearTimeout(timer)
    cancelPaint()
    highlight?.abort()
    highlight = undefined
    if (!input.inView) {
      return
    }
    const controller = new AbortController()
    highlight = controller
    timer = setTimeout(async () => {
      const source = input.state.doc.toString()
      const language = String(node.attrs.language || '').split(/\s/)[0] || ''
      try {
        const result = await highlightCodeInWorker(source, language, controller.signal)
        const paint = () => {
          if (destroyed || controller.signal.aborted || input.state.doc.toString() !== source) {
            return
          }
          const painted = codePaint(result)
          if (appliedPalette !== painted.style) {
            appliedPalette = painted.style
            const palette = host.ownerDocument.createElement('span').style
            palette.cssText = painted.style
            const block = host.closest<HTMLElement>('.blog-code')
            for (const property of palette) {
              block?.style.setProperty(property, palette.getPropertyValue(property))
            }
          }
          const ranges = painted.tokens
            .filter((token) => token.to <= input.state.doc.length)
            .map((token) =>
              Decoration.mark({ attributes: { style: token.style } }).range(token.from, token.to),
            )
          input.dispatch({ effects: colors.of(Decoration.set(ranges, true)) })
        }
        if (typeof requestIdleCallback === 'function') {
          const paintWhenIdle = (deadline: IdleDeadline) => {
            if (destroyed || controller.signal.aborted) {
              return
            }
            // 高亮补色是次要反馈，不能因超时强制挤进连续输入或滚动的一帧。
            // 当前空闲预算不足时继续等，完整代码与原有颜色始终可读可编辑。
            if (deadline.timeRemaining() < 8) {
              schedulePaint()
              return
            }
            paint()
          }
          const schedulePaint = () => {
            const task = requestIdleCallback(paintWhenIdle)
            cancelPaint = () => cancelIdleCallback(task)
          }
          schedulePaint()
        } else {
          const task = requestAnimationFrame(paint)
          cancelPaint = () => cancelAnimationFrame(task)
        }
      } catch {
        /* 保留完整文本与已有颜色，下一次代码修改再独立重试。 */
      }
    }, 120)
  }
  editor.on('selectionUpdate', syncSelection)
  scheduleHighlight()
  return {
    update: syncNode,
    range() {
      const start = position()
      return typeof start === 'number'
        ? { from: start + 1, to: start + 1 + node.content.size }
        : undefined
    },
    positionAtDOM(dom, offset) {
      const start = position()
      return typeof start === 'number' && input.contentDOM.contains(dom)
        ? start + 1 + input.posAtDOM(dom, offset)
        : null
    },
    positionAtCoords(point) {
      const start = position()
      const offset = input.posAtCoords(point)
      return typeof start === 'number' && offset !== null ? start + 1 + offset : null
    },
    domAtPosition(value) {
      const start = position()
      if (typeof start !== 'number' || value < start + 1 || value > start + 1 + node.content.size) {
        return null
      }
      return input.domAtPos(value - start - 1)
    },
    setSelection(anchor, head, focus = true) {
      if (destroyed) {
        return
      }
      synchronizing = true
      try {
        if (focus && !input.hasFocus) {
          input.focus()
        }
        if (
          input.state.selection.main.anchor !== anchor ||
          input.state.selection.main.head !== head
        ) {
          input.dispatch({ selection: { anchor, head } })
        }
      } finally {
        synchronizing = false
      }
    },
    setEditable(value) {
      if (!destroyed && input.contentDOM.contentEditable !== String(value)) {
        input.dispatch({
          effects: editable.reconfigure([
            EditorView.editable.of(value),
            EditorState.readOnly.of(!value),
          ]),
        })
      }
    },
    destroy() {
      if (destroyed) {
        return
      }
      destroyed = true
      clearTimeout(timer)
      cancelPaint()
      highlight?.abort()
      editor.off('selectionUpdate', syncSelection)
      input.destroy()
    },
  }
}
