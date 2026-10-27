import { ImmersiveMarkdownGuard } from '../utils/editor/immersiveCapabilities'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { TextSelection } from '@tiptap/pm/state'
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

it('单块输入、选区和补色不读取或重建其他代码块，修改别块不取消正在高亮的块', async () => {
  vi.useFakeTimers()
  const pending: { source: string; signal: AbortSignal; finish: (node: ElementNode) => void }[] = []
  background.mockImplementation(
    (source: string, _language: string, signal: AbortSignal) =>
      new Promise<ElementNode>((finish) => pending.push({ source, signal, finish })),
  )
  const source = Array.from({ length: 40 }, (_, i) => `const n${i}=1;`).join('\n')
  const editor = new Editor({
    element: null,
    extensions: [
      StarterKit.configure({ trailingNode: false }),
      ImmersiveMarkdownGuard.configure({ roundtrip: false }),
      ImmersiveDecorations,
    ],
    content: {
      type: 'doc',
      content: [
        { type: 'paragraph', content: [{ type: 'text', text: '前文' }] },
        ...Array.from({ length: 200 }, () => ({
          type: 'paragraph',
          content: [{ type: 'text', text: '无关正文' }],
        })),
        {
          type: 'codeBlock',
          attrs: { language: 'javascript [first.js]' },
          content: [{ type: 'text', text: source }],
        },

        {
          type: 'codeBlock',
          attrs: { language: 'javascript [second.js]' },
          content: [{ type: 'text', text: source + '\n// other' }],
        },
        { type: 'paragraph' },
      ],
    },
  })
  editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
  const plugin = editor.state.plugins.find(
    (p) => p.spec.key?.getState(editor.state) instanceof DecorationSet,
  )!
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
  const positions: number[] = []
  editor.state.doc.forEach((node, pos) => {
    if (node.type.name === 'codeBlock') {
      positions.push(pos)
    }
  })
  const [first, second] = positions as [number, number]
  const ast = (text: string): ElementNode => [
    'pre',
    { style: '--shiki-light:#111' },
    [
      'code',
      {},
      ...text
        .split('\n')
        .flatMap(
          (line, index) =>
            (index
              ? ['\n', ['span', { style: '--shiki-light:#222' }, line]]
              : [['span', { style: '--shiki-light:#222' }, line]]) as (string | ElementNode)[],
        ),
    ],
  ]
  try {
    await vi.advanceTimersByTimeAsync(100)
    expect(pending.length).toBeGreaterThan(0)
    const firstTask = pending[0]!
    // 第二个代码块输入不能取消第一个代码块已在进行的任务。
    view.dispatch(
      editor.state.tr.insertText('x', second + 2, second + 3).setMeta('immersive-plain-text', true),
    )
    expect(firstTask.signal.aborted).toBe(false)
    firstTask.finish(ast(firstTask.source))
    await vi.advanceTimersByTimeAsync(200)
    for (const task of pending) {
      task.finish(ast(task.source))
    }
    await vi.advanceTimersByTimeAsync(200)
    const otherNode = editor.state.doc.nodeAt(second)!
    const otherText = vi.spyOn(otherNode, 'textContent', 'get')
    const decorations = () => plugin.props.decorations!.call(plugin, editor.state) as DecorationSet
    const otherToken = decorations().find(second + 1, second + otherNode.nodeSize - 1, (spec) =>
      Boolean(spec.immersiveToken),
    )[0]
    expect(otherToken).toBeDefined()
    const stableToken = decorations().find(first + 50, first + 70, (spec) =>
      Boolean(spec.immersiveToken),
    )[0]!
    otherText.mockClear()
    const edit = editor.state.tr.insertText('z', first + 2, first + 3)
    const fullScan = vi.spyOn(edit.doc, 'descendants')
    view.dispatch(edit)
    expect(fullScan).not.toHaveBeenCalled()
    fullScan.mockRestore()
    expect(otherText).not.toHaveBeenCalled()
    expect(
      decorations().find(second + 1, second + otherNode.nodeSize - 1, (spec) =>
        Boolean(spec.immersiveToken),
      )[0]!.spec,
    ).toBe(otherToken!.spec)
    otherText.mockClear()
    view.dispatch(
      editor.state.tr.setSelection(TextSelection.near(editor.state.doc.resolve(first + 4))),
    )
    expect(otherText).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(200)
    const latest = pending.findLast(
      (task) =>
        task.source !== source && !task.signal.aborted && task.source.length === source.length,
    )!
    latest.finish(ast(latest.source))
    await vi.advanceTimersByTimeAsync(200)
    expect(otherText).not.toHaveBeenCalled()
    // 当前块未变化的颜色规则也直接复用，不随每次停笔重新批量删除和添加。
    expect(
      decorations().find(stableToken.from, stableToken.to, (spec) => spec === stableToken.spec),
    ).toHaveLength(1)
    view.dispatch(
      editor.state.tr.setMeta(IMMERSIVE_CODE_EXPANDED, { position: first, value: false }),
    )
    expect(otherText).not.toHaveBeenCalled()
    otherText.mockRestore()
    view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, 1)))
    view.dispatch(
      editor.state.tr.setMeta(IMMERSIVE_CODE_EXPANDED, { position: first, value: true }),
    )
    view.dispatch(
      editor.state.tr.setNodeMarkup(first, undefined, {
        ...editor.state.doc.nodeAt(first)!.attrs,
        language: 'javascript [renamed.js]',
      }),
    )
    await vi.advanceTimersByTimeAsync(200)
    const changedInfo = pending.at(-1)!
    changedInfo.finish(ast(changedInfo.source))
    await vi.advanceTimersByTimeAsync(200)
    expect(
      decorations().find(first + 1, first + editor.state.doc.nodeAt(first)!.nodeSize - 1, (spec) =>
        Boolean(spec.immersiveToken),
      ),
    ).toHaveLength(40)
  } finally {
    lifecycle.destroy?.()
    editor.destroy()
  }
})
