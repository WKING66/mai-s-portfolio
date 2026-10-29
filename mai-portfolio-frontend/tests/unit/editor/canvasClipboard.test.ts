import { describe, expect, it, vi } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import type { JSONContent } from '@tiptap/core'
import {
  CANVAS_EDITOR_EXTENSIONS,
  CANVAS_STARTER_KIT,
  importCanvasMarkdown,
} from '../../../app/utils/editor/markdown/canvasMarkdown'
import { canvasMarkdownClipboard } from '../../../app/utils/editor/interaction/canvasClipboard'

function setup(source = '', parse = async (text: string) => importCanvasMarkdown(text)) {
  const editor = new Editor({
    element: null,
    extensions: [StarterKit.configure(CANVAS_STARTER_KIT), ...CANVAS_EDITOR_EXTENSIONS],
    content: importCanvasMarkdown(source),
  })
  const notice = vi.fn()
  const pendingChanged = vi.fn()
  const plugin = canvasMarkdownClipboard(editor, {
    resources: () => [],
    parse,
    notice,
    pendingChanged,
  })
  editor.view.updateState(
    editor.state.reconfigure({ plugins: [...editor.extensionManager.plugins, plugin] }),
  )
  function paste(text: string, html = '') {
    const event = {
      clipboardData: {
        files: [],
        getData: (type: string) => (type === 'text/plain' ? text : html),
      },
      preventDefault: vi.fn(),
    } as unknown as ClipboardEvent
    return plugin.props.handlePaste!.call(plugin, editor.view, event, editor.state.doc.slice(0))
  }
  const settle = async () => {
    await new Promise<void>((resolve) => setImmediate(resolve))
  }
  return { editor, plugin, paste, notice, pendingChanged, settle }
}

describe('Markdown 剪贴板使用完整解析，普通内部复制沿用原流程', () => {
  it('工具栏重新注册插件后，同一 Plugin 仍可完成粘贴', async () => {
    const { editor, plugin, paste, settle, pendingChanged } = setup()
    try {
      const firstView = plugin.spec.view!(editor.view)
      firstView.destroy!()
      const nextView = plugin.spec.view!(editor.view)
      paste('## 重建后的标题')
      await settle()
      expect(editor.state.doc.firstChild?.type.name).toBe('heading')
      expect(pendingChanged).toHaveBeenLastCalledWith(false)
      nextView.destroy!()
    } finally {
      editor.destroy()
    }
  })
  it('正常粘贴生成标题、表格、公式和代码节点，一次撤销恢复原文', async () => {
    const { editor, paste, settle } = setup()
    try {
      const before = editor.state.doc
      const source =
        '## 第一章\n\n首版 **加粗** $x^2+1$\n\n| 项目 | 结果 |\n| --- | --- |\n| 公式 | $2n-1$ |\n\n~~~typescript [acceptance.ts]\nconst result = 1\n~~~'
      expect(paste(source)).toBe(true)
      await settle()
      const types: string[] = []
      editor.state.doc.descendants((node) => {
        types.push(node.type.name)
      })
      expect(types).toContain('heading')
      expect(types).toContain('table')
      expect(types).toContain('preservedMathInline')
      expect(types).toContain('codeBlock')
      expect(editor.state.doc.textContent).not.toContain('##')
      expect(editor.commands.undo()).toBe(true)
      expect(editor.state.doc.eq(before)).toBe(true)
      expect(editor.commands.redo()).toBe(true)
      expect(editor.state.doc.textContent).toContain('const result = 1')
    } finally {
      editor.destroy()
    }
  })

  it('替换连续选区，不重建整篇文档', async () => {
    const { editor, paste, settle } = setup('原有内容')
    try {
      editor.commands.selectAll()
      paste('## 新标题\n\n新正文')
      await settle()
      expect(editor.state.doc.firstChild?.type.name).toBe('heading')
      expect(editor.state.doc.textContent).toBe('新标题新正文')
    } finally {
      editor.destroy()
    }
  })

  it.each([
    ['普通正文', ''],
    ['## 内部标题', '<h2 data-pm-slice="1 1 []">内部标题</h2>'],
  ])('不接管原生粘贴：%s', (text, html) => {
    const { editor, paste } = setup()
    try {
      expect(paste(text, html)).toBe(false)
    } finally {
      editor.destroy()
    }
  })

  it.each(['~~~ts\ncode\n~~~', '| 甲 | 乙 |\n|---|---|\n| 丙 | 丁 |'])(
    '代码和表格内粘贴保留既有处理：%s',
    (source) => {
      const { editor, paste } = setup(source)
      try {
        editor.commands.setTextSelection(source.startsWith('|') ? 4 : 1)
        expect(paste('## 字面内容')).toBe(false)
      } finally {
        editor.destroy()
      }
    },
  )

  it('Ctrl+Shift+V 保留全部 Markdown 符号为纯文本', async () => {
    const { editor, plugin, paste, settle } = setup()
    try {
      plugin.props.handleDOMEvents!.keydown!.call(plugin, editor.view, {
        key: 'v',
        shiftKey: true,
        ctrlKey: true,
        metaKey: false,
      } as KeyboardEvent)
      paste('## 标题\n\n**加粗** $x^2$')
      await settle()
      expect(editor.state.doc.firstChild?.type.name).toBe('paragraph')
      expect(editor.state.doc.textContent).toContain('## 标题')
      expect(editor.state.doc.textContent).toContain('**加粗** $x^2$')
    } finally {
      editor.destroy()
    }
  })

  it('后台解析期间的新输入和光标不会被旧选区覆盖', async () => {
    let complete!: (document: JSONContent) => void
    const { editor, paste, settle, pendingChanged } = setup(
      '',
      () =>
        new Promise((resolve) => {
          complete = resolve
        }),
    )
    try {
      paste('## 粘贴标题')
      expect(pendingChanged).toHaveBeenLastCalledWith(true)
      editor.view.dispatch(editor.state.tr.insertText('后续输入'))
      complete(importCanvasMarkdown('## 粘贴标题'))
      await settle()
      expect(editor.state.doc.textContent).toContain('后续输入')
      expect(editor.state.doc.textContent).toContain('粘贴标题')
      expect(editor.state.selection.$from.parent.textContent).toBe('后续输入')
      expect(pendingChanged).toHaveBeenLastCalledWith(false)
    } finally {
      editor.destroy()
    }
  })

  it('选中内容已被编辑时拒绝旧粘贴，保留新输入', async () => {
    let complete!: (document: JSONContent) => void
    const { editor, paste, settle, notice } = setup(
      '旧文字',
      () =>
        new Promise((resolve) => {
          complete = resolve
        }),
    )
    try {
      editor.commands.setTextSelection({ from: 1, to: 4 })
      paste('## 粘贴标题')
      editor.view.dispatch(editor.state.tr.insertText('新文字'))
      complete(importCanvasMarkdown('## 粘贴标题'))
      await settle()
      expect(editor.state.doc.textContent).toBe('新文字')
      expect(notice).toHaveBeenCalledWith(expect.stringContaining('选中内容已变化'))
    } finally {
      editor.destroy()
    }
  })

  it('解析失败保留原文并结束等待状态', async () => {
    const { editor, paste, settle, notice, pendingChanged } = setup('保留正文', async () => {
      throw new Error('解析失败')
    })
    try {
      const before = editor.state.doc
      paste('## 粘贴标题')
      await settle()
      expect(editor.state.doc).toBe(before)
      expect(notice).toHaveBeenCalledWith('解析失败')
      expect(pendingChanged).toHaveBeenLastCalledWith(false)
    } finally {
      editor.destroy()
    }
  })
})
