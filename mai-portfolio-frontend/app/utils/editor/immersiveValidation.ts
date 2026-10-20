import type { JSONContent } from '@tiptap/core'
import { createImmersiveMarkdownManager } from './immersiveMarkdown'
import { inspectImmersiveSource } from './immersiveDraft'
import { editorSchema } from './markdownCompatibility'
import { buildImmersiveSourceMap, type ImmersiveSourceRange } from './immersiveSourceMap'
import { serializeEditorDocument } from './markdownOutput'

export type ImmersiveValidationInput =
  | { kind: 'rich'; document: JSONContent }
  | { kind: 'source'; markdown: string; resources: string[] }
export type ImmersiveValidationResult =
  | { kind: 'rich'; markdown: string; ranges: ImmersiveSourceRange[] }
  | { kind: 'source'; issue: string; document?: JSONContent; ranges: ImmersiveSourceRange[] }
export type ImmersiveValidationReply = { id: number } & (
  { ok: true; value: ImmersiveValidationResult } | { ok: false; error: string }
)
export interface ImmersiveValidator {
  check(input: ImmersiveValidationInput): Promise<ImmersiveValidationResult>
  dispose(): void
}

/** 浏览器 Worker 和定向测试执行同一套真实 Markdown/Schema 校验。 */
export function createImmersiveValidation() {
  const manager = createImmersiveMarkdownManager()
  const parsedBlocks = new Map<string, JSONContent>()
  function ranges(source: string, document: Parameters<typeof buildImmersiveSourceMap>[1]) {
    try {
      return buildImmersiveSourceMap(source, document, manager, parsedBlocks)
    } catch {
      parsedBlocks.clear()
      return []
    }
  }
  return (input: ImmersiveValidationInput): ImmersiveValidationResult => {
    if (input.kind === 'rich') {
      const document = editorSchema.nodeFromJSON(input.document)
      document.check()
      const markdown = serializeEditorDocument(
        { markdown: manager, schema: editorSchema },
        document,
      )
      return { kind: 'rich', markdown, ranges: ranges(markdown, document) }
    }
    const issue = inspectImmersiveSource(input.markdown, manager, new Set(input.resources))
    if (issue) {
      return { kind: 'source', issue, ranges: [] }
    }
    const parsed = editorSchema.nodeFromJSON(manager.parse(input.markdown))
    const document = parsed.childCount ? parsed : editorSchema.topNodeType.createAndFill()!
    return {
      kind: 'source',
      issue: '',
      document: document.toJSON(),
      ranges: ranges(input.markdown, document),
    }
  }
}
