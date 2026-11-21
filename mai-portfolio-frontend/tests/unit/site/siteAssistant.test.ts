import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, shallowRef } from 'vue'
import { AgentChatError, type createAgentChatApi } from '../../../app/api/agentChat'
import { useSiteAssistant } from '../../../app/composables/useSiteAssistant'
import {
  formatAssistantQuestion,
  prepareAssistantQuote,
} from '../../../app/utils/site/assistantQuestion'
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
  clearContent: vi.fn<ChatApi['clearContent']>(),
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

const oldTurn = {
  requestId: 'request',
  executionId: '27',
  question: '旧问题',
  text: '旧回复',
  status: 'completed',
} as const
const cleared = {
  conversationId: '12',
  title: '新会话',
  createdAt: '2026-11-18T00:00:00Z',
  turns: [],
}

describe('清空聊天内容', () => {
  it('更新当前历史标题并保留原会话供下一问使用', async () => {
    session.value = { loggedIn: true, username: 'alice', csrfToken: 'csrf' }
    api.list.mockResolvedValue({
      items: [{ conversationId: '12', title: '旧问题', updatedAt: cleared.createdAt }],
      nextCursor: null,
    })
    api.read.mockResolvedValue({ turns: [oldTurn] })
    api.clearContent.mockImplementation(async () => ({ ...cleared, turns: [] }))
    const chat = setup()
    await lifecycle.mount?.()

    expect(await chat.clearContent()).toBe(true)
    expect(api.clearContent).toHaveBeenCalledWith('12')
    expect(chat.turns.value).toEqual([])
    expect(chat.histories.value[0]?.title).toBe('新会话')

    await chat.send('下一问')
    expect(api.submit.mock.calls[0]?.[0]).toBe('12')
    expect(api.create).not.toHaveBeenCalled()
  })

  it('匿名会话清空后仍保留同一个续读编号', async () => {
    values.set(ANONYMOUS_CONVERSATION_KEY, '12')
    api.read.mockResolvedValue({ turns: [oldTurn] })
    api.clearContent.mockImplementation(async () => ({ ...cleared, turns: [] }))
    const chat = setup()
    await lifecycle.mount?.()
    expect(await chat.clearContent()).toBe(true)
    expect(chat.turns.value).toEqual([])
    expect(values.get(ANONYMOUS_CONVERSATION_KEY)).toBe('12')
  })

  it('服务端返回 409 时保留正文并提示先停止生成', async () => {
    values.set(ANONYMOUS_CONVERSATION_KEY, '12')
    api.read.mockResolvedValue({ turns: [oldTurn] })
    api.clearContent.mockRejectedValue(new AgentChatError('Conflict', 409))
    const chat = setup()
    await lifecycle.mount?.()

    await expect(chat.clearContent()).rejects.toThrow('请先停止生成')
    expect(chat.turns.value).toEqual([oldTurn])
    expect(chat.canClearContent.value).toBe(true)
  })

  it('跨登录状态的迟到清空响应不能覆盖新选中的会话', async () => {
    values.set(ANONYMOUS_CONVERSATION_KEY, '12')
    api.read.mockResolvedValueOnce({ turns: [oldTurn] })
    let complete!: (result: Awaited<ReturnType<ChatApi['clearContent']>>) => void
    api.clearContent.mockImplementation(
      () =>
        new Promise((resolve) => {
          complete = resolve
        }),
    )
    const chat = setup()
    await lifecycle.mount?.()
    const pending = chat.clearContent()

    const accountTurn = { ...oldTurn, question: '账号历史' }
    api.list.mockResolvedValue({
      items: [{ conversationId: '22', updatedAt: cleared.createdAt }],
      nextCursor: null,
    })
    api.read.mockResolvedValue({ turns: [accountTurn] })
    session.value = { loggedIn: true, username: 'alice', csrfToken: 'csrf' }
    await vi.waitFor(() => expect(chat.conversationId.value).toBe('22'))
    complete(cleared)

    expect(await pending).toBe(false)
    expect(chat.turns.value).toEqual([accountTurn])
  })
  it('存在尚未结束的回复时，不发清空请求', async () => {
    values.set(ANONYMOUS_CONVERSATION_KEY, '12')
    api.read.mockResolvedValue({ turns: [{ ...oldTurn, status: 'running' }] })
    const chat = setup()
    await lifecycle.mount?.()

    expect(chat.canClearContent.value).toBe(false)
    expect(await chat.clearContent()).toBe(false)
    expect(api.clearContent).not.toHaveBeenCalled()
  })
})

