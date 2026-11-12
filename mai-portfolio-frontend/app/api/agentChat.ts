import { CSRF_HEADER } from '../constants/auth'

const base = '/api/agent/v1'

export type TurnStatus = 'pending' | 'accepted' | 'running' | 'completed' | 'failed' | 'interrupted'

export interface ChatSnapshot {
  requestId: string
  executionId: string | null
  status: TurnStatus
  text: string
}

export interface SavedTurn extends ChatSnapshot {
  question: string
}

export interface ConversationSummary {
  conversationId: string
  updatedAt: string
}

type ChatEvent =
  | { kind: 'accepted'; value: Pick<ChatSnapshot, 'requestId' | 'executionId' | 'status'> }
  | { kind: 'snapshot' | 'terminal'; value: ChatSnapshot }
  | { kind: 'delta'; value: { offset: number; text: string } }

export class AgentChatError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message)
  }
}

export function createAgentChatApi(csrfToken: () => string) {
  async function request(path: string, method = 'GET', body?: unknown, signal?: AbortSignal) {
    const headers = new Headers()
    if (method !== 'GET') {
      headers.set('Content-Type', 'application/json')
      headers.set(CSRF_HEADER, csrfToken())
    }
    const response = await fetch(base + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'same-origin',
      signal,
    })
    if (!response.ok) {
      const error = await response.json().catch(() => null)
      const message = typeof error?.message === 'string' ? error.message : '模型服务暂不可用。'
      throw new AgentChatError(message, response.status)
    }
    return response
  }

  async function json<T>(path: string, method?: string, body?: unknown): Promise<T> {
    const response = await request(path, method, body)
    const envelope = await response.json()
    if (envelope.code !== 'OK' || !envelope.data) {
      throw new AgentChatError('数字人服务响应不完整。', response.status)
    }
    return envelope.data
  }

  async function events(
    path: string,
    receive: (event: ChatEvent) => void,
    signal: AbortSignal,
    body?: { requestId: string; question: string },
  ) {
    const response = await request(path, body ? 'POST' : 'GET', body, signal)
    if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) {
      throw new AgentChatError('未能连接回答流。', response.status)
    }
    const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
    let buffered = ''
    let terminal = false
    try {
      for (;;) {
        const chunk = await reader.read()
        if (chunk.done) {
          break
        }
        buffered += chunk.value
        let boundary = /\r?\n\r?\n/.exec(buffered)
        while (boundary) {
          const block = buffered.slice(0, boundary.index)
          buffered = buffered.slice(boundary.index + boundary[0].length)
          const lines = block.split(/\r?\n/)
          const kind = lines
            .find((line) => line.startsWith('event:'))
            ?.slice(6)
            .trim()
          const data = lines
            .filter((line) => line.startsWith('data:'))
            .map((line) => line.slice(5).trimStart())
            .join('\n')
          if (
            data &&
            (kind === 'accepted' || kind === 'snapshot' || kind === 'terminal' || kind === 'delta')
          ) {
            receive({ kind, value: JSON.parse(data) })
            terminal ||= kind === 'terminal'
          }
          boundary = /\r?\n\r?\n/.exec(buffered)
        }
      }
      if (!terminal) {
        throw new AgentChatError('连接中断，可继续读取本轮。', 0)
      }
    } finally {
      await reader.cancel().catch(() => undefined)
      reader.releaseLock()
    }
  }

  return {
    status: () => json<{ enabled: boolean }>('/status'),
    create: () => json<{ conversationId: string }>('/conversations', 'POST', {}),
    list: (cursor?: string) =>
      json<{ items: ConversationSummary[]; nextCursor: string | null }>(
        cursor ? `/conversations?cursor=${encodeURIComponent(cursor)}` : '/conversations',
      ),
    read: (id: string) => json<{ turns: SavedTurn[] }>(`/conversations/${id}`),
    submit: (
      id: string,
      body: { requestId: string; question: string },
      receive: (event: ChatEvent) => void,
      signal: AbortSignal,
    ) => events(`/conversations/${id}/turns`, receive, signal, body),
    resume: (id: string, receive: (event: ChatEvent) => void, signal: AbortSignal) =>
      events(`/executions/${id}/events`, receive, signal),
    stop: (id: string) => json<ChatSnapshot>(`/executions/${id}/stop`, 'POST', {}),
    import: (importId: string, turns: { question: string; answer: string }[]) =>
      json<{ conversationId: string }>('/conversations/import', 'POST', { importId, turns }),
  }
}
