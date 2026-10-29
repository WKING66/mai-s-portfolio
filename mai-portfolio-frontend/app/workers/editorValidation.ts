import {
  validateCanvasMarkdown,
  type CanvasValidationInput,
  type CanvasValidationReply,
} from '../utils/editor/markdown/validation'

const validate = validateCanvasMarkdown
self.onmessage = (event: MessageEvent<{ id: number; input: CanvasValidationInput }>) => {
  const { id, input } = event.data
  let reply: CanvasValidationReply
  try {
    reply = { id, ok: true, value: validate(input) }
  } catch (cause) {
    reply = {
      id,
      ok: false,
      error: cause instanceof Error ? cause.message : 'Markdown 校验失败，当前输入已保留。',
    }
  }
  self.postMessage(reply)
}
