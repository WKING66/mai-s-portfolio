import {
  createImmersiveValidation,
  type ImmersiveValidationInput,
  type ImmersiveValidationReply,
} from './immersiveValidation'

const validate = createImmersiveValidation()
self.onmessage = (event: MessageEvent<{ id: number; input: ImmersiveValidationInput }>) => {
  const { id, input } = event.data
  let reply: ImmersiveValidationReply
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
