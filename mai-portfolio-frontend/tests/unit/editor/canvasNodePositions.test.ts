import { describe, expect, it, vi } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { CanvasNodePositions, trackCanvasNodePosition } from '../../../app/utils/editor/session/canvasNodePositions'

function setup() {
  const editor = new Editor({
    element: null,
    extensions: [StarterKit, CanvasNodePositions],
    content: {
      type: 'doc',
      content: ['前段', '目标', '末段'].map((text) => ({
        type: 'paragraph',
        content: [{ type: 'text', text }],
      })),
    },
  })
  editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
  const original = vi.fn(() => {
    let result: number | undefined
    editor.state.doc.forEach((node, position) => {
      if (node.textContent === '目标') {
        result = position
      }
    })
    return result
  })
  const tracked = trackCanvasNodePosition(editor, original)
  return { editor, original, tracked }
}
describe('复杂节点位置缓存跟随真实事务映射', () => {
  it('普通连续输入与选区移动不重复遍历前序兄弟，位置保持准确', () => {
    const { editor, original, tracked } = setup()
    try {
      expect(tracked.getPos()).toBe(4)
      for (let index = 0; index < 15; index++) {
        editor.view.dispatch(editor.state.tr.insertText('字', 1))
        for (let read = 0; read < 10; read++) {
          expect(tracked.getPos()).toBe(5 + index)
        }
      }
      editor.commands.setTextSelection(2)
      expect(tracked.getPos()).toBe(19)
      expect(original).toHaveBeenCalledTimes(1)
    } finally {
      tracked.dispose()
      editor.destroy()
    }
  })
  it('删除、撤销和重做后不返回落到其他块上的旧位置', () => {
    const { editor, tracked } = setup()
    try {
      expect(tracked.getPos()).toBe(4)
      editor.view.dispatch(editor.state.tr.delete(4, 8))
      expect(tracked.getPos()).toBeUndefined()
      editor.commands.undo()
      expect(tracked.getPos()).toBe(4)
      editor.commands.redo()
      expect(tracked.getPos()).toBeUndefined()
    } finally {
      tracked.dispose()
      editor.destroy()
    }
  })
  it('块移动的删除/插入映射失效时，重新读取真实位置', () => {
    const { editor, tracked } = setup()
    try {
      expect(tracked.getPos()).toBe(4)
      const target = editor.state.doc.nodeAt(4)!
      const transaction = editor.state.tr.delete(4, 8)
      transaction.insert(transaction.doc.content.size, target)
      editor.view.dispatch(transaction)
      expect(tracked.getPos()).toBe(8)
      editor.commands.undo()
      expect(tracked.getPos()).toBe(4)
    } finally {
      tracked.dispose()
      editor.destroy()
    }
  })
})
