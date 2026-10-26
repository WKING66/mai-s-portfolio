import { describe, expect, it } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import {
  CANVAS_EDITOR_EXTENSIONS,
  CANVAS_STARTER_KIT,
  createCanvasMarkdownOptions,
} from '../utils/editor/canvasMarkdown'
import {
  newImmersiveParagraph,
  continueImmersiveDocument,
  ImmersiveTabBoundary,
} from '../utils/editor/immersiveParagraphInput'
import { exitImmersiveMath, mathExitDirection } from '../utils/editor/immersiveMath'

function createEditor(source: string) {
  const editor = new Editor({
    element: null,
    extensions: [
      StarterKit.configure(CANVAS_STARTER_KIT),
      ...CANVAS_EDITOR_EXTENSIONS,
      Markdown.configure(createCanvasMarkdownOptions()),
      ImmersiveTabBoundary,
    ],
    content: source,
    contentType: 'markdown',
  })
  editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
  return editor
}

function tabKey(editor: Editor, shiftKey = false) {
  const event = {
    key: 'Tab',
    keyCode: 9,
    shiftKey,
    ctrlKey: false,
    altKey: false,
    metaKey: false,
  } as KeyboardEvent
  return editor.state.plugins.some((plugin) =>
    Boolean(plugin.props.handleKeyDown?.call(plugin, editor.view, event)),
  )
}

