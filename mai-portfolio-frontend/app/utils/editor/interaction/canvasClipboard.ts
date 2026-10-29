import type { Editor, JSONContent } from '@tiptap/core'
import { Plugin } from '@tiptap/pm/state'
import type { Slice } from '@tiptap/pm/model'
import type { DocumentResource } from '../../../api/blogs'
import { createImmersiveValidator } from '../markdown/immersiveValidationClient'

/** 识别需要完整 Markdown 解析的剪贴板；普通富文本、内部块复制和表格粘贴走原有流程。 */
export function isCanvasMarkdownClipboard(text: string) {
  return /(?:^|\n) {0,3}(?:#{1,6}(?:\s|$)|`{3,}|~{3,}|[-+*>]\s|\d+[.)]\s|\$\$|\\\[)|\|.*\||\$[^\n$]+\$|\\\([^\n]*\\\)|\*[^\n*]+\*|_[^\n_]+_|~~|!?\[[^\n]*\]\(/.test(
    text,
  )
}

interface PasteOptions {
  resources: () => DocumentResource[]
  notice: (message: string) => void
  pendingChanged?: (pending: boolean) => void
  parse?: (text: string) => Promise<JSONContent>
}

export function canvasMarkdownClipboard(editor: Editor, options: PasteOptions) {
  let validator: ReturnType<typeof createImmersiveValidator> | undefined
  let pending: { from: number; to: number; selected: Slice } | undefined
  let plain = false
  let destroyed = false
  let generation = 0
  const parse =
    options.parse ??
    (async (markdown: string) => {
      validator ??= createImmersiveValidator()
      const result = await validator.check({
        kind: 'canvas-import',
        markdown,
        // 投影字段均为标量；解开 Vue 的数组/对象代理后才能发送给 Worker。
        resources: options.resources().map((resource) => ({ ...resource })),
      })
      if (result.kind !== 'canvas-import') {
        throw new Error('粘贴解析结果不匹配。')
      }
      return result.document
    })
  return new Plugin({
    state: {
      init: () => null,
      apply(transaction) {
        if (pending && transaction.docChanged) {
          const empty = pending.from === pending.to
          pending.from = transaction.mapping.map(pending.from, empty ? -1 : 1)
          pending.to = empty ? pending.from : transaction.mapping.map(pending.to, -1)
        }
        return null
      },
    },
    props: {
      handleDOMEvents: {
        keydown(_view, event) {
          plain =
            event.key.toLowerCase() === 'v' && event.shiftKey && (event.ctrlKey || event.metaKey)
          return false
        },
        keyup() {
          plain = false
          return false
        },
      },
      handlePaste(view, event) {
        const asPlain = plain
        plain = false
        const clipboard = event.clipboardData
        if (
          !editor.isEditable ||
          !clipboard ||
          clipboard.files.length ||
          editor.isActive('codeBlock') ||
          editor.isActive('table')
        ) {
          return false
        }
        const text = clipboard.getData('text/plain')
        if (!text || (!asPlain && clipboard.getData('text/html').includes('data-pm-slice='))) {
          return false
        }
        if (!asPlain && !isCanvasMarkdownClipboard(text)) {
          return false
        }
        event.preventDefault()
        if (asPlain) {
          if (
            !editor.commands.insertContentAt(
              view.state.selection,
              text.split(/\r?\n/).map((line) => ({
                type: 'paragraph',
                ...(line ? { content: [{ type: 'text', text: line, marks: [] }] } : {}),
              })),
            )
          ) {
            options.notice('当前位置不能插入纯文本，当前内容未改变。')
          }
          return true
        }
        if (pending) {
          options.notice('上一段 Markdown 仍在解析，请稍后再次粘贴。')
          return true
        }
        const { from, to } = view.state.selection
        const job = { from, to, selected: view.state.doc.slice(from, to) }
        const jobGeneration = generation
        pending = job
        options.pendingChanged?.(true)
        const beforeDocument = view.state.doc
        const beforeSelection = view.state.selection
        void parse(text)
          .then((document) => {
            if (destroyed || pending !== job) {
              return
            }
            pending = undefined
            if (job.from > job.to || !view.state.doc.slice(job.from, job.to).eq(job.selected)) {
              throw new Error('粘贴期间选中内容已变化，未覆盖新输入，请重新粘贴。')
            }
            const follow =
              view.state.doc === beforeDocument && view.state.selection.eq(beforeSelection)
            if (
              !editor.commands.insertContentAt(
                { from: job.from, to: job.to },
                document.content ?? [],
                { updateSelection: follow },
              )
            ) {
              throw new Error('当前位置不能插入这段 Markdown，当前内容未改变。')
            }
          })
          .catch((cause) => {
            if (!destroyed && generation === jobGeneration) {
              options.notice(
                cause instanceof Error ? cause.message : 'Markdown 粘贴失败，当前内容未改变。',
              )
            }
          })
          .finally(() => {
            if (pending === job) {
              pending = undefined
            }
            if (!destroyed && generation === jobGeneration) {
              options.pendingChanged?.(Boolean(pending))
            }
          })
        return true
      },
    },
    view: () => {
      // 工具栏注册插件时 PM 会重建 PluginView；复用的 Plugin 仍要支持下一次粘贴。
      destroyed = false
      return {
        destroy() {
          destroyed = true
          generation++
          pending = undefined
          options.pendingChanged?.(false)
          validator?.dispose()
          validator = undefined
        },
      }
    },
  })
}
