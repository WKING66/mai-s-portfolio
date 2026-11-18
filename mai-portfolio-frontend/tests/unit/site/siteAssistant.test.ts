import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, shallowRef } from 'vue'
import type { createAgentChatApi } from '../../../app/api/agentChat'
import { useSiteAssistant } from '../../../app/composables/useSiteAssistant'
import { ANONYMOUS_CONVERSATION_KEY } from '../../../app/utils/site/assistantStorage'

type ChatApi = ReturnType<typeof createAgentChatApi>
const lifecycle = vi.hoisted(() => ({
  mount: undefined as (() => Promise<void>) | undefined,
  unmount: undefined as (() => void) | undefined,
}))
const api = vi.hoisted(() => ({
  status: vi.fn<ChatApi['status']>(),
  claim: vi.fn<ChatApi['claim']>(),
  create: vi.fn<ChatApi['create']>(),
  list: vi.fn<ChatApi['list']>(),
  read: vi.fn<ChatApi['read']>(),
  submit: vi.fn<ChatApi['submit']>(),
  resume: vi.fn<ChatApi['resume']>(),
  stop: vi.fn<ChatApi['stop']>(),
}))
vi.mock('vue', async (original) => ({
  ...(await original<typeof import('vue')>()),
  onMounted: (callback: () => Promise<void>) => {
    lifecycle.mount = callback
  },
  onBeforeUnmount: (callback: () => void) => {
    lifecycle.unmount = callback
  },
}))
vi.mock('../../../app/api/agentChat', async (original) => ({
  ...(await original<typeof import('../../../app/api/agentChat')>()),
  createAgentChatApi: () => api,
}))
vi.mock('../../../app/composables/useAuthState', () => ({
  useAuthState: () => ({ state: session, ensureSession: async () => undefined }),
}))
const session = shallowRef({ loggedIn: false, username: '', csrfToken: 'csrf' })
let scope = effectScope()
let values = new Map<string, string>()
function setup() {
  scope = effectScope()
  return scope.run(useSiteAssistant)!
}
beforeEach(() => {
  vi.resetAllMocks()
  session.value = { loggedIn: false, username: '', csrfToken: 'csrf' }
  values = new Map()
  vi.stubGlobal('localStorage', {
    get length() {
      return values.size
    },
    key: (index: number) => [...values.keys()][index] ?? null,
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  })
  api.status.mockResolvedValue({ enabled: true })
  api.claim.mockResolvedValue({ conversationId: null })
  api.create.mockResolvedValue({ conversationId: '12' })
  api.list.mockResolvedValue({ items: [], nextCursor: null })
  api.read.mockResolvedValue({ turns: [] })
})
afterEach(() => {
  lifecycle.unmount?.()
  scope.stop()
  vi.unstubAllGlobals()
})

