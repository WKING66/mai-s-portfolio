import { afterEach, describe, expect, it, vi } from 'vitest'
import { AgentChatError, createAgentChatApi } from '../../../app/api/agentChat'

const requestId = '41e842ae-242a-4e73-86ad-8734bbef38e5'
const executionId = 'a2a070b1-2eb5-427f-90a8-5376a8f2f234'
const receipt = { requestId, executionId, status: 'accepted' }
const final = { ...receipt, status: 'completed', text: '收到' }

function stream(...chunks: string[]) {
  const encoder = new TextEncoder()
  return new Response(
    new ReadableStream({
      start(controller) {
        for (const chunk of chunks) {
          controller.enqueue(encoder.encode(chunk))
        }
        controller.close()
      },
    }),
    { headers: { 'content-type': 'text/event-stream' } },
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('网站聊天流', () => {
  it('一次发送同时读取受理、快照、分块增量和终态', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        stream(
          `event: accepted\r\ndata: ${JSON.stringify(receipt)}\r\n\r\n`,
          `event: snapshot\ndata: ${JSON.stringify({ ...receipt, text: '' })}\n\n`,
          'event: del',
          'ta\ndata: {"offset":0,"text":"收到"}\n\n',
          `event: terminal\ndata: ${JSON.stringify(final)}\n\n`,
        ),
      )
    vi.stubGlobal('fetch', fetcher)
    const events: string[] = []
    const api = createAgentChatApi(() => 'csrf')
    await api.submit(
      'conversation',
      { requestId, question: '测试' },
      (event) => events.push(event.kind),
      new AbortController().signal,
    )
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(fetcher.mock.calls[0]?.[0]).toBe('/api/agent/v1/conversations/conversation/turns')
    expect(fetcher.mock.calls[0]?.[1].method).toBe('POST')
    expect(events).toEqual(['accepted', 'snapshot', 'delta', 'terminal'])
  })

  it('无终态的断流仍可按同一执行编号订阅恢复', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(stream(`event: accepted\ndata: ${JSON.stringify(receipt)}\n\n`))
      .mockResolvedValueOnce(
        stream(
          `event: snapshot\ndata: ${JSON.stringify(final)}\n\n`,
          `event: terminal\ndata: ${JSON.stringify(final)}\n\n`,
        ),
      )
    vi.stubGlobal('fetch', fetcher)
    const api = createAgentChatApi(() => '')
    const signal = new AbortController().signal
    await expect(
      api.submit('conversation', { requestId, question: '测试' }, () => {}, signal),
    ).rejects.toBeInstanceOf(AgentChatError)
    await api.resume(executionId, () => {}, signal)
    expect(fetcher.mock.calls[1]?.[0]).toBe(`/api/agent/v1/executions/${executionId}/events`)
    expect(fetcher.mock.calls[1]?.[1].method).toBe('GET')
  })
})
