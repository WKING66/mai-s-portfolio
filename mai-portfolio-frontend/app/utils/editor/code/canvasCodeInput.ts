import type { Editor, NodeViewRenderer, NodeViewProps } from '@tiptap/core'
import type { Node } from '@tiptap/pm/model'
import type { InjectionKey } from 'vue'

export interface CanvasCodeInput {
  update(node: Node): void
  setSelection(anchor: number, head: number, focus?: boolean): void
  range(): { from: number; to: number } | undefined
  positionAtDOM(node: globalThis.Node, offset: number): number | null
  positionAtCoords(point: { x: number; y: number }): number | null
  domAtPosition(position: number): { node: globalThis.Node; offset: number } | null
  search(matches: { from: number; to: number; active: boolean }[]): void
  revealSearch(position: number): void
  setEditable(editable: boolean): void
  destroy(): void
}
export type CanvasCodeInputFactory = (host: HTMLElement, props: NodeViewProps) => CanvasCodeInput
/** 工作台提供局部代码输入实现；Mermaid 保留原生 contentDOM。 */
export const CANVAS_CODE_INPUT: InjectionKey<CanvasCodeInputFactory> = Symbol('canvas-code-input')
export const canvasCodeInputs = new WeakMap<HTMLElement, CanvasCodeInput>()
const inputEntries = new WeakMap<Editor, Set<HTMLElement>>()
export function registerCanvasCodeInput(editor: Editor, host: HTMLElement, input: CanvasCodeInput) {
  canvasCodeInputs.set(host, input)
  const entries = inputEntries.get(editor) ?? new Set<HTMLElement>()
  entries.add(host)
  inputEntries.set(editor, entries)
}
export function unregisterCanvasCodeInput(editor: Editor, host: HTMLElement) {
  canvasCodeInputs.delete(host)
  const entries = inputEntries.get(editor)
  entries?.delete(host)
  if (!entries?.size) {
    inputEntries.delete(editor)
  }
}
export function canvasCodeInputEntries(editor: Editor) {
  return [...(inputEntries.get(editor) ?? [])].flatMap((host) => {
    const input = canvasCodeInputs.get(host)
    return input ? [{ host, input }] : []
  })
}

/** 代码编辑器由 NodeView 管理；文章数据、撤销和块选区仍由 PM 事务持有。 */
export function canvasCodeInputRenderer(native: NodeViewRenderer): NodeViewRenderer {
  return (props) => {
    const nodeView = native(props)
    // EditorContent 尚未挂载时 Vue 返回空占位；交还 PM，待其正常重建真正的 NodeView。
    if (!nodeView.dom) {
      return nodeView
    }
    if (String(props.node.attrs.language || '').split(/\s/)[0] === 'mermaid') {
      const update = nodeView.update?.bind(nodeView)
      nodeView.update = (node, decorations, innerDecorations) =>
        String(node.attrs.language || '').split(/\s/)[0] === 'mermaid' &&
        Boolean(update?.(node, decorations, innerDecorations))
      return nodeView
    }
    const host = () => nodeView.dom.querySelector<HTMLElement>('[data-canvas-code-input]')
    const input = () => {
      const element = host()
      return element ? canvasCodeInputs.get(element) : undefined
    }
    Object.defineProperty(nodeView, 'contentDOM', { get: () => null })
    const update = nodeView.update?.bind(nodeView)
    nodeView.update = (node, decorations, innerDecorations) => {
      if (String(node.attrs.language || '').split(/\s/)[0] === 'mermaid') {
        return false
      }
      const accepted = update?.(node, decorations, innerDecorations) ?? false
      if (accepted) {
        input()?.update(node)
      }
      return accepted
    }
    nodeView.setSelection = (anchor, head) => input()?.setSelection(anchor, head)
    const stop = nodeView.stopEvent?.bind(nodeView)
    nodeView.stopEvent = (event) => {
      const element = host()
      return (
        Boolean(
          element && event.target instanceof globalThis.Node && element.contains(event.target),
        ) || Boolean(stop?.(event))
      )
    }
    const ignore = nodeView.ignoreMutation?.bind(nodeView)
    nodeView.ignoreMutation = (mutation) => {
      const element = host()
      if (element && element.contains(mutation.target)) {
        return true
      }
      return ignore?.(mutation) ?? false
    }

    const destroy = nodeView.destroy?.bind(nodeView)
    nodeView.destroy = () => {
      input()?.destroy()
      destroy?.()
    }
    return nodeView
  }
}
