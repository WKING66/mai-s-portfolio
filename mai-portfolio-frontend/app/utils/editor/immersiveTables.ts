import type { Command } from '@tiptap/core'
import { Fragment, type Node as DocumentNode } from '@tiptap/pm/model'
import { TextSelection, type Selection } from '@tiptap/pm/state'
import { moveTableRow, moveTableColumn, CellSelection, TableMap } from '@tiptap/pm/tables'
import {
  immersiveAxisSelection,
  immersiveSelectedCells,
  IMMERSIVE_SELECTION_KEY,
  type ImmersiveAxisSelection,
} from './immersiveSelection'
import { inheritTableColumnAlignment } from './tableCommands'

/** GFM 对齐是列属性；多选单元格时统一调整所覆盖列，不能伪装成任意单格样式。 */
export function alignImmersiveColumns(align: 'left' | 'center' | 'right'): Command {
  return ({ tr, state }) => {
    const resolved = tr.selection.$from
    let depth = resolved.depth
    while (depth > 0 && resolved.node(depth).type.name !== 'table') {
      depth--
    }
    if (!depth) {
      return false
    }
    const table = resolved.node(depth)
    const start = resolved.start(depth)
    const columns = new Set<number>()
    const cells = immersiveSelectedCells(state)
    if (cells.length) {
      const map = TableMap.get(table)
      cells.forEach((position) => columns.add(map.findCell(position - start).left))
    } else {
      columns.add(resolved.index(depth + 1))
    }
    table.forEach((row, rowOffset) =>
      row.forEach((cell, cellOffset, column) => {
        if (columns.has(column)) {
          tr.setNodeMarkup(start + rowOffset + 1 + cellOffset, undefined, { ...cell.attrs, align })
        }
      }),
    )
    return true
  }
}
export function immersiveTableContext(document: DocumentNode, selection: Selection) {
  const resolved = selection.$from
  let depth = resolved.depth
  while (depth > 0 && resolved.node(depth).type.name !== 'table') {
    depth--
  }
  if (!depth) {
    return null
  }
  const table = resolved.node(depth)
  const start = resolved.start(depth)
  let cellPosition = selection instanceof CellSelection ? selection.$anchorCell.pos : -1
  for (let child = resolved.depth; child > depth; child--) {
    if (['tableCell', 'tableHeader'].includes(resolved.node(child).type.name)) {
      cellPosition = resolved.before(child)
      break
    }
  }
  if (cellPosition < start) {
    return null
  }
  const map = TableMap.get(table)
  const cell = map.findCell(cellPosition - start)
  return { table, start, position: resolved.before(depth), map, row: cell.top, column: cell.left }
}
export function selectImmersiveAxis(axis: 'row' | 'column', index: number): Command {
  return ({ tr }) => {
    const context = immersiveTableContext(tr.doc, tr.selection)
    if (!context) {
      return false
    }
    const { map, start } = context
    if (index < 0 || index >= (axis === 'row' ? map.height : map.width)) {
      return false
    }
    const anchor = axis === 'row' ? index * map.width : index
    const head = axis === 'row' ? anchor + map.width - 1 : (map.height - 1) * map.width + index
    tr.setSelection(
      axis === 'row'
        ? CellSelection.rowSelection(
            tr.doc.resolve(start + map.map[anchor]!),
            tr.doc.resolve(start + map.map[head]!),
          )
        : CellSelection.colSelection(
            tr.doc.resolve(start + map.map[anchor]!),
            tr.doc.resolve(start + map.map[head]!),
          ),
    )
    return true
  }
}
export type ImmersiveTableChange =
  'rowBefore' | 'rowAfter' | 'columnBefore' | 'columnAfter' | 'deleteRow' | 'deleteColumn'