describe('站点聊天会话恢复', () => {
  it('发送与完整流结束后只存匿名 ID', async () => {
    api.submit.mockImplementation(async (_id, body, receive) => {
      receive({
        kind: 'accepted',
        value: { requestId: body.requestId, executionId: '27', status: 'accepted' },
      })
      receive({
        kind: 'terminal',
        value: {
          requestId: body.requestId,
          executionId: '27',
          status: 'completed',
          text: 'server answer',
        },
      })
    })
    const chat = setup()
    await lifecycle.mount?.()
    await chat.send('private question')
    expect(chat.latest.value?.text).toBe('server answer')
    expect([...values.entries()]).toEqual([[ANONYMOUS_CONVERSATION_KEY, '12']])
  })

  it('浏览器存储被限制时仍可完成本页问答', async () => {
    vi.stubGlobal('localStorage', {
      get length() {
        throw new Error('storage denied')
      },
    })
    api.submit.mockImplementation(async (_id, body, receive) => {
      receive({
        kind: 'terminal',
        value: {
          requestId: body.requestId,
          executionId: '27',
          status: 'completed',
          text: 'answer',
        },
      })
    })
    const chat = setup()
    await lifecycle.mount?.()
    await chat.send('question')
    expect(chat.latest.value?.text).toBe('answer')
    expect(chat.error.value).toBe('')
  })

  it('登录后按原编号读取服务端归属，成功后移除匿名编号', async () => {
    values.set(ANONYMOUS_CONVERSATION_KEY, '12')
    api.claim.mockResolvedValue({ conversationId: '12' })
    session.value = { loggedIn: true, username: 'alice', csrfToken: 'csrf' }
    const chat = setup()
    await lifecycle.mount?.()
    expect(api.claim).toHaveBeenCalledOnce()
    expect(api.read).toHaveBeenCalledWith('12')
    expect(api.claim.mock.invocationCallOrder[0]).toBeLessThan(
      api.read.mock.invocationCallOrder[0]!,
    )
    expect(chat.conversationId.value).toBe('12')
    expect(values.size).toBe(0)
  })

  it('认领失败保留匿名编号，不读取账号或伪装迁移成功', async () => {
    values.set(ANONYMOUS_CONVERSATION_KEY, '12')
    session.value = { loggedIn: true, username: 'alice', csrfToken: 'csrf' }
    api.claim.mockRejectedValue(new Error('服务暂不可用'))
    const chat = setup()
    await lifecycle.mount?.()
    expect(values.get(ANONYMOUS_CONVERSATION_KEY)).toBe('12')
    expect(chat.error.value).toBe('服务暂不可用')
    expect(api.read).not.toHaveBeenCalled()
  })

  it('迟到的停止响应只中止原轮次，下一轮继续输出', async () => {
    type Receive = Parameters<ChatApi['submit']>[2]
    const complete = new Map<string, () => void>()
    const receivers = new Map<string, Receive>()
    const signals: AbortSignal[] = []
    const bodies: Parameters<ChatApi['submit']>[1][] = []
    api.submit.mockImplementation(async (_id, body, receive, signal) => {
      bodies.push(body)
      signals.push(signal)
      receivers.set(body.requestId, receive)
      receive({
        kind: 'accepted',
        value: { requestId: body.requestId, executionId: body.requestId, status: 'accepted' },
      })
      await new Promise<void>((resolve) => {
        complete.set(body.requestId, resolve)
      })
    })
    let stopped!: (value: Awaited<ReturnType<ChatApi['stop']>>) => void
    api.stop.mockReturnValue(
      new Promise((resolve) => {
        stopped = resolve
      }),
    )
    const chat = setup()
    await lifecycle.mount?.()
    const firstSending = chat.send('first')
    await vi.waitFor(() => expect(chat.canStop.value).toBe(true))
    const stopping = chat.stop()
    const first = bodies[0]!
    const firstFinal = {
      requestId: first.requestId,
      executionId: first.requestId,
      status: 'completed' as const,
      text: 'first answer',
    }
    receivers.get(first.requestId)!({ kind: 'terminal', value: firstFinal })
    complete.get(first.requestId)!()
    await firstSending
    const secondSending = chat.send('second')
    await vi.waitFor(() => expect(bodies).toHaveLength(2))
    stopped(firstFinal)
    await stopping
    expect(signals[1]!.aborted).toBe(false)
    const second = bodies[1]!
    receivers.get(second.requestId)!({
      kind: 'terminal',
      value: {
        requestId: second.requestId,
        executionId: second.requestId,
        status: 'completed',
        text: 'second answer',
      },
    })
    complete.get(second.requestId)!()
    await secondSending
    expect(chat.latest.value?.text).toBe('second answer')
  })

  it('账号刷新从服务端最近会话恢复，不写账号缓存', async () => {
    session.value = { loggedIn: true, username: 'alice', csrfToken: 'csrf' }
    api.list.mockResolvedValue({
      items: [{ conversationId: '19', updatedAt: '2026-11-18T00:00:00Z' }],
      nextCursor: null,
    })
    const chat = setup()
    await lifecycle.mount?.()
    expect(chat.conversationId.value).toBe('19')
    expect(values.size).toBe(0)
  })

  it('恢复中的点击不会提交新问答', async () => {
    values.set(ANONYMOUS_CONVERSATION_KEY, '12')
    let finish!: (value: { turns: [] }) => void
    api.read.mockReturnValue(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const chat = setup()
    const mounting = lifecycle.mount?.()
    await vi.waitFor(() => expect(chat.restoring.value).toBe(true))
    await chat.send('new question')
    expect(api.submit).not.toHaveBeenCalled()
    finish({ turns: [] })
    await mounting
    expect(chat.restoring.value).toBe(false)
  })

  it('A 切到 B 再切回 A 时，第一次 A 的迟到回包不能覆盖新会话', async () => {
    session.value = { loggedIn: true, username: 'alice', csrfToken: 'csrf' }
    const chat = setup()
    await lifecycle.mount?.()
    let finish!: (value: { turns: [] }) => void
    api.read.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const oldRead = chat.open('old')
    session.value = { ...session.value, username: 'bob' }
    await nextTick()
    session.value = { ...session.value, username: 'alice' }
    await nextTick()
    await chat.open('new')
    finish({ turns: [] })
    await oldRead
    expect(chat.conversationId.value).toBe('new')
  })
})
