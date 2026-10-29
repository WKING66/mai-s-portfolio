import type { JSONContent } from '@tiptap/core'
import { exportCanvasMarkdown, importCanvasMarkdown } from './canvasMarkdown'
import type { DocumentResource } from '../../../api/blogs'

export type CanvasValidationInput =
  | { kind: 'canvas-import'; markdown: string; resources: DocumentResource[] }
  | { kind: 'canvas'; document: JSONContent }
export type CanvasValidationResult =
  { kind: 'canvas-import'; document: JSONContent } | { kind: 'canvas'; markdown: string }
export type CanvasValidationReply = { id: number } & (
  { ok: true; value: CanvasValidationResult } | { ok: false; error: string }
)
export interface CanvasValidator {
  check(input: CanvasValidationInput): Promise<CanvasValidationResult>
  dispose(): void
}

/** Worker 与定向测试使用正式画布的同一套导入、导出及完整语义校验。 */
export function validateCanvasMarkdown(input: CanvasValidationInput): CanvasValidationResult {
  if (input.kind === 'canvas-import') {
    return {
      kind: 'canvas-import',
      document: importCanvasMarkdown(input.markdown, input.resources),
    }
  }
  return { kind: 'canvas', markdown: exportCanvasMarkdown(input.document) }
}
