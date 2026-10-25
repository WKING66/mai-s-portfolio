import { Extension, type Editor, type NodeViewRenderer, type NodeViewProps } from '@tiptap/core'
import { VueNodeViewRenderer, type VueNodeViewRendererOptions } from '@tiptap/vue-3'
import type { Component } from 'vue'
import type { Node } from '@tiptap/pm/model'
import { Plugin, PluginKey } from '@tiptap/pm/state'

interface Position {
  document?: Node
  position?: number
}
const positions = new WeakMap<Editor, Set<Position>>()
function entries(editor: Editor) {
  let records = positions.get(editor)
  if (!records) {
    records = new Set()
    positions.set(editor, records)
  }
  return records
}

/** getPos 的原生计算会遍历前序兄弟；选区通知中重复读取时复用已映射的位置。 */
export function trackCanvasNodePosition(editor: Editor, original: () => number | undefined) {
  const records = entries(editor)
  const record: Position = {}
  records.add(record)
  return {
    getPos() {
      if (record.document !== editor.state.doc || record.position === undefined) {
        record.position = original()
        record.document = editor.state.doc
      }
      return record.position
    },
    dispose: () => {
      records.delete(record)
    },
  }
}

export const CanvasNodePositions = Extension.create({
  name: 'canvasNodePositions',
  addProseMirrorPlugins() {
    const records = entries(this.editor)
    return [
      new Plugin({
        key: new PluginKey('canvas-node-positions'),
        state: {
          init: () => null,
          apply(transaction) {
            if (transaction.docChanged) {
              for (const record of records) {
                if (record.document === transaction.before && record.position !== undefined) {
                  const mapped = transaction.mapping.mapResult(record.position, 1)
                  record.position = mapped.deleted ? undefined : mapped.pos
                  record.document = transaction.doc
                } else {
                  record.document = undefined
                }
              }
            }
            return null
          },
        },
      }),
    ]
  },
})

/** 仅包装公开 getPos/销毁接口，不替换 Vue NodeView 的编辑、选区或拖拽行为。 */
export function canvasNodeViewRenderer(
  component: Component<NodeViewProps>,
  options?: Partial<VueNodeViewRendererOptions>,
): NodeViewRenderer {
  const render = VueNodeViewRenderer(component, options)
  return (props) => {
    const tracked = trackCanvasNodePosition(props.editor, props.getPos)
    const view = render({ ...props, getPos: tracked.getPos })
    const destroy = view.destroy
    view.destroy = () => {
      tracked.dispose()
      destroy?.call(view)
    }
    return view
  }
}
