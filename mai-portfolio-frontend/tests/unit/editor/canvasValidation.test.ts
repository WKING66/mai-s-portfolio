import { afterEach, expect, it, vi } from 'vitest'
import { createCanvasValidator } from '../../../app/utils/editor/markdown/validationClient'
import {
  validateCanvasMarkdown,
  type CanvasValidationInput,
  type CanvasValidationReply,
} from '../../../app/utils/editor/markdown/validation'
afterEach(() => vi.unstubAllGlobals())
it('Worker 运输只保留最新待执行任务，故障拒绝请求且不回退到主线程', async () => {
  const transports: Transport[] = []
  class Transport {
    onmessage: (event: MessageEvent<CanvasValidationReply>) => void = () => {}
    onerror = () => {}
    onmessageerror = () => {}
    sent: { id: number; input: CanvasValidationInput }[] = []
    terminate = vi.fn()
    constructor() {
      transports.push(this)
    }
    postMessage(message: { id: number; input: CanvasValidationInput }) {
      this.sent.push(message)
    }
  }
  vi.stubGlobal('Worker', Transport)
  const client = createCanvasValidator()
  const first = client.check({ kind: 'canvas-import', markdown: 'A', resources: [] })
  const superseded = client
    .check({ kind: 'canvas-import', markdown: 'B', resources: [] })
    .catch((error: Error) => error.message)
  const latest = client
    .check({ kind: 'canvas-import', markdown: 'C', resources: [] })
    .catch((error: Error) => error.message)
  const transport = transports[0]!
  expect(transport.sent).toHaveLength(1)
  const request = transport.sent[0]!
  transport.onmessage(
    new MessageEvent('message', {
      data: { id: request.id, ok: true, value: validateCanvasMarkdown(request.input) },
    }),
  )
  await first
  expect(await superseded).toContain('替代')
  expect(transport.sent).toHaveLength(2)
  expect(transport.sent[1]!.input).toMatchObject({ markdown: 'C' })
  transport.onerror()
  expect(await latest).toContain('后台校验不可用')
  await expect(
    client.check({ kind: 'canvas-import', markdown: 'D', resources: [] }),
  ).rejects.toThrow('后台校验不可用')
  expect(transport.terminate).toHaveBeenCalledTimes(1)
  client.dispose()
})
