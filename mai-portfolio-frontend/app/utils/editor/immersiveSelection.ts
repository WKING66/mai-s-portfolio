import { Extension, type Command } from '@tiptap/core'
import { Plugin, PluginKey, TextSelection, type EditorState } from '@tiptap/pm/state'
import { CellSelection, TableMap } from '@tiptap/pm/tables'
import { Decoration, DecorationSet } from '@tiptap/pm/view'

export interface ImmersiveAxisSelection {
  table: number
  axis: 'row' | 'column'
  indices: number[]
  anchor: number
}
export const IMMERSIVE_SELECTION_KEY = new PluginKey<ImmersiveAxisSelection | null>(
  'immersive-axis-selection',
)
export function immersiveAxisSelection(state: EditorState) {
  return IMMERSIVE_SELECTION_KEY.getState(state) ?? null
}
export function immersiveSelectedCells(state: EditorState): number[] {
  const axes = immersiveAxisSelection(state)
  if (axes) {
    const table = state.doc.nodeAt(axes.table)
    if (table?.type.name !== 'table') {
      return []
    }
    const map = TableMap.get(table)
    const positions = new Set<number>()
    for (let row = 0; row < map.height; row++) {
      for (let column = 0; column < map.width; column++) {
        if (axes.indices.includes(axes.axis === 'row' ? row : column)) {
          positions.add(axes.table + 1 + map.map[row * map.width + column]!)
        }
      }
    }
    return [...positions].sort((a, b) => a - b)
  }
  const positions: number[] = []
  if (state.selection instanceof CellSelection) {
    state.selection.forEachCell((_cell, position) => positions.push(position))
  }
  return positions.sort((a, b) => a - b)
}
/** 三种选区统一归一为真实文本范围；公式和图片是原子节点，不参与文本 Mark 修改。 */
export function immersiveTextRanges(state: EditorState) {
  const ranges: { from: number; to: number }[] = []
  const cells = immersiveSelectedCells(state)
  if (cells.length) {
    for (const position of cells) {
      state.doc.nodeAt(position)?.descendants((node, offset) => {
        if (node.isText && node.text?.length) {
          ranges.push({ from: position + 1 + offset, to: position + 1 + offset + node.nodeSize })
        }
      })
    }
  } else if (state.selection instanceof TextSelection && !state.selection.empty) {
    const { from, to } = state.selection
    state.doc.nodesBetween(from, to, (node, position) => {
      if (node.isText) {
        ranges.push({ from: Math.max(from, position), to: Math.min(to, position + node.nodeSize) })
      }
    })
  }
  return ranges.filter(
    (range) => range.to > range.from && state.doc.textBetween(range.from, range.to).trim(),
  )
}
export function immersiveMarkActive(state: EditorState, name: string) {
  const mark = state.schema.marks[name]
  const ranges = immersiveTextRanges(state)
  return Boolean(
    mark &&
    ranges.length &&
    ranges.every((range) => state.doc.rangeHasMark(range.from, range.to, mark)),
  )
}
export function formatImmersiveText(
  name: 'bold' | 'italic' | 'strike' | 'code' | 'clear',
): Command {
  return ({ state, tr }) => {
    const ranges = immersiveTextRanges(state)
    if (!ranges.length) {
      return false
    }
    const mark = state.schema.marks[name]
    const remove = name === 'clear' || immersiveMarkActive(state, name)
    for (const range of ranges) {
      if (name === 'clear') {
        // 清除文字样式，保留链接身份和公式源码；链接有独立的移除入口。
        for (const kind of ['bold', 'italic', 'strike', 'code']) {
          const type = state.schema.marks[kind]
          if (type) {
            tr.removeMark(range.from, range.to, type)
          }
        }
      } else if (mark && tr.doc.resolve(range.from).parent.type.allowsMarkType(mark)) {
        if (remove) {
          tr.removeMark(range.from, range.to, mark)
        } else {
          tr.addMark(range.from, range.to, mark.create())
        }
      }
    }
    return tr.docChanged
  }
}
export function selectImmersiveAxes(
  tablePosition: number,
  axis: 'row' | 'column',
  index: number,
  mode: 'replace' | 'extend' | 'toggle' = 'replace',
): Command {
  return ({ state, tr }) => {
    const table = tr.doc.nodeAt(tablePosition)
    if (table?.type.name !== 'table') {
      return false
    }
    const map = TableMap.get(table)
    if (index < 0 || index >= (axis === 'row' ? map.height : map.width)) {
      return false
    }
    const previous = immersiveAxisSelection(state)
    const same = previous?.table === tablePosition && previous.axis === axis
    let indices = [index]
    let anchor = index
    if (same && mode === 'extend') {
      anchor = previous.anchor
      indices = Array.from(
        { length: Math.abs(index - anchor) + 1 },
        (_, offset) => Math.min(index, anchor) + offset,
      )
    } else if (same && mode === 'toggle') {
      anchor = previous.anchor
      indices = previous.indices.includes(index)
        ? previous.indices.filter((value) => value !== index)
        : [...previous.indices, index]
    }
    indices.sort((a, b) => a - b)
    if (!indices.length) {
      tr.setMeta(IMMERSIVE_SELECTION_KEY, null)
      tr.setSelection(
        TextSelection.near(
          tr.doc.resolve(tablePosition + 3 + map.map[axis === 'row' ? index * map.width : index]!),
        ),
      )
      return true
    }
    // 原生 CellSelection 只锚定集合内一格；非连续集合由插件精确维护，绝不扩成包围矩形。
    const cell =
      tablePosition + 1 + map.map[axis === 'row' ? indices[0]! * map.width : indices[0]!]!
    tr.setSelection(new CellSelection(tr.doc.resolve(cell)))
    tr.setMeta(IMMERSIVE_SELECTION_KEY, { table: tablePosition, axis, indices, anchor })
    return true
  }
}
export const ImmersiveSelection = Extension.create({
  name: 'immersiveSelection',
  priority: 1100,
  addKeyboardShortcuts() {
    return {
      'Mod-b': () => this.editor.commands.command(formatImmersiveText('bold')),
      'Mod-i': () => this.editor.commands.command(formatImmersiveText('italic')),
      'Mod-Shift-x': () => this.editor.commands.command(formatImmersiveText('strike')),
      'Mod-e': () => this.editor.commands.command(formatImmersiveText('code')),
    }
  },
  addProseMirrorPlugins() {
    return [
      new Plugin<ImmersiveAxisSelection | null>({
        key: IMMERSIVE_SELECTION_KEY,
        state: {
          init: () => null,
          apply(tr, previous) {
            const explicit = tr.getMeta(IMMERSIVE_SELECTION_KEY)
            if (explicit !== undefined) {
              return explicit
            }
            if (!previous || tr.selectionSet) {
              return null
            }
            const table = tr.mapping.map(previous.table)
            if (tr.doc.nodeAt(table)?.type.name !== 'table') {
              return null
            }
            return { ...previous, table }
          },
        },
        props: {
          decorations(state) {
            const axes = immersiveAxisSelection(state)
            if (!axes) {
              return null
            }
            return DecorationSet.create(
              state.doc,
              immersiveSelectedCells(state).map((position) => {
                const map = TableMap.get(state.doc.nodeAt(axes.table)!)
                const cell = map.findCell(position - axes.table - 1)
                let edge = ''
                if (axes.axis === 'column' && cell.top === 0) {
                  edge = ' immersive-column-edge'
                }
                if (axes.axis === 'row' && cell.left === 0) {
                  edge = ' immersive-row-edge'
                }
                return Decoration.node(position, position + state.doc.nodeAt(position)!.nodeSize, {
                  class: 'selectedCell immersive-table-selected' + edge,
                })
              }),
            )
          },
          handleDOMEvents: {
            mouseup(view, event) {
              if (view.state.selection instanceof CellSelection) {
                // 原生拖选结束后将浏览器选区收束到模型；避免 DOMObserver 用旧 DOM 光标覆盖矩形选区。
                event.preventDefault()
                view.focus()
              }
              return false
            },
          },
          handleKeyDown(view, event) {
            if (
              event.key === 'Escape' &&
              (immersiveAxisSelection(view.state) || view.state.selection instanceof CellSelection)
            ) {
              view.dispatch(
                view.state.tr
                  .setSelection(
                    TextSelection.near(view.state.doc.resolve(view.state.selection.from + 1)),
                  )
                  .setMeta(IMMERSIVE_SELECTION_KEY, null),
              )
              return true
            }
            return false
          },
        },
      }),
    ]
  },
})
