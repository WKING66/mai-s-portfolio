import { Extension, type Editor } from '@tiptap/core'
import type { Node as DocumentNode } from '@tiptap/pm/model'
import { Plugin, type Transaction } from '@tiptap/pm/state'
import { ReplaceStep } from '@tiptap/pm/transform'
import { serializeEditorDocument } from './markdownOutput'

export interface ImmersiveRejection {
  reason: string
  position: number
}

/** GFM 单元格只允许一段行内内容，不能接受 Tiptap 默认的 block+ 嵌套能力。 */
export function immersiveDocumentProblem(document: DocumentNode): ImmersiveRejection | undefined {
  let problem: ImmersiveRejection | undefined
  document.descendants((node, position) => {
    if (problem) {
      return false
    }
    if (node.type.name === 'tableCell' || node.type.name === 'tableHeader') {
      if (node.childCount !== 1 || node.firstChild?.type.name !== 'paragraph') {
        problem = {
          reason:
            'Markdown 表格单元格只能包含一段行内内容；不能插入代码、列表或独立公式等块级内容。',
          position,
        }
      } else if (node.attrs.colspan !== 1 || node.attrs.rowspan !== 1) {
        problem = { reason: 'GFM 表格无法保存合并单元格，请在源码中保留复杂表格。', position }
      }
    }
    return !problem
  })
  return problem
}

export function immersiveInsertionProblem(editor: Editor, kind: string): string {
  if (
    editor.isActive('table') &&
    !['paragraph', 'mark', 'link', 'mathInline', 'undo', 'redo'].includes(kind)
  ) {
    return '当前单元格不支持此块级操作，请在表格外插入。'
  }
  return ''
}

/** 未变块保留已验证的原生节点；严格检查变更块及两侧边界，完整往返仍在后台保存前执行。 */
export function validateImmersiveChange(
  editor: Editor,
  transaction: Transaction,
): string | undefined {
  const next = transaction.doc
  const start = transaction.before.content.findDiffStart(next.content)
  if (start === null) {
    return
  }
  const end = transaction.before.content.findDiffEnd(next.content)!
  const first = Math.max(0, next.resolve(start).index(0) - 1)
  const last = Math.min(next.childCount, next.resolve(end.b).index(0) + 2)
  const wholeDocument = first === 0 && last === next.childCount
  const affected = wholeDocument
    ? next
    : next.type.create(
        next.attrs,
        Array.from({ length: last - first }, (_, index) => next.child(first + index)),
      )
  const markdown = serializeEditorDocument(editor, affected)
  // 只有覆盖全文的结果可以直接成为 Markdown 草稿，片段结果绝不写入全文。
  return wholeDocument ? markdown : undefined
}

/** 入口禁用之外的最后一道事务门禁：拒绝粘贴、拖入等无法无损保存的结构。 */
export const ImmersiveMarkdownGuard = Extension.create<{
  reject: (failure: ImmersiveRejection) => void
  roundtrip: boolean
}>({
  name: 'immersiveMarkdownGuard',
  addOptions() {
    return { reject: () => {}, roundtrip: true }
  },
  addProseMirrorPlugins() {
    const editor = this.editor
    return [
      new Plugin({
        filterTransaction: (transaction) => {
          if (!transaction.docChanged || transaction.getMeta('immersive-source')) {
            return true
          }
          let failure = immersiveDocumentProblem(transaction.doc)
          // 普通同段输入只改变文本，不在每个按键上全文序列化；结构/属性事务才进行严格往返。
          const plainText = transaction.steps.every((step, index) => {
            if (!(step instanceof ReplaceStep) || step.slice.openStart || step.slice.openEnd) {
              return false
            }
            let textOnly = true
            step.slice.content.forEach((node) => {
              textOnly &&= node.isText
            })
            const before = transaction.docs[index]!
            return (
              textOnly &&
              before.resolve(step.from).sameParent(before.resolve(step.to)) &&
              before.resolve(step.from).parent.isTextblock
            )
          })
          if (plainText) {
            transaction.setMeta('immersive-plain-text', true)
          }
          if (!failure && !plainText && this.options.roundtrip) {
            try {
              const markdown = validateImmersiveChange(editor, transaction)
              if (markdown !== undefined) {
                transaction.setMeta('immersive-markdown', markdown)
              }
            } catch (cause) {
              const node = transaction.doc.resolve(
                Math.min(transaction.selection.from, transaction.doc.content.size),
              ).parent
              failure = {
                reason:
                  '节点 ' +
                  node.type.name +
                  ' 无法安全往返：' +
                  (cause instanceof Error ? cause.message : '结构不受支持'),
                position: transaction.selection.from,
              }
            }
          }
          if (!failure) {
            return true
          }
          const rejected = failure
          const documentVersion = editor.state.doc
          queueMicrotask(() => {
            if (!editor.isDestroyed && editor.state.doc === documentVersion) {
              this.options.reject(rejected)
            }
          })
          return false
        },
      }),
    ]
  },
})
