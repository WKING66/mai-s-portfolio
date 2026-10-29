import type { JSONContent } from '@tiptap/core'
import type { createCanvasResources } from './canvasResources'

export interface CanvasSaveAdapter<T> {
  save(snapshot: T): Promise<void> | Promise<'unchanged' | undefined>
}
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

export interface CanvasPersistence {
  adapter: CanvasSaveAdapter<CanvasSaveSnapshot>
  resources: ReturnType<typeof createCanvasResources>
  saveProperties: (metadata: CanvasMetadata) => Promise<void>
}
