import { describe, expect, it } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { importCanvasMarkdown } from '../utils/editor/canvasMarkdown'
import { Plugin, TextSelection } from '@tiptap/pm/state'
import { closeHistory } from '@tiptap/pm/history'
import {
  advanceImmersiveMenu,
  IMMERSIVE_MENU_KEY,
  openImmersiveMenu,
  type ImmersiveMenuTarget,
} from '../utils/editor/immersiveCommands'

function setup(content = '') {
  const editor = new Editor({
    element: null,
    extensions: [StarterKit],
    content: importCanvasMarkdown(content),
  })
  editor.registerPlugin(
    new Plugin<ImmersiveMenuTarget | null>({
      key: IMMERSIVE_MENU_KEY,
      state: { init: () => null, apply: advanceImmersiveMenu },
    }),
    (plugin) => [plugin, ...editor.extensionManager.plugins],
  )
  return editor
}
const menu = (editor: Editor) => IMMERSIVE_MENU_KEY.getState(editor.state)
const type = (editor: Editor, value: string) =>
  editor.view.dispatch(editor.state.tr.insertText(value))

describe('斜杠命令只跟随本次主动输入', () => {
  it('第七个查询字符关闭菜单，删除回六个字符也不重开', () => {
    const editor = setup()
    try {
      type(editor, '/')
      expect(menu(editor)?.query).toBe('')
      type(editor, 'abcdef')
      expect(menu(editor)?.query).toBe('abcdef')
      type(editor, 'g')
      expect(menu(editor)).toBeNull()
      editor.view.dispatch(editor.state.tr.delete(8, 9))
      expect(menu(editor)).toBeNull()
      expect(editor.state.doc.textContent).toBe('/abcdef')
    } finally {
      editor.destroy()
    }
  })
  it('六字符限制按 Unicode 字符计数，中文查询正常', () => {
    const editor = setup()
    try {
      type(editor, '/')
      type(editor, '标题列表😀六')
      expect(menu(editor)?.query).toBe('标题列表😀六')
      type(editor, '七')
      expect(menu(editor)).toBeNull()
      expect(editor.state.doc.textContent).toBe('/标题列表😀六七')
    } finally {
      editor.destroy()
    }
  })
  it('关闭后光标经过已有斜杠、继续输入和删除都不重新打开', () => {
    const editor = setup()
    try {
      type(editor, '/')
      editor.view.dispatch(editor.state.tr.setMeta(IMMERSIVE_MENU_KEY, null))
      editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, 1)))
      editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, 2)))
      type(editor, 'abc')
      expect(menu(editor)).toBeNull()
      editor.view.dispatch(editor.state.tr.delete(2, 5))
      expect(menu(editor)).toBeNull()
      type(editor, 'd')
      expect(menu(editor)).toBeNull()
      expect(editor.state.doc.textContent).toBe('/d')
    } finally {
      editor.destroy()
    }
  })
  it('旧斜杠只定位不打开，编辑其后文字也不打开；重新输入斜杠可打开', () => {
    const editor = setup('/old')
    try {
      editor.commands.setTextSelection(2)
      expect(menu(editor)).toBeNull()
      type(editor, 'x')
      expect(menu(editor)).toBeNull()
      editor.commands.selectAll()
      type(editor, '/')
      expect(menu(editor)?.query).toBe('')
    } finally {
      editor.destroy()
    }
  })
  it('粘贴和撤销恢复的斜杠保留为文本', () => {
    const editor = setup()
    try {
      editor.view.dispatch(editor.state.tr.insertText('/pasted').setMeta('uiEvent', 'paste'))
      expect(menu(editor)).toBeNull()
      editor.view.dispatch(closeHistory(editor.state.tr))
      editor.commands.selectAll()
      type(editor, 'text')
      editor.commands.undo()
      expect(editor.state.doc.textContent).toBe('/pasted')
      expect(menu(editor)).toBeNull()
    } finally {
      editor.destroy()
    }
  })
  it('列表软换行后的新斜杠仍可触发，加号显式打开不受查询长度限制', () => {
    const editor = setup('1. 外层')
    try {
      editor.commands.setTextSelection(5)
      editor.commands.setHardBreak()
      type(editor, '/')
      expect(menu(editor)?.kind).toBe('slash')
      expect(menu(editor)?.query).toBe('')
      openImmersiveMenu(editor, 2)
      expect(menu(editor)?.kind).toBe('line')
    } finally {
      editor.destroy()
    }
  })
})
