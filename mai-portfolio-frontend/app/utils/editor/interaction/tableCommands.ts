import type { Command } from '@tiptap/core'

/** GFM 仅支持按列对齐；新插入的行沿用表头，避免保存时默认空属性与列语义冲突。 */
export const inheritTableColumnAlignment: Command = ({ tr }) => {
  const position = tr.selection.$from
  let depth = position.depth
  while (depth > 0 && position.node(depth).type.name !== 'table') {
    depth -= 1
  }
  if (!depth) {
    return false
  }
  const table = position.node(depth)
  const header = table.firstChild
  if (!header) {
    return false
  }
  const start = position.start(depth)
  table.forEach((row, rowOffset) => {
    row.forEach((cell, cellOffset, column) => {
      const alignment = header.maybeChild(column)?.attrs.align
      if (cell.attrs.align === null && alignment) {
        tr.setNodeMarkup(start + rowOffset + 1 + cellOffset, undefined, {
          ...cell.attrs,
          align: alignment,
        })
      }
    })
  })
  return true
}
