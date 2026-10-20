import { TextSelection } from '@tiptap/pm/state'
import { Extension, type Command } from '@tiptap/core'

/** Enter 新建段落时重置输入样式；原段落和 Shift+Enter 的 Mark 不受影响。 */
export const newImmersiveParagraph: Command = ({ editor, commands, tr }) => {
  if (editor.isActive('codeBlock')) {
    return false
  }
  const changed = editor.isActive('table')
    ? commands.goToNextCell()
    : commands.first([
        () => commands.splitListItem('listItem'),
        () => commands.splitListItem('taskItem'),
        () => commands.createParagraphNear(),
        () => commands.liftEmptyBlock(),
        () => commands.splitBlock({ keepMarks: false }),
      ])
  if (changed) {
    // 只重置下一次输入的状态，不移除文档内已经设置的样式。
    if (editor.isActive('table')) {
      // GFM 单元格不支持新段落；进入下一格末尾，不能用选中整格的默认 Tab 状态覆盖原内容。
      tr.setSelection(TextSelection.near(tr.doc.resolve(tr.selection.to)))
    }
    tr.setStoredMarks([])
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
