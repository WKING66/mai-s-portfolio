import type { JSONContent } from '@tiptap/core'
import type { CanvasSaveAdapter } from '../../composables/useCanvasSave'

export interface CanvasMetadata {
  title: string
  slug: string
  summary: string
  allowDownload: boolean
  remoteRightsConfirmed: boolean
  tagIds?: number[]
}
export interface CanvasSaveSnapshot {
  document: JSONContent
  metadata: CanvasMetadata
  markdown?: string
}

/** Showcase 内存端口。标识始终为模拟保存，不写后端、OSS 或本机恢复存储。 */
export function createCanvasMockAdapter(
  fail: () => boolean = () => false,
): CanvasSaveAdapter<CanvasSaveSnapshot> & { readonly snapshot: CanvasSaveSnapshot | undefined } {
  let lastSaved: CanvasSaveSnapshot | undefined
  return {
    get snapshot() {
      return lastSaved ? structuredClone(lastSaved) : undefined
    },
    async save(value) {
      await new Promise((resolve) => setTimeout(resolve, 300))
      if (fail()) {
        throw new Error('模拟保存失败，请重试；当前正文未丢失。')
      }
      if (typeof value.markdown !== 'string') {
        throw new Error('正文尚未通过 Markdown 校验。')
      }
      lastSaved = structuredClone(value)
    },
  }
}
