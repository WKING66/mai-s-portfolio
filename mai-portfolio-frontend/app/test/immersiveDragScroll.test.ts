import { describe, expect, it, vi } from 'vitest'
import type { EditorView } from '@tiptap/pm/view'
import { EditorState } from '@tiptap/pm/state'
import { canvasSchema, importCanvasMarkdown } from '../utils/editor/canvasMarkdown'
import { canvasDragScroll } from '../utils/editor/immersiveDragScroll'

// 运行真实滚动控制器和原生 EventTarget；几何/动画帧是可控夹具，不冒充浏览器拖放。
function fixture() {
  const frames = new Map<number, FrameRequestCallback>()
  let id = 0
  const window = Object.assign(new EventTarget(), {
    innerHeight: 800,
    requestAnimationFrame: (callback: FrameRequestCallback) => {
      frames.set(++id, callback)
      return id
    },
    cancelAnimationFrame: (id: number) => frames.delete(id),
  })
  const document = Object.assign(new EventTarget(), {
    defaultView: window,
    hidden: false,
    documentElement: new EventTarget(),
  })
  const pane = {
    scrollTop: 300,
    scrollHeight: 2000,
    clientHeight: 600,
    getBoundingClientRect: () => ({ top: 100, bottom: 700, left: 0, right: 900 }),
  }
  const state = EditorState.create({
    doc: canvasSchema.nodeFromJSON(importCanvasMarkdown('## 标题\n\n正文\n\n另一块')),
  })
  const view = {
    dom: { ownerDocument: document, closest: () => pane },
    state,
    dispatch: vi.fn(),
    editable: true,
    isDestroyed: false,
    dragging: { slice: state.doc.slice(0), move: true } as EditorView['dragging'],
  }
  const controller = canvasDragScroll(view as unknown as EditorView)
  function move(y: number, x = 450) {
    const event = Object.assign(new Event('dragover', { cancelable: true }), {
      clientX: x,
      clientY: y,
    })
    document.dispatchEvent(event)
    return event
  }
  function tick(time = 16) {
    const callbacks = [...frames.values()]
    frames.clear()
    callbacks.forEach((callback) => callback(time))
  }
  return { view, document, window, pane, frames, controller, move, tick }
}

describe('内部块拖拽的画布边缘滚动', () => {
  it('底部及稍越过底部时持续滚动，鼠标停住也继续；不接管放置或修改 PM 状态', () => {
    const f = fixture()
    try {
      const state = f.view.state
      const slice = f.view.dragging!.slice
      expect(f.move(720).defaultPrevented).toBe(false)
      f.tick()
      const first = f.pane.scrollTop
      expect(first).toBeGreaterThan(300)
      f.tick(32)
      expect(f.pane.scrollTop).toBeGreaterThan(first)
      expect(f.frames.size).toBe(1)
      expect(f.view.state).toBe(state)
      expect(f.view.dragging!.slice).toBe(slice)
      expect(f.view.dispatch).not.toHaveBeenCalled()
    } finally {
      f.controller.destroy?.()
    }
  })

  it('顶部向上滚动，回到中间或离开横向画布范围后停止', () => {
    const f = fixture()
    try {
      f.move(90)
      f.tick()
      expect(f.pane.scrollTop).toBeLessThan(300)
      f.move(400)
      f.tick(32)
      expect(f.frames.size).toBe(0)
      const before = f.pane.scrollTop
      f.move(695, 1000)
      f.tick(48)
      expect(f.pane.scrollTop).toBe(before)
    } finally {
      f.controller.destroy?.()
    }
  })

  it('外部文件拖入和只读状态不启动滚动', () => {
    const f = fixture()
    try {
      f.view.dragging = null
      f.move(695)
      expect(f.frames.size).toBe(0)
      f.view.dragging = { slice: f.view.state.doc.slice(0), move: true }
      f.view.editable = false
      f.move(695)
      expect(f.frames.size).toBe(0)
    } finally {
      f.controller.destroy?.()
    }
  })

  it.each(['drop', 'dragend'])('%s 后立即停止，不拦截原有事件', (type) => {
    const f = fixture()
    try {
      f.move(695)
      f.tick()
      const before = f.pane.scrollTop
      const event = new Event(type, { cancelable: true })
      f.document.dispatchEvent(event)
      f.tick(32)
      expect(f.pane.scrollTop).toBe(before)
      expect(f.frames.size).toBe(0)
      expect(event.defaultPrevented).toBe(false)
    } finally {
      f.controller.destroy?.()
    }
  })

  it('取消、失焦、卸载时释放帧和监听；达到滚动首尾时不空转', () => {
    const f = fixture()
    try {
      f.pane.scrollTop = 1400
      f.move(695)
      f.tick()
      expect(f.frames.size).toBe(0)
      f.move(90)
      f.tick(32)
      expect(f.frames.size).toBe(1)
      f.document.dispatchEvent(Object.assign(new Event('keydown'), { key: 'Escape' }))
      expect(f.frames.size).toBe(0)
      f.move(695)
      f.window.dispatchEvent(new Event('blur'))
      expect(f.frames.size).toBe(0)
      f.controller.destroy?.()
      f.move(695)
      expect(f.frames.size).toBe(0)
    } finally {
      f.controller.destroy?.()
    }
  })
})
