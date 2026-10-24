import { Extension } from '@tiptap/core'
import { Plugin } from '@tiptap/pm/state'
import type { EditorView } from '@tiptap/pm/view'

/** 只滚动画布；原有拖拽 Slice、选区、放置及撤销仍交给 ProseMirror。 */
export function canvasDragScroll(view: EditorView) {
  const document = view.dom.ownerDocument
  const window = document.defaultView
  const pane = view.dom.closest<HTMLElement>('.canvas-pane')
  if (!window || !pane) {
    return {}
  }
  const listeners = new AbortController()
  let point: { x: number; y: number } | undefined
  let frame = 0
  let previousTime: number | undefined

  function stop() {
    window!.cancelAnimationFrame(frame)
    frame = 0
    point = undefined
    previousTime = undefined
  }

  function scroll(time: number) {
    frame = 0
    if (!point || !view.dragging || !view.editable || view.isDestroyed || document.hidden) {
      stop()
      return
    }
    const rect = pane!.getBoundingClientRect()
    const top = Math.max(0, rect.top)
    const bottom = Math.min(window!.innerHeight, rect.bottom)
    if (bottom <= top || point.x < rect.left - 24 || point.x > rect.right + 24) {
      stop()
      return
    }
    const edge = Math.min(64, (bottom - top) / 4)
    let direction = 0
    if (point.y < top + edge) {
      direction = -Math.min(1, (top + edge - point.y) / edge)
    } else if (point.y > bottom - edge) {
      direction = Math.min(1, (point.y - bottom + edge) / edge)
    }
    const elapsed = Math.min(32, previousTime === undefined ? 16 : time - previousTime)
    previousTime = time
    const before = pane!.scrollTop
    pane!.scrollTop = Math.max(
      0,
      Math.min(
        pane!.scrollHeight - pane!.clientHeight,
        before + (direction * 900 * elapsed) / 1000,
      ),
    )
    if (pane!.scrollTop !== before) {
      frame = window!.requestAnimationFrame(scroll)
    } else {
      // 停在中间或到达首尾时不空转，下一次 dragover 可重新启动。
      previousTime = undefined
    }
  }

  const options = { capture: true, signal: listeners.signal }
  document.addEventListener(
    'dragover',
    (event) => {
      // 外部文件、表格轴指针拖动不属于 PM 原生内部拖拽。
      if (!view.dragging || !view.editable) {
        stop()
        return
      }
      point = { x: event.clientX, y: event.clientY }
      if (!frame) {
        frame = window.requestAnimationFrame(scroll)
      }
    },
    options,
  )
  document.addEventListener('drop', stop, options)
  document.addEventListener('dragend', stop, options)
  document.addEventListener(
    'keydown',
    (event) => {
      if (event.key === 'Escape') {
        stop()
      }
    },
    options,
  )
  document.addEventListener(
    'visibilitychange',
    () => {
      if (document.hidden) {
        stop()
      }
    },
    options,
  )
  document.addEventListener(
    'dragleave',
    (event) => {
      if (event.target === document.documentElement && !event.relatedTarget) {
        stop()
      }
    },
    options,
  )
  window.addEventListener('blur', stop, options)
  return {
    destroy() {
      stop()
      listeners.abort()
    },
  }
}

export const ImmersiveDragScroll = Extension.create({
  name: 'immersiveDragScroll',
  addProseMirrorPlugins: () => [new Plugin({ view: canvasDragScroll })],
})
