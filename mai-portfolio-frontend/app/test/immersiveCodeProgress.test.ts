import { afterEach, describe, expect, it, vi } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { DecorationSet, type EditorView } from '@tiptap/pm/view'
import type { ElementNode } from 'comark'
import {
  ImmersiveDecorations,
  IMMERSIVE_CODE_EXPANDED,
  IMMERSIVE_HIGHLIGHT_READY,
} from '../utils/editor/immersiveDecorations'

const background = vi.hoisted(() => vi.fn())
vi.mock('../utils/codeHighlightClient', () => ({
  highlightCodeInWorker: background,
  nextCodePaint: () => Promise.resolve(),
}))
afterEach(() => {
  vi.useRealTimers()
  background.mockReset()
})

describe('原生代码视图渐进着色', () => {
  it('装载时不分词，正文输入不取消代码，预览/展开/选区只改变装饰', async () => {
    vi.useFakeTimers()
    let ready = false
    let finish!: (node: ElementNode) => void
    background.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const source = Array.from({ length: 40 }, (_, i) => `const value${i}=1;`).join('\n')
    const editor = new Editor({
      element: null,
      extensions: [StarterKit, ImmersiveDecorations.configure({ ready: () => ready })],
      content: {
        type: 'doc',
        content: [
          { type: 'paragraph', content: [{ type: 'text', text: '前文' }] },
          {
            type: 'codeBlock',
            attrs: { language: 'javascript' },
            content: [{ type: 'text', text: source }],
          },
          { type: 'paragraph' },
        ],
      },
    })
    editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
    const plugin = editor.state.plugins.find(
      (p) => p.spec.key?.getState(editor.state) instanceof DecorationSet,
    )!
    // 用真实 PM 状态/事务驱动插件视图；这里不模拟浏览器布局或语法算法。
    const view = {
      get state() {
        return editor.state
      },
      dispatch(transaction: Parameters<EditorView['dispatch']>[0]) {
        const previous = editor.state
        editor.view.dispatch(transaction)
        lifecycle.update?.(view, previous)
      },
    } as EditorView
    const lifecycle = plugin.spec.view!(view)
    const tokens = () =>
      (plugin.props.decorations!.call(plugin, editor.state) as DecorationSet)
        .find()
        .filter((d) => d.spec.immersiveToken)
    try {
      await vi.advanceTimersByTimeAsync(100)
      expect(background).not.toHaveBeenCalled()
      ready = true
      view.dispatch(editor.state.tr.setMeta(IMMERSIVE_HIGHLIGHT_READY, true))
      await vi.advanceTimersByTimeAsync(100)
      expect(background).toHaveBeenCalledTimes(1)
      const signal = background.mock.calls[0]![2] as AbortSignal
      view.dispatch(editor.state.tr.insertText('新', 2))
      expect(signal.aborted).toBe(false)
      const snapshot = editor.state.doc
      finish([
        'pre',
        { style: '--shiki-light:#111' },
        [
          'code',
          {},
          ...source
            .split('\n')
            .flatMap(
              (line, index) =>
                (index
                  ? ['\n', ['span', { style: '--shiki-light:#222' }, line]]
                  : [['span', { style: '--shiki-light:#222' }, line]]) as (string | ElementNode)[],
            ),
        ],
      ])
      await vi.advanceTimersByTimeAsync(1)
      expect(tokens()).toHaveLength(14)
      const position = editor.state.doc.firstChild!.nodeSize
      view.dispatch(editor.state.tr.setMeta(IMMERSIVE_CODE_EXPANDED, { position, value: true }))
      expect(tokens()).toHaveLength(40)
      view.dispatch(editor.state.tr.setMeta(IMMERSIVE_CODE_EXPANDED, { position, value: false }))
      expect(tokens()).toHaveLength(14)
      editor.commands.setTextSelection({ from: position + 3, to: position + source.length - 2 })
      expect(tokens()).toHaveLength(40)
      expect(editor.state.doc).toBe(snapshot)
      lifecycle.destroy?.()
      // 已卸载视图不会继续接收迟到补色。
      expect(signal.aborted).toBe(true)
    } finally {
      editor.destroy()
    }
  })
})