describe('回答进度', () => {
  it('跳过未收到的阶段，收起后保留本轮实际进度供回看', async () => {
    const chat = setup()
    await lifecycle.mount?.()
    api.submit.mockImplementation(async (_id, body, receive) => {
      receive({ kind: 'progress', value: { phase: 'preparing' } })
      receive({ kind: 'progress', value: { phase: 'preparing' } })
      receive({ kind: 'progress', value: { phase: 'thinking' } })
      expect(chat.progress.value?.phases).toEqual(['preparing', 'thinking'])
      receive({ kind: 'delta', value: { offset: 0, text: '答' } })
      receive({ kind: 'progress', value: { phase: 'queued' } })
      expect(chat.progress.value?.phases).toEqual(['preparing', 'thinking', 'output'])
      receive({
        kind: 'terminal',
        value: { requestId: body.requestId, executionId: '27', status: 'completed', text: '答案' },
      })
      expect(chat.progress.value).toBeUndefined()
    })
    await chat.send('问题')
    expect(chat.latest.value?.text).toBe('答案')
    expect(chat.error.value).toBe('')
    const finished = chat.latest.value!
    expect(chat.finishedProgress.get(finished)).toEqual(['preparing', 'thinking'])

    api.submit.mockImplementationOnce(async (_id, body, receive) => {
      receive({
        kind: 'terminal',
        value: {
          requestId: body.requestId,
          executionId: '28',
          status: 'completed',
          text: '直接回答',
        },
      })
    })
    await chat.send('下一问')
    expect(chat.finishedProgress.get(finished)).toEqual(['preparing', 'thinking'])
    expect(chat.finishedProgress.get(chat.latest.value!)).toBeUndefined()
  })

  it('续读舍弃旧阶段，从新快照与进度恢复，已有正文直接进入输出', async () => {
    const chat = setup()
    await lifecycle.mount?.()
    api.submit.mockImplementation(async (_id, body, receive) => {
      receive({
        kind: 'accepted',
        value: { requestId: body.requestId, executionId: '27', status: 'accepted' },
      })
      receive({ kind: 'progress', value: { phase: 'queued' } })
      throw new AgentChatError('断流', 0)
    })
    await chat.send('问题')
    expect(chat.progress.value).toBeUndefined()

    api.resume.mockImplementation(async (_id, receive) => {
      const snapshot = {
        requestId: chat.latest.value!.requestId,
        executionId: '27',
        status: 'running',
        text: '',
      } as const
      receive({ kind: 'snapshot', value: snapshot })
      receive({ kind: 'progress', value: { phase: 'thinking' } })
      expect(chat.progress.value?.phases).toEqual(['thinking'])
      receive({ kind: 'snapshot', value: { ...snapshot, text: '答' } })
      expect(chat.progress.value?.phases).toEqual(['thinking', 'output'])
      receive({ kind: 'terminal', value: { ...snapshot, status: 'completed', text: '答案' } })
    })
    chat.resume()
    await vi.waitFor(() => expect(chat.latest.value?.status).toBe('completed'))
    expect(chat.progress.value).toBeUndefined()
  })
})

