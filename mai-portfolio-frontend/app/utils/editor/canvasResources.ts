import type { InjectionKey } from 'vue'
import type { Editor, JSONContent } from '@tiptap/core'

/** 只接收当前 Showcase 自己持有的预览地址，绝不放宽公开阅读器的 URL 规则。 */
export const CANVAS_RESOURCE_PREVIEW: InjectionKey<(uri: string) => string | undefined> =
  Symbol('canvas-resource-preview')
export const CANVAS_RESOURCE_NORMALIZATION = 'canvas-resource-normalization'
const PENDING_PREFIX = 'resource://showcase-pending-'
const IMAGE_TYPES = new Set([
  'image/png',
  'image/jpeg',
  'image/gif',
  'image/webp',
  'image/avif',
  'image/bmp',
])

interface LocalResource {
  file: File
  pending: string
  stable: string
  preview?: string
  ready: boolean
  preparing?: Promise<void>
}

function visitReferences(document: JSONContent, replace: (uri: string) => string) {
  if (document.type === 'image' && typeof document.attrs?.src === 'string') {
    document.attrs.src = replace(document.attrs.src)
  }
  for (const mark of document.marks ?? []) {
    if (mark.type === 'link' && typeof mark.attrs?.href === 'string') {
      mark.attrs.href = replace(mark.attrs.href)
    }
  }
  document.content?.forEach((child) => visitReferences(child, replace))
}

/** 仅模拟资源准备：不请求 API/OSS；两种引用和原文件都只活在本页面内。 */
export function createCanvasResources(fail: () => boolean = () => false) {
  const resources = new Map<string, LocalResource>()
  let disposed = false
  function add(file: File): JSONContent {
    if (disposed) {
      throw new Error('当前编辑页面已经关闭。')
    }
    const id = crypto.randomUUID()
    const image = IMAGE_TYPES.has(file.type)
    const entry: LocalResource = {
      file,
      pending: PENDING_PREFIX + id,
      stable: 'resource://showcase-' + id,
      preview: image ? URL.createObjectURL(file) : undefined,
      ready: false,
    }
    resources.set(entry.pending, entry)
    return image
      ? { type: 'image', attrs: { src: entry.pending, alt: file.name } }
      : {
          type: 'text',
          text: file.name || '附件',
          marks: [{ type: 'link', attrs: { href: entry.pending } }],
        }
  }
  function release(references: string[]) {
    for (const uri of references) {
      const entry = resources.get(uri)
      if (!entry) {
        continue
      }
      resources.delete(entry.pending)
      resources.delete(entry.stable)
      if (entry.preview) {
        URL.revokeObjectURL(entry.preview)
      }
    }
  }
  async function prepare(document: JSONContent) {
    if (disposed) {
      throw new Error('当前编辑页面已经关闭。')
    }
    const output = structuredClone(document)
    const pending = new Set<LocalResource>()
    visitReferences(output, (uri) => {
      const entry = resources.get(uri)
      if (entry) {
        pending.add(entry)
      } else if (uri.startsWith(PENDING_PREFIX)) {
        throw new Error('本地临时资源不属于当前页面，请重新插入文件；正文未保存。')
      }
      return uri
    })
    await Promise.all(
      [...pending].map(async (entry) => {
        if (entry.ready) {
          return
        }
        entry.preparing ??= (async () => {
          await new Promise((resolve) => setTimeout(resolve, 300))
          if (disposed) {
            throw new Error('当前编辑页面已经关闭。')
          }
          if (fail()) {
            throw new Error('模拟资源处理失败，请重试；文件与正文仍保留在当前页面。')
          }
          entry.ready = true
          resources.set(entry.stable, entry)
        })().finally(() => {
          entry.preparing = undefined
        })
        await entry.preparing
      }),
    )
    const replacements = new Map([...pending].map((entry) => [entry.pending, entry.stable]))
    visitReferences(output, (uri) => replacements.get(uri) ?? uri)
    return { document: output, replacements }
  }
  return {
    add,
    release,
    prepare,
    preview: (uri: string) => resources.get(uri)?.preview,
    dispose() {
      disposed = true
      release([...resources.keys()])
    },
  }
}

/** 只更新仍然存在的资源属性，不拿旧快照替换用户继续编辑的正文，不进入撤销历史。 */
export function normalizeCanvasResources(editor: Editor, replacements: Map<string, string>) {
  if (!replacements.size) {
    return
  }
  const before = editor.state.doc
  const transaction = editor.state.tr
  transaction.doc.descendants((node, position) => {
    const source = node.type.name === 'image' ? replacements.get(String(node.attrs.src)) : undefined
    if (source) {
      transaction.setNodeMarkup(position, undefined, { ...node.attrs, src: source }, node.marks)
    }
    for (const mark of node.marks) {
      const href = mark.type.name === 'link' ? replacements.get(String(mark.attrs.href)) : undefined
      if (href) {
        transaction.removeMark(position, position + node.nodeSize, mark)
        transaction.addMark(
          position,
          position + node.nodeSize,
          mark.type.create({ ...mark.attrs, href }),
        )
      }
    }
  })
  if (transaction.docChanged) {
    editor.view.dispatch(
      transaction.setMeta('addToHistory', false).setMeta(CANVAS_RESOURCE_NORMALIZATION, true),
    )
    if (editor.state.doc === before) {
      throw new Error('资源引用更新被编辑器拒绝，请重试；当前正文未保存。')
    }
  }
}

export function insertCanvasFiles(
  editor: Editor,
  resources: ReturnType<typeof createCanvasResources>,
  files: File[],
) {
  if (!editor.isEditable || editor.isActive('codeBlock') || !files.length) {
    return false
  }
  const content = files.flatMap((file, index) => {
    const node = resources.add(file)
    return index ? [{ type: 'text', text: ' ' }, node] : [node]
  })
  const references: string[] = []
  content.forEach((node) =>
    visitReferences(node, (uri) => {
      references.push(uri)
      return uri
    }),
  )
  try {
    const before = editor.state.doc
    const inserted = editor.commands.insertContent(content) && editor.state.doc !== before
    if (!inserted) {
      resources.release(references)
    }
    return inserted
  } catch (cause) {
    resources.release(references)
    throw cause
  }
}
