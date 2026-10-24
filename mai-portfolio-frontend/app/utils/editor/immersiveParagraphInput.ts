import { TextSelection } from '@tiptap/pm/state'
import { Extension, type Command } from '@tiptap/core'

/** 文末空白点击复用已有末尾空段，否则添加一段，保留正文与原生撤销。 */
export const continueImmersiveDocument: Command = ({ editor, tr }) => {
  if (!editor.isEditable) {
    return false
  }
  const last = tr.doc.lastChild
  if (last?.type.name !== 'paragraph' || last.content.size) {
    tr.insert(tr.doc.content.size, tr.doc.type.schema.nodes.paragraph!.create())
  }
  tr.setSelection(TextSelection.create(tr.doc, tr.doc.content.size - 1))
    .setStoredMarks(null)
    .scrollIntoView()
  return true
}

/** 在文字中间拆段保留格式；在段末结束格式，单元格导航由目标位置决定格式。 */
export const newImmersiveParagraph: Command = ({ editor, commands, tr }) => {
  if (editor.isActive('codeBlock')) {
    return false
  }
  const atEnd =
    tr.selection.empty && tr.selection.$from.parentOffset === tr.selection.$from.parent.content.size
  const inTable = editor.isActive('table')
  const changed = inTable
    ? commands.goToNextCell()
    : commands.first([
        () => commands.splitListItem('listItem'),
        () => commands.splitListItem('taskItem'),
        () => commands.createParagraphNear(),
        () => commands.liftEmptyBlock(),
        () => commands.splitBlock({ keepMarks: !atEnd }),
      ])
  if (changed) {
    if (inTable) {
      // GFM 单元格不支持新段落；进入下一格末尾，不能用选中整格的默认 Tab 状态覆盖原内容。
      tr.setSelection(TextSelection.near(tr.doc.resolve(tr.selection.to)))
    } else if (atEnd) {
      // 新空段落从自身推导输入格式，不把上一段显式 storedMarks 带过来。
      tr.setStoredMarks(null)
    }
  }
  return changed
}
export const ImmersiveParagraphInput = Extension.create<{ notice: (message: string) => void }>({
  name: 'immersiveParagraphInput',
  priority: 1100,
  addOptions() {
    return { notice: () => {} }
  },
  addKeyboardShortcuts() {
    return {
      Enter: () => {
        const changed = this.editor.commands.command(newImmersiveParagraph)
        if (!changed && this.editor.isActive('table')) {
          this.options.notice(
            '已在最后一个单元格。GFM 单元格仅支持一段内容，可通过表格边界添加行。',
          )
          return true
        }
        return changed
      },
      'Shift-Enter': () => {
        if (this.editor.isActive('heading')) {
          this.options.notice('Markdown 标题不支持软换行，请按 Enter 新建正文段落。')
          return true
        }
        if (!this.editor.isActive('table')) {
          return this.editor.commands.setHardBreak()
        }
        this.options.notice('GFM 单元格仅支持一段内容，不能保存软换行；Enter 可进入下一单元格。')
        return true
      },
    }
  },
})