describe('带引用的提问', () => {
  it('引用不拆分 emoji，快捷动作和手填问题共用完整提交预算', () => {
    const quote = prepareAssistantQuote('字'.repeat(199) + '😀后续')
    expect(quote).toEqual({ text: '字'.repeat(199), truncated: true })
    for (const action of ['解释这段', '展开讲讲']) {
      expect(formatAssistantQuestion(action, quote.text)).toBe(
        '参考以下摘录：\n' + quote.text + '\n\n' + action,
      )
    }
    expect(formatAssistantQuestion(' ', quote.text)).toBe('')
    expect(formatAssistantQuestion('😀'.repeat(150)).length).toBe(300)
  })

  it('受理事件丢失后用同一请求号续读，只通知一次', async () => {
    const chat = setup()
    await lifecycle.mount?.()
    const notify = vi.fn()
    let originalId = ''
    api.submit.mockImplementationOnce(async (_id, body, receive) => {
      originalId = body.requestId
      receive({ kind: 'progress', value: { phase: 'queued' } })
      throw new AgentChatError('连接中断', 503)
    })
    await chat.send('原问题', notify)
    expect(notify).not.toHaveBeenCalled()

    api.submit.mockImplementationOnce(async (_id, body, receive) => {
      expect(body.requestId).toBe(originalId)
      receive({
        kind: 'snapshot',
        value: { requestId: body.requestId, executionId: '27', status: 'running', text: '答' },
      })
      receive({
        kind: 'terminal',
        value: { requestId: body.requestId, executionId: '27', status: 'completed', text: '答案' },
      })
    })
    chat.resume()
    await vi.waitFor(() => expect(chat.latest.value?.status).toBe('completed'))
    expect(notify).toHaveBeenCalledOnce()
  })

  it('账号切换后忽略原会话迟到的受理事件', async () => {
    const chat = setup()
    await lifecycle.mount?.()
    const notify = vi.fn()
    let lateEvent: (() => void) | undefined
    api.submit.mockImplementationOnce(async (_id, body, receive) => {
      lateEvent = () =>
        receive({
          kind: 'accepted',
          value: { requestId: body.requestId, executionId: '27', status: 'accepted' },
        })
      throw new AgentChatError('连接中断', 503)
    })
    await chat.send('访客问题', notify)
    session.value = { loggedIn: true, username: 'alice', csrfToken: 'csrf' }
    await vi.waitFor(() => expect(api.list).toHaveBeenCalled())
    lateEvent?.()
    expect(notify).not.toHaveBeenCalled()
  })

  it('拦截完整串 301 字，允许 300 字，受理前不通知清空', async () => {
    const chat = setup()
    await lifecycle.mount?.()
    const notify = vi.fn()
    const quote = prepareAssistantQuote('摘'.repeat(200)).text
    const budget = 300 - formatAssistantQuestion('问', quote).length + 1
    const valid = formatAssistantQuestion('问'.repeat(budget), quote)
    expect(valid).toHaveLength(300)
    await chat.send(valid + '超', notify)
    expect(api.create).not.toHaveBeenCalled()
    expect(api.submit).not.toHaveBeenCalled()

    api.create.mockRejectedValueOnce(new Error('会话创建失败'))
    await chat.send(valid, notify)
    expect(notify).not.toHaveBeenCalled()

    api.submit.mockImplementationOnce(async (_id, body, receive) => {
      expect(notify).not.toHaveBeenCalled()
      expect(body.question).toBe(valid)
      receive({
        kind: 'accepted',
        value: { requestId: body.requestId, executionId: '27', status: 'accepted' },
      })
      receive({
        kind: 'terminal',
        value: { requestId: body.requestId, executionId: '27', status: 'completed', text: '答' },
      })
    })
    await chat.send(valid, notify)
    expect(api.submit).toHaveBeenCalledOnce()
    expect(notify).toHaveBeenCalledOnce()
    expect(chat.latest.value?.text).toBe('答')
  })
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

  it('旧匿名编号失效后自动恢复历史，不发起问答', async () => {
    const oldId = '12345678-1234-1234-1234-123456789abc'
    values.set(ANONYMOUS_CONVERSATION_KEY, oldId)
    api.read.mockRejectedValueOnce(new AgentChatError('Not found', 404))
    api.read.mockResolvedValueOnce({
      turns: [
        {
          requestId: 'old-request',
          executionId: '27',
          question: '旧问题',
          text: '旧回复',
          status: 'completed',
        },
      ],
    })

    const chat = setup()
    await lifecycle.mount?.()

    expect(api.create).toHaveBeenCalledOnce()
    expect(api.read.mock.calls).toEqual([[oldId], ['12']])
    expect(chat.latest.value?.text).toBe('旧回复')
    expect(values.get(ANONYMOUS_CONVERSATION_KEY)).toBe('12')
    expect(api.submit).not.toHaveBeenCalled()
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

  it('登录后没有本地匿名编号也会尝试认领 Cookie 对应的历史', async () => {
    session.value = { loggedIn: true, username: 'alice', csrfToken: 'csrf' }
    api.claim.mockResolvedValue({ conversationId: '12' })
    api.read.mockResolvedValue({ turns: [oldTurn] })
    const chat = setup()
    await lifecycle.mount?.()

    expect(api.claim).toHaveBeenCalledOnce()
    expect(api.read).toHaveBeenCalledWith('12')
    expect(chat.conversationId.value).toBe('12')
    expect(chat.turns.value).toEqual([oldTurn])
    expect(api.list).not.toHaveBeenCalled()
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
