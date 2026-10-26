import type { Editor } from '@tiptap/core'
import { closeHistory } from '@tiptap/pm/history'

/** 异步格式化只写回仍与原快照一致的这个代码块；移动可跟随，修改/删除/改语言则拒绝覆盖。 */
export function applyFormattedCode(
  editor: Editor,
  position: number | undefined,
  snapshot: { source: string; info: string },
  formatted: string,
) {
  if (editor.isDestroyed || !editor.isEditable || typeof position !== 'number') {
    return false
  }
  const node = editor.state.doc.nodeAt(position)
  if (
    node?.type.name !== 'codeBlock' ||
    node.textContent !== snapshot.source ||
    String(node.attrs.language || '') !== snapshot.info
  ) {
    return false
  }
  if (formatted !== snapshot.source) {
    const transaction = closeHistory(editor.state.tr).insertText(
      formatted,
      position + 1,
      position + node.nodeSize - 1,
    )
    editor.view.dispatch(transaction)
    editor.view.dispatch(closeHistory(editor.state.tr).setMeta('addToHistory', false))
  }
  return true
}
