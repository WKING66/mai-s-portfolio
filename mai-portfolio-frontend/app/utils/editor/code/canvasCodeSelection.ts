import { Extension } from '@tiptap/core'
import { Plugin, TextSelection, type Selection } from '@tiptap/pm/state'
import type { EditorView } from '@tiptap/pm/view'
import { canvasCodeInputEntries, canvasCodeInputs, type CanvasCodeInput } from './canvasCodeInput'

/** 将两个公开编辑视图的端点映射到同一 PM 选区，不将跨块拖选截断在代码外壳。 */
export const CanvasCodeSelection = Extension.create({
  name: 'canvasCodeSelection',
  addProseMirrorPlugins() {
    const editor = this.editor
    function inputAt(node: globalThis.Node | null): CanvasCodeInput | undefined {
      const element = node instanceof Element ? node : node?.parentElement
      const host = element?.closest<HTMLElement>('[data-canvas-code-input]')
      return host ? canvasCodeInputs.get(host) : undefined
    }
    function endpoint(node: globalThis.Node | null, offset: number, fallback: number) {
      return node ? (inputAt(node)?.positionAtDOM(node, offset) ?? fallback) : fallback
    }
    function point(view: EditorView, event: MouseEvent) {
      const target = view.dom.ownerDocument.elementFromPoint(event.clientX, event.clientY)
      const input = inputAt(target)
      return {
        input,
        position:
          input?.positionAtCoords({ x: event.clientX, y: event.clientY }) ??
          view.posAtCoords({ left: event.clientX, top: event.clientY })?.pos,
      }
    }
    function showSelection(view: EditorView) {
      const selection = view.state.selection
      if (!(selection instanceof TextSelection) || selection.empty) {
        return
      }
      const entries = canvasCodeInputEntries(editor)
      const fromInput = entries.find(({ input }) => {
        const range = input.range()
        return range && selection.anchor >= range.from && selection.anchor <= range.to
      })?.input
      const toInput = entries.find(({ input }) => {
        const range = input.range()
        return range && selection.head >= range.from && selection.head <= range.to
      })?.input
      if ((!fromInput && !toInput) || fromInput === toInput) {
        return
      }
      for (const { input } of entries) {
        const range = input.range()
        if (range && selection.to >= range.from && selection.from <= range.to) {
          input.setSelection(
            Math.max(0, Math.min(range.to - range.from, selection.anchor - range.from)),
            Math.max(0, Math.min(range.to - range.from, selection.head - range.from)),
            false,
          )
        }
      }
      const native = view.dom.ownerDocument.getSelection()
      if (!native) {
        return
      }
      const currentAnchor = endpoint(
        native.anchorNode,
        native.anchorOffset,
        native.anchorNode && view.dom.contains(native.anchorNode)
          ? view.posAtDOM(native.anchorNode, native.anchorOffset)
          : -1,
      )
      const currentHead = endpoint(
        native.focusNode,
        native.focusOffset,
        native.focusNode && view.dom.contains(native.focusNode)
          ? view.posAtDOM(native.focusNode, native.focusOffset)
          : -1,
      )
      if (currentAnchor === selection.anchor && currentHead === selection.head) {
        return
      }
      const anchor = fromInput?.domAtPosition(selection.anchor) ?? view.domAtPos(selection.anchor)
      const head = toInput?.domAtPosition(selection.head) ?? view.domAtPos(selection.head)
      native.setBaseAndExtent(anchor.node, anchor.offset, head.node, head.offset)
    }
    return [
      new Plugin({
        props: {
          createSelectionBetween(view, anchor, head) {
            const native = view.dom.ownerDocument.getSelection()
            if (!native || (!inputAt(native.anchorNode) && !inputAt(native.focusNode))) {
              return null
            }
            return TextSelection.between(
              view.state.doc.resolve(endpoint(native.anchorNode, native.anchorOffset, anchor.pos)),
              view.state.doc.resolve(endpoint(native.focusNode, native.focusOffset, head.pos)),
            )
          },
        },
        view(view) {
          let drag:
            | { anchor: number; input?: CanvasCodeInput; active: boolean; last?: Selection }
            | undefined
          const down = (event: MouseEvent) => {
            if (
              event.button !== 0 ||
              (event.target instanceof Element && event.target.closest('[data-editor-controls]'))
            ) {
              return
            }
            const target = point(view, event)
            if (target.position === undefined) {
              return
            }
            drag = {
              anchor: event.shiftKey ? view.state.selection.anchor : target.position,
              input: target.input,
              active: false,
            }
            if (target.input && !event.shiftKey) {
              view.dispatch(
                view.state.tr.setSelection(
                  TextSelection.near(view.state.doc.resolve(target.position)),
                ),
              )
            }
          }
          const move = (event: MouseEvent) => {
            if (!drag || !(event.buttons & 1)) {
              return
            }
            const target = point(view, event)
            if (
              target.position === undefined ||
              (!drag.active && ((!drag.input && !target.input) || drag.input === target.input))
            ) {
              return
            }
            drag.active = true
            const selection = TextSelection.between(
              view.state.doc.resolve(drag.anchor),
              view.state.doc.resolve(target.position),
            )
            drag.last = selection
            // 同一字符范围内的鼠标移动不重复通知全文；派发后由同一个 view.update 同步显示。
            if (!selection.eq(view.state.selection)) {
              view.dispatch(view.state.tr.setSelection(selection))
            }
            event.preventDefault()
            event.stopImmediatePropagation()
          }
          const up = () => {
            const selection = drag?.active ? drag.last : undefined
            const document = view.state.doc
            drag = undefined
            if (selection) {
              queueMicrotask(() => {
                if (!editor.isDestroyed && view.state.doc === document) {
                  if (!selection.eq(view.state.selection)) {
                    view.dispatch(view.state.tr.setSelection(selection))
                  } else {
                    showSelection(view)
                  }
                }
              })
            }
          }
          view.dom.addEventListener('mousedown', down, true)
          window.addEventListener('mousemove', move, true)
          window.addEventListener('mouseup', up, true)
          return {
            update: showSelection,
            destroy() {
              view.dom.removeEventListener('mousedown', down, true)
              window.removeEventListener('mousemove', move, true)
              window.removeEventListener('mouseup', up, true)
            },
          }
        },
      }),
    ]
  },
})
