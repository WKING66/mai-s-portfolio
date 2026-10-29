import { describe, expect, it, vi } from 'vitest'
import { Editor } from '@tiptap/core'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import { defineComponent } from 'vue'
import CodeBlock from '@tiptap/extension-code-block'
import { DecorationSet } from '@tiptap/pm/view'
import { canvasCodeInputRenderer } from '../utils/editor/canvasCodeInput'
import StarterKit from '@tiptap/starter-kit'
import { ChangeSet } from '@codemirror/state'
import { TextSelection } from '@tiptap/pm/state'
import { codeMirrorTransaction } from '../utils/editor/canvasCodeMirror'
import { ImmersiveMarkdownGuard } from '../utils/editor/immersiveCapabilities'

function setup() {
  const editor = new Editor({
    element: null,
    extensions: [
      StarterKit.configure({ trailingNode: false }),
      ImmersiveMarkdownGuard.configure({ roundtrip: false }),
    ],
    content: {
      type: 'doc',
      content: [
        { type: 'paragraph', content: [{ type: 'text', text: '前文不变' }] },
        {
          type: 'codeBlock',
          attrs: { language: 'javascript [example.js]' },
          content: [{ type: 'text', text: 'const value = 1;\nreturn value;' }],
        },
        { type: 'paragraph', content: [{ type: 'text', text: '后文不变' }] },
      ],
    },
  })
  editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
  const position = editor.state.doc.firstChild!.nodeSize
  return { editor, position, source: editor.state.doc.nodeAt(position)!.textContent }
}
describe('隔离代码输入与真实文章事务', () => {
  it('EditorContent 尚未挂载时保留 Vue 的空占位，不给不存在的 DOM 安装销毁逻辑', () => {
    const { editor, position } = setup()
    try {
      const renderer = canvasCodeInputRenderer(
        VueNodeViewRenderer(defineComponent({ render: () => null })),
      )
      const view = renderer({
        editor,
        view: editor.view,
        node: editor.state.doc.nodeAt(position)!,
        getPos: () => position,
        decorations: [],
        innerDecorations: DecorationSet.empty,
        extension: CodeBlock,
        HTMLAttributes: {},
      })
      expect(view.dom).toBeUndefined()
      expect(view.contentDOM).toBeUndefined()
      expect(view.destroy).toBeUndefined()
    } finally {
      editor.destroy()
    }
  })
  it('多个局部替换保留两侧块身份、代码属性和反向选区，不遍历整篇正文', () => {
    const { editor, position, source } = setup()
    try {
      const before = editor.state.doc.firstChild
      const after = editor.state.doc.lastChild
      const changes = ChangeSet.of(
        [
          { from: 6, to: 11, insert: 'answer' },
          { from: source.lastIndexOf('value'), to: source.length - 1, insert: 'answer' },
        ],
        source.length,
      )
      const transaction = codeMirrorTransaction(editor, position, changes, 9, 3)!
      const scan = vi.spyOn(transaction.doc, 'descendants')
      editor.view.dispatch(transaction)
      expect(scan).not.toHaveBeenCalled()
      scan.mockRestore()
      expect(editor.state.doc.firstChild).toBe(before)
      expect(editor.state.doc.lastChild).toBe(after)
      expect(editor.state.doc.nodeAt(position)!.textContent).toBe(
        'const answer = 1;\nreturn answer;',
      )
      expect(editor.state.doc.nodeAt(position)!.attrs.language).toBe('javascript [example.js]')
      expect(editor.state.selection.anchor).toBe(position + 10)
      expect(editor.state.selection.head).toBe(position + 4)
    } finally {
      editor.destroy()
    }
  })
  it('输入删除及撤销重做共用文章历史，不建立第二套代码历史', () => {
    const { editor, position, source } = setup()
    try {
      editor.commands.setTextSelection(position + source.length + 1)
      const changes = ChangeSet.of({ from: 0, to: source.length, insert: '' }, source.length)
      editor.view.dispatch(codeMirrorTransaction(editor, position, changes, 0, 0)!)
      expect(editor.state.doc.nodeAt(position)!.textContent).toBe('')
      expect(editor.commands.undo()).toBe(true)
      expect(editor.state.doc.nodeAt(position)!.textContent).toBe(source)
      expect(editor.commands.redo()).toBe(true)
      expect(editor.state.doc.nodeAt(position)!.textContent).toBe('')
    } finally {
      editor.destroy()
    }
  })
  it('只移动光标不改变正文，已删除块或过期文本长度不覆盖当前文档', () => {
    const { editor, position, source } = setup()
    try {
      const snapshot = editor.state.doc
      const selection = codeMirrorTransaction(
        editor,
        position,
        ChangeSet.empty(source.length),
        2,
        2,
      )!
      editor.view.dispatch(selection)
      expect(editor.state.doc).toBe(snapshot)
      expect(editor.state.selection).toBeInstanceOf(TextSelection)
      expect(codeMirrorTransaction(editor, position, ChangeSet.empty(1), 0, 0)).toBeNull()
      editor.view.dispatch(
        editor.state.tr.delete(position, position + editor.state.doc.nodeAt(position)!.nodeSize),
      )
      expect(
        codeMirrorTransaction(editor, position, ChangeSet.empty(source.length), 0, 0),
      ).toBeNull()
    } finally {
      editor.destroy()
    }
  })
  it('阅读状态拒绝文字修改，但仍允许选择复制', () => {
    const { editor, position, source } = setup()
    try {
      editor.setEditable(false, false)
      const changes = ChangeSet.of({ from: 0, insert: 'x' }, source.length)
      expect(codeMirrorTransaction(editor, position, changes, 1, 1)).toBeNull()
      expect(
        codeMirrorTransaction(editor, position, ChangeSet.empty(source.length), 0, 3)?.docChanged,
      ).toBe(false)
      expect(editor.state.doc.nodeAt(position)!.textContent).toBe(source)
    } finally {
      editor.destroy()
    }
  })
})