describe('Tab 焦点边界沿用真实快捷键处理顺序', () => {
  it.each([false, true])('普通正文保留光标和内容；只读不拦截浏览器导航（Shift=%s）', (shift) => {
    const editor = createEditor('首段\n\n编辑中的正文')
    try {
      editor.commands.setTextSelection(editor.state.doc.content.size - 2)
      const before = editor.state
      expect(tabKey(editor, shift)).toBe(true)
      expect(editor.state.doc).toBe(before.doc)
      expect(editor.state.selection.eq(before.selection)).toBe(true)
      editor.setEditable(false, false)
      expect(tabKey(editor, shift)).toBe(false)
    } finally {
      editor.destroy()
    }
  })

  it('表格 Tab 和 Shift+Tab 继续进入下一格/上一格', () => {
    const editor = createEditor('| 甲 | 乙 |\n|---|---|\n| 丙 | 丁 |')
    try {
      let position = 0
      editor.state.doc.descendants((node, from) => {
        if (node.text === '甲') {
          position = from
        }
      })
      editor.commands.setTextSelection(position)
      expect(tabKey(editor)).toBe(true)
      expect(editor.state.selection.$from.parent.textContent).toBe('乙')
      expect(tabKey(editor, true)).toBe(true)
      expect(editor.state.selection.$from.parent.textContent).toBe('甲')
    } finally {
      editor.destroy()
    }
  })

  it('列表 Tab 缩进与 Shift+Tab 提升仍由原有列表命令处理', () => {
    const editor = createEditor('- 第一项\n- 第二项')
    try {
      editor.commands.setTextSelection(editor.state.doc.content.size - 3)
      const before = editor.state.doc
      expect(tabKey(editor)).toBe(true)
      expect(editor.state.selection.$from.depth).toBe(5)
      expect(tabKey(editor, true)).toBe(true)
      expect(editor.state.doc.eq(before)).toBe(true)
    } finally {
      editor.destroy()
    }
  })

  it('代码选区 Tab 仍插入缩进，不被正文兜底吞掉', () => {
    const editor = createEditor('~~~ts\nconst x = 1\n~~~')
    try {
      editor.commands.setTextSelection({ from: 1, to: editor.state.doc.content.size - 1 })
      expect(tabKey(editor)).toBe(true)
      expect(editor.state.doc.firstChild?.textContent).toMatch(/^\s+const x = 1$/)
    } finally {
      editor.destroy()
    }
  })
})
describe('Enter 根据光标和结构决定格式继承', () => {
  it('点击文末空白可接续最后的原子块，重复点击复用空段并可正常撤销', () => {
    const editor = createEditor('$$\nx^2\n$$')
    try {
      const before = editor.state.doc
      expect(editor.commands.command(continueImmersiveDocument)).toBe(true)
      expect(editor.state.doc.lastChild?.type.name).toBe('paragraph')
      expect(editor.state.selection.$from.parent.type.name).toBe('paragraph')
      editor.commands.command(continueImmersiveDocument)
      expect(editor.state.doc.childCount).toBe(before.childCount + 1)
      editor.commands.undo()
      expect(editor.state.doc.eq(before)).toBe(true)
    } finally {
      editor.destroy()
    }
  })

  it('在公式首/末行用上下键退出，跨行中间及选区不被截获', () => {
    expect(mathExitDirection('a\nb\nc', 1, 1, 'ArrowUp')).toBe(-1)
    expect(mathExitDirection('a\nb\nc', 3, 3, 'ArrowUp')).toBeUndefined()
    expect(mathExitDirection('a\nb\nc', 4, 4, 'ArrowDown')).toBe(1)
    expect(mathExitDirection('a\nb\nc', 3, 3, 'ArrowDown')).toBeUndefined()
    expect(mathExitDirection('abc', 0, 2, 'ArrowUp')).toBeUndefined()
  })

  it.each([-1, 1] as const)('文首/末公式退出到有效段落，保留公式与撤销历史：%s', (direction) => {
    const editor = createEditor('$$\nx^2\n$$')
    try {
      const before = editor.state.doc
      expect(editor.commands.command(exitImmersiveMath(0, direction))).toBe(true)
      expect(editor.state.selection.$from.parent.type.name).toBe('paragraph')
      expect(editor.state.doc.child(direction < 0 ? 1 : 0).eq(before.firstChild!)).toBe(true)
      editor.commands.undo()
      expect(editor.state.doc.eq(before)).toBe(true)
    } finally {
      editor.destroy()
    }
  })
  it('Enter 导航到已有加粗内容的单元格时，不清除目标单元格格式', () => {
    const editor = createEditor('| 起点 | **目标** |\n|---|---|\n| A | B |')
    try {
      let position = 0
      editor.state.doc.descendants((node, from) => {
        if (node.isText && node.text === '起点') {
          position = from + node.nodeSize
        }
      })
      editor.commands.setTextSelection(position)
      expect(editor.commands.command(newImmersiveParagraph)).toBe(true)
      editor.view.dispatch(editor.state.tr.insertText('继续'))
      expect(
        editor.state.selection.$from.nodeBefore?.marks.some((mark) => mark.type.name === 'bold'),
      ).toBe(true)
    } finally {
      editor.destroy()
    }
  })
  it.each(['**加粗内容**', '## **加粗内容**', '- **加粗内容**', '> **加粗内容**'])(
    '在格式文字中间拆段不丢掉后续连续输入的样式：%s',
    (source) => {
      const editor = createEditor(source)
      try {
        let from = 0
        editor.state.doc.descendants((node, position) => {
          if (node.isText) {
            from = position + 2
          }
        })
        editor.commands.setTextSelection(from)
        expect(editor.commands.command(newImmersiveParagraph)).toBe(true)
        editor.view.dispatch(editor.state.tr.insertText('继续'))
        expect(
          editor.state.selection.$from.nodeBefore?.marks.some((mark) => mark.type.name === 'bold'),
        ).toBe(true)
      } finally {
        editor.destroy()
      }
    },
  )
  it('在完整加粗段落末尾结束该段后，下一段不意外继承加粗', () => {
    const editor = createEditor('**完整段落**')
    try {
      editor.commands.setTextSelection(editor.state.doc.content.size - 1)
      editor.commands.command(newImmersiveParagraph)
      editor.view.dispatch(editor.state.tr.insertText('普通段落'))
      expect(editor.state.selection.$from.nodeBefore?.marks).toEqual([])
    } finally {
      editor.destroy()
    }
  })
})
