import { createImmersiveMarkdownManager } from '../markdown/immersiveMarkdown'
import type { Editor, JSONContent } from '@tiptap/core'
import { inspectBlogMarkdown } from '../markdown/markdownCompatibility'

/** 原文未闭合围栏时保留最后有效画布，不能让旧画布覆盖正在输入的源码。 */
export function inspectImmersiveSource(
  source: string,
  manager = createImmersiveMarkdownManager(),
  allowedLocalResources: ReadonlySet<string> = new Set(),
): string {
  const tokens = manager.instance.lexer(source)
  let incompleteFence = false
  manager.instance.walkTokens(tokens, (token) => {
    if (token.type !== 'code') {
      return
    }
    const opening = token.raw?.match(/^ {0,3}([~\x60]{3,})[^\n]*\n/)
    if (!opening) {
      return
    }
    const marker = opening[1]![0]!
    const closing = new RegExp('^ {0,3}' + marker + '{' + opening[1]!.length + ',}[ \\t]*$', 'm')
    incompleteFence ||= !closing.test(token.raw!.slice(opening[0].length))
  })
  if (incompleteFence) {
    return '代码围栏尚未闭合。源码已保留，画布暂时只读并显示上一次有效内容。'
  }
  return inspectBlogMarkdown(source, manager, allowedLocalResources)
}

/** 差异范围提交单个事务；不 setContent、不替换整篇或重建实例。 */
export function patchImmersiveDocument(editor: Editor, source: string, prepared?: JSONContent): void {
  if (!editor.markdown) {
    throw new Error('Markdown 解析尚未就绪。')
  }
  const parsed = editor.schema.nodeFromJSON(prepared ?? editor.markdown.parse(source))
  const next = parsed.childCount ? parsed : editor.schema.topNodeType.createAndFill()
  if (!next) {
    throw new Error('不能恢复文档结构，源码保持不变。')
  }
  next.check()
  const previous = editor.state.doc
  const start = previous.content.findDiffStart(next.content)
  if (start === null) {
    return
  }
  const end = previous.content.findDiffEnd(next.content)!
  const overlap = Math.max(0, start - Math.min(end.a, end.b))
  const transaction = editor.state.tr
  const sameBlocks =
    previous.childCount === next.childCount &&
    Array.from(
      { length: previous.childCount },
      (_, index) => previous.child(index).type === next.child(index).type,
    ).every(Boolean)
  if (sameBlocks) {
    // 同时编辑相距很远的源码块时，逐块逆序提交，避免中间未改节点丢失身份、主题和交互状态。
    let position = previous.content.size
    for (let index = previous.childCount - 1; index >= 0; index--) {
      const before = previous.child(index)
      const after = next.child(index)
      position -= before.nodeSize
      if (!before.eq(after)) {
        if (before.type.name === 'codeBlock') {
          // 代码源码只补内部差异并更新属性，保留 NodeView、主题身份与滚动位置。
          const innerStart = before.content.findDiffStart(after.content)
          if (innerStart !== null) {
            const innerEnd = before.content.findDiffEnd(after.content)!
            const innerOverlap = Math.max(0, innerStart - Math.min(innerEnd.a, innerEnd.b))
            transaction.replace(
              position + 1 + innerStart,
              position + 1 + innerEnd.a + innerOverlap,
              after.slice(innerStart, innerEnd.b + innerOverlap),
            )
          }
          for (const [name, value] of Object.entries(after.attrs)) {
            if (JSON.stringify(before.attrs[name]) !== JSON.stringify(value)) {
              transaction.setNodeAttribute(position, name, value)
            }
          }
        } else {
          transaction.replaceWith(position, position + before.nodeSize, after)
        }
      }
    }
  } else {
    transaction.replace(start, end.a + overlap, next.slice(start, end.b + overlap))
  }
  if (!transaction.doc.eq(next)) {
    throw new Error('本次结构变化无法安全局部应用，源码已保留，请完成当前结构后重试。')
  }
  editor.view.dispatch(transaction.setMeta('immersive-source', true))
}
