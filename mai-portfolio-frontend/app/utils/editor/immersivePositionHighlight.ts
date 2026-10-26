import { Extension } from '@tiptap/core'
import { Plugin, PluginKey } from '@tiptap/pm/state'
import { Decoration, DecorationSet } from '@tiptap/pm/view'

export const IMMERSIVE_POSITION_KEY = new PluginKey<DecorationSet>('immersive-position')
/** 导航提醒仅装饰普通段落/标题，不重建代码、图表、表格或公式 NodeView。 */
export const ImmersivePositionHighlight = Extension.create({
  name: 'immersivePositionHighlight',
  addProseMirrorPlugins() {
    return [
      new Plugin({
        key: IMMERSIVE_POSITION_KEY,
        state: {
          init: () => DecorationSet.empty,
          apply(transaction, previous) {
            const range = transaction.getMeta(IMMERSIVE_POSITION_KEY)
            if (range === null) {
              return DecorationSet.empty
            }
            if (range) {
              const node = transaction.doc.nodeAt(range.from)
              if (
                node &&
                ['paragraph', 'heading'].includes(node.type.name) &&
                !node.children.some((child) => child.isAtom && !child.isText)
              ) {
                return DecorationSet.create(transaction.doc, [
                  Decoration.node(range.from, range.from + node.nodeSize, {
                    class: 'immersive-position-flash',
                  }),
                ])
              }
              return DecorationSet.empty
            }
            return previous.map(transaction.mapping, transaction.doc)
          },
        },
        props: { decorations: (state) => IMMERSIVE_POSITION_KEY.getState(state) },
      }),
    ]
  },
})
