import { getMarkRange, type Editor } from '@tiptap/core'
import { immersiveAxisSelection } from './immersiveSelection'
import { CellSelection } from '@tiptap/pm/tables'
import { PluginKey } from '@tiptap/pm/state'
export const IMMERSIVE_LINK_KEY = new PluginKey('immersive-link-popover')
export function openImmersiveLink(editor: Editor) {
  editor.view.dispatch(editor.state.tr.setMeta(IMMERSIVE_LINK_KEY, { open: true }))
}
export function immersiveLinkRange(editor: Editor, position?: number) {
  const selection = editor.state.selection
  if (
    position === undefined &&
    (selection instanceof CellSelection ||
      immersiveAxisSelection(editor.state) ||
      !selection.$from.sameParent(selection.$to))
  ) {
    return null
  }
  const resolved = editor.state.doc.resolve(position ?? selection.from)
  const mark = editor.schema.marks.link
  const existing = mark ? getMarkRange(resolved, mark) : undefined
  const range =
    existing && (selection.empty || position !== undefined)
      ? existing
      : { from: selection.from, to: selection.to }
  if (range.from === range.to) {
    return null
  }
  return {
    ...range,
    href: existing
      ? String(
          editor.state.doc.nodeAt(existing.from)?.marks.find((item) => item.type === mark)?.attrs
            .href ??
            editor.getAttributes('link').href ??
            '',
        )
      : '',
  }
}