export function changeImmersiveTable(change: ImmersiveTableChange): Command {
  return (props) => {
    const { tr, commands } = props
    const context = immersiveTableContext(tr.doc, tr.selection)
    if (!context || (context.row === 0 && ['rowBefore', 'deleteRow'].includes(change))) {
      return false
    }
    const actions = {
      rowBefore: commands.addRowBefore,
      rowAfter: commands.addRowAfter,
      columnBefore: commands.addColumnBefore,
      columnAfter: commands.addColumnAfter,
      deleteRow: commands.deleteRow,
      deleteColumn: commands.deleteColumn,
    }
    if (!actions[change]()) {
      return false
    }
    if (change.startsWith('row')) {
      inheritTableColumnAlignment(props)
    }
    const table = tr.doc.nodeAt(context.position)
    if (table?.type.name !== 'table') {
      return true
    }
    const map = TableMap.get(table)
    // 新行/列直接定位到新单元格；删除后的光标也保持在仍存在的表格内。
    const row = Math.min(map.height - 1, context.row + (change === 'rowAfter' ? 1 : 0))
    const column = Math.min(map.width - 1, context.column + (change === 'columnAfter' ? 1 : 0))
    tr.setSelection(
      TextSelection.near(tr.doc.resolve(context.position + 2 + map.map[row * map.width + column]!)),
    )
    return true
  }
}
export function moveImmersiveAxis(axis: 'row' | 'column', from: number, to: number): Command {
  return ({ state, dispatch }) => {
    const context = immersiveTableContext(state.doc, state.selection)
    if (!context || from === to || from < 0 || to < 0 || (axis === 'row' && (!from || !to))) {
      return false
    }
    if (
      from >= (axis === 'row' ? context.map.height : context.map.width) ||
      to >= (axis === 'row' ? context.map.height : context.map.width)
    ) {
      return false
    }
    // 复用 ProseMirror 原生结构事务，不移动 DOM；首行始终保留为 GFM 表头。
    return (axis === 'row' ? moveTableRow : moveTableColumn)({
      from,
      to,
      pos: context.position + 1,
      select: true,
    })(state, dispatch)
  }
}
/** 工具栏根据实际选中文本显示，CellSelection 和非连续集合都不是例外。 */
export function isImmersiveTextSelection(selection: Selection) {
  if (selection instanceof CellSelection) {
    let hasText = false
    selection.forEachCell((cell) => {
      hasText ||= Boolean(cell.textContent.trim())
    })
    return hasText
  }
  return (
    selection instanceof TextSelection &&
    !selection.empty &&
    Boolean(selection.$from.doc.textBetween(selection.from, selection.to).trim())
  )
}
/** 原生整行/整列 CellSelection 与边缘追加集合使用同一结构操作边界。 */
export function selectedImmersiveAxes(
  state: import('@tiptap/pm/state').EditorState,
  axis?: 'row' | 'column',
): ImmersiveAxisSelection | null {
  const explicit = immersiveAxisSelection(state)
  if (explicit) {
    return !axis || explicit.axis === axis ? explicit : null
  }
  const selection = state.selection
  if (!(selection instanceof CellSelection)) {
    return null
  }
  const context = immersiveTableContext(state.doc, selection)
  if (!context) {
    return null
  }
  const kind = axis ?? (selection.isRowSelection() ? 'row' : 'column')
  if (kind === 'row' ? !selection.isRowSelection() : !selection.isColSelection()) {
    return null
  }
  const indices = new Set<number>()
  selection.forEachCell((_cell, position) => {
    const rect = context.map.findCell(position - context.start)
    indices.add(kind === 'row' ? rect.top : rect.left)
  })
  const ordered = [...indices].sort((a, b) => a - b)
  return { table: context.position, axis: kind, indices: ordered, anchor: ordered[0]! }
}
/** 清空严格使用真实目标单元格；逆序提交避免前面的内容删除改变后续位置。 */
export function clearImmersiveCells(): Command {
  return ({ state, tr }) => {
    const cells = immersiveSelectedCells(state)
    if (!cells.length) {
      return false
    }
    for (const position of cells.slice().reverse()) {
      const cell = state.doc.nodeAt(position)!
      tr.replaceWith(
        position + 1,
        position + cell.nodeSize - 1,
        state.schema.nodes.paragraph!.create(),
      )
    }
    return tr.docChanged
  }
}
export function immersiveAxisProblem(
  state: import('@tiptap/pm/state').EditorState,
  action: 'delete' | 'move',
  axis?: 'row' | 'column',
) {
  const selection = selectedImmersiveAxes(state, axis)
  const table = selection ? state.doc.nodeAt(selection.table) : null
  if (!selection || table?.type.name !== 'table') {
    return '请先选择需要操作的行或列。'
  }
  if (selection.axis === 'row' && selection.indices.includes(0)) {
    return 'Markdown 表头固定，不能删除或移动。'
  }
  const count = selection.axis === 'row' ? table.childCount : table.firstChild!.childCount
  const minimum = selection.axis === 'row' ? 2 : 1
  if (action === 'delete' && count - selection.indices.length < minimum) {
    return '至少保留一行数据和一列。'
  }
  return ''
}
export function editImmersiveAxes(
  action: 'delete' | 'clear' | 'move',
  boundary = 0,
  axis?: 'row' | 'column',
): Command {
  return ({ state, tr }) => {
    const selected = selectedImmersiveAxes(state, axis)
    if (!selected || (action !== 'clear' && immersiveAxisProblem(state, action, axis))) {
      return false
    }
    const table = tr.doc.nodeAt(selected.table)
    if (!table || table.type.name !== 'table') {
      return false
    }
    const count = selected.axis === 'row' ? table.childCount : table.firstChild!.childCount
    if (action === 'move' && (boundary < (selected.axis === 'row' ? 1 : 0) || boundary > count)) {
      return false
    }
    const indices = Array.from({ length: count }, (_, index) => index)
    const picked = indices.filter((index) => selected.indices.includes(index))
    let order = indices
    let nextIndices: number[] = []
    if (action !== 'clear') {
      order = indices.filter((index) => !selected.indices.includes(index))
      if (action === 'move') {
        // 插入目标是原表格边界；扣除目标前被移走的项目，集合始终保持原先相对顺序。
        const destination = boundary - picked.filter((index) => index < boundary).length
        order.splice(destination, 0, ...picked)
        nextIndices = picked.map((_value, offset) => destination + offset)
        if (order.every((value, index) => value === index)) {
          return false
        }
      }
    }
    const rows = table.children.map((row, rowIndex) => {
      let cells = row.children
      if (action === 'clear') {
        cells = cells.map((cell, columnIndex) => {
          if (selected.indices.includes(selected.axis === 'row' ? rowIndex : columnIndex)) {
            return cell.copy(Fragment.from(state.schema.nodes.paragraph!.create()))
          }
          return cell
        })
      } else if (selected.axis === 'column') {
        cells = order.map((index) => cells[index]!)
      }
      return row.copy(Fragment.fromArray(cells))
    })
    const resultRows =
      action !== 'clear' && selected.axis === 'row' ? order.map((index) => rows[index]!) : rows
    const next = table.copy(Fragment.fromArray(resultRows))
    tr.replaceWith(selected.table, selected.table + table.nodeSize, next)
    tr.setSelection(TextSelection.near(tr.doc.resolve(selected.table + 4)))
    tr.setMeta(
      IMMERSIVE_SELECTION_KEY,
      action === 'move' || action === 'clear'
        ? {
            ...selected,
            indices: action === 'move' ? nextIndices : selected.indices,
            anchor: nextIndices[0] ?? selected.anchor,
          }
        : null,
    )
    return true
  }
}
