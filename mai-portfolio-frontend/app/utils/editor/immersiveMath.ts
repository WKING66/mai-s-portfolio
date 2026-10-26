import type { Command } from '@tiptap/core'
import { Selection, TextSelection } from '@tiptap/pm/state'

/** 仅在输入首/末行越界时回到正文；中间行和连续选区保持原生 textarea 行为。 */
export function mathExitDirection(value: string, start: number, end: number, key: string) {
  if (start !== end) {
    return
  }
  if (
    (key === 'ArrowLeft' && start === 0) ||
    (key === 'ArrowUp' && !value.slice(0, start).includes('\n'))
  ) {
    return -1
  }
  if (
    (key === 'ArrowRight' && end === value.length) ||
    (key === 'ArrowDown' && !value.slice(end).includes('\n'))
  ) {
    return 1
  }
}

export function exitImmersiveMath(position: number, direction: -1 | 1): Command {
  return ({ tr, editor }) => {
    const node = tr.doc.nodeAt(position)
    if (!editor.isEditable || !node?.type.name.startsWith('preservedMath')) {
      return false
    }
    const boundary = direction < 0 ? position : position + node.nodeSize
    const resolved = tr.doc.resolve(boundary)
    const selection = Selection.findFrom(resolved, direction, true)
    if (selection) {
      tr.setSelection(selection).scrollIntoView()
      return true
    }
    // 文首/文末没有可编辑文字时，显式离开公式才添加正文，不在初始化中制造空段。
    const paragraph = tr.doc.type.schema.nodes.paragraph!
    if (!resolved.parent.canReplaceWith(resolved.index(), resolved.index(), paragraph)) {
      return false
    }
    tr.insert(boundary, paragraph.create())
      .setSelection(TextSelection.create(tr.doc, boundary + 1))
      .setStoredMarks(null)
      .scrollIntoView()
    return true
  }
}

/** 复用已有数学分隔符，不引入新的持久化语法。 */
export function immersiveMathSource(original: string, expression: string, block: boolean): string {
  let delimiters = block ? ['$$', '$$'] : ['$', '$']
  if (original.startsWith('\\[')) {
    delimiters = ['\\[', '\\]']
  } else if (original.startsWith('\\(')) {
    delimiters = ['\\(', '\\)']
  }
  // 行内至少保留一个空白；现有编辑器与阅读解析器均支持这一表示。
  let content = expression
  if (block) {
    content = '\n' + expression + '\n'
  } else if (!expression.trim()) {
    content = ' '
  }
  return delimiters[0]! + content + delimiters[1]!
}
