import { describe, expect, it } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import {
  CANVAS_EDITOR_EXTENSIONS,
  CANVAS_STARTER_KIT,
  ALERT_KINDS,
  createCanvasMarkdownOptions,
  importCanvasMarkdown,
  exportCanvasMarkdown,
  canvasSchema,
} from '../utils/editor/canvasMarkdown'
import {
  IMMERSIVE_COMMANDS,
  executeImmersiveCommand,
  canConvertImmersiveTextBlock,
  immersiveSlashTarget,
  immersiveCommandProblem,
  openImmersiveMenu,
  IMMERSIVE_MENU_KEY,
  type ImmersiveMenuTarget,
} from '../utils/editor/immersiveCommands'
import { closeHistory } from '@tiptap/pm/history'
import { editorContentMeaning } from '../utils/editor/markdownOutput'

describe('Showcase 新语法的真实编辑命令', () => {
  it.each(['- 原有一\n- 原有二', '1. 原有一\n2. 原有二', '- [ ] 原有一\n- [ ] 原有二'])(
    'Shift+Enter 列表软换行的斜杠和加号均在当前行插入，保留列表并可撤销：%s',
    (markdown) => {
      for (const entry of ['slash', 'plus', 'plus-content']) {
        const editor = new Editor({
          element: null,
          extensions: [
            StarterKit.configure(CANVAS_STARTER_KIT),
            ...CANVAS_EDITOR_EXTENSIONS,
            Markdown.configure(createCanvasMarkdownOptions()),
          ],
          content: importCanvasMarkdown(markdown),
        })
        editor.view.updateState(
          editor.state.reconfigure({ plugins: editor.extensionManager.plugins }),
        )
        try {
          let end = 0
          editor.state.doc.descendants((node, position) => {
            if (node.type.name === 'paragraph' && node.textContent === '原有一') {
              end = position + node.nodeSize - 1
            }
          })
          editor.commands.setTextSelection(end)
          editor.commands.setHardBreak()
          if (entry === 'slash') {
            editor.commands.insertContent({ type: 'text', text: '/code' })
          } else if (entry === 'plus-content') {
            editor.commands.insertContent({ type: 'text', text: '下一行保留' })
          }
          const before = editor.state.doc
          let target: ImmersiveMenuTarget | null = null
          if (entry === 'slash') {
            target = immersiveSlashTarget(editor.state)
            expect(target?.query).toBe('code')
          } else {
            const requests: ImmersiveMenuTarget[] = []
            editor.on('transaction', ({ transaction }) => {
              const request = transaction.getMeta(IMMERSIVE_MENU_KEY)
              if (request) {
                requests.push(request)
              }
            })
            openImmersiveMenu(editor, 0)
            target = requests.at(-1) ?? null
            expect(target?.kind).toBe('line')
          }
          expect(target).not.toBeNull()
          const item = IMMERSIVE_COMMANDS.find((item) => item.kind === 'codeBlock')!
          expect(immersiveCommandProblem(editor, target!, item)).toBe('')
          expect(editor.state.doc).toBe(before)
          editor.view.dispatch(closeHistory(editor.state.tr))
          expect(executeImmersiveCommand(editor, target!, item)).toBe('')
          const list = editor.state.doc.firstChild!
          expect(list.childCount).toBe(2)
          expect(list.firstChild!.firstChild!.textContent).toBe('原有一')
          expect(list.firstChild!.child(1).type.name).toBe('codeBlock')
          expect(list.firstChild!.child(1).textContent).toBe('')
          if (entry === 'plus-content') {
            expect(list.firstChild!.child(2).textContent).toBe('下一行保留')
          }
          expect(list.child(1).textContent).toBe('原有二')
          expect(
            editorContentMeaning(
              importCanvasMarkdown(exportCanvasMarkdown(editor.state.doc.toJSON())),
            ),
          ).toEqual(editorContentMeaning(editor.state.doc.toJSON()))
          editor.commands.undo()
          expect(editor.state.doc.eq(before)).toBe(true)
        } finally {
          editor.destroy()
        }
      }
    },
  )
  it('图片及包含图片的段落不提供文本转换，普通标题/公式正文保持可转换', () => {
    for (const source of ['![图片](resource://image)', '文字 ![图片](resource://image)']) {
      const node = canvasSchema.nodeFromJSON(importCanvasMarkdown(source)).firstChild!
      expect(canConvertImmersiveTextBlock(node)).toBe(false)
    }
    for (const source of ['普通正文', '## 标题', '正文 $x^2$']) {
      expect(
        canConvertImmersiveTextBlock(
          canvasSchema.nodeFromJSON(importCanvasMarkdown(source)).firstChild!,
        ),
      ).toBe(true)
    }
    expect(canConvertImmersiveTextBlock(null)).toBe(false)
  })
  it.each(ALERT_KINDS)('%s 提示块从菜单插入后可直接输入并输出标准引用标记', (kind) => {
    const editor = new Editor({
      element: null,
      extensions: [
        StarterKit.configure(CANVAS_STARTER_KIT),
        ...CANVAS_EDITOR_EXTENSIONS,
        Markdown.configure(createCanvasMarkdownOptions()),
      ],
      content: { type: 'doc', content: [{ type: 'paragraph' }] },
    })
    try {
      const item = IMMERSIVE_COMMANDS.find(
        (item) => item.kind === 'alert' && item.alertKind === kind,
      )!
      expect(
        executeImmersiveCommand(editor, { kind: 'block', from: 0, to: 0, query: '' }, item),
      ).toBe('')
      let position = 0
      editor.state.doc.descendants((node, from) => {
        if (node.type.name === 'paragraph' && !position) {
          position = from + 1
        }
      })
      editor.view.dispatch(editor.state.tr.insertText('可编辑提示正文', position))
      const output = exportCanvasMarkdown(editor.state.doc.toJSON())
      expect(output).toContain('> [!' + kind + ']')
      expect(output).toContain('> 可编辑提示正文')
      expect(editorContentMeaning(importCanvasMarkdown(output))).toEqual(
        editorContentMeaning(editor.state.doc.toJSON()),
      )
    } finally {
      editor.destroy()
    }
  })
})
