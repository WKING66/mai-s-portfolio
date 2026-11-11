import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { AgentChatError, createAgentChatApi } from '../api/agentChat'
import type { ChatSnapshot, ConversationSummary, SavedTurn } from '../api/agentChat'
import { useAuthState } from './useAuthState'

const visitorKey = 'portfolio-agent:visitor'
const active = (status: SavedTurn['status']) =>
  status === 'pending' || status === 'accepted' || status === 'running'

interface BrowserConversation {
  id: string
  pending?: SavedTurn
  importId?: string
  turns?: { question: string; answer: string }[]
}

export function useSiteAssistant() {
  const auth = useAuthState()
  const api = createAgentChatApi(() => auth.state.value.csrfToken || '')
  const enabled = ref(false)
  const turns = ref<SavedTurn[]>([])
  const generating = ref(false)
  const error = ref('')
  const conversationId = ref<string>()
  const histories = ref<ConversationSummary[]>([])
  const nextCursor = ref<string | null>(null)
  const loggedIn = computed(() => auth.state.value.loggedIn)
  const key = computed(() =>
    loggedIn.value ? `portfolio-agent:account:${auth.state.value.username}` : visitorKey,
  )
  const hasAnonymousHistory = ref(false)
  let controller: AbortController | undefined
  let loaded = false

  function stored(storageKey = key.value): BrowserConversation | null {
    try {
      return JSON.parse(localStorage.getItem(storageKey) || 'null')
    } catch {
      return null
    }
  }

  function remember() {
    if (!conversationId.value) {
      return
    }
    const previous = stored()
    const entry: BrowserConversation = { id: conversationId.value }
    const pending = turns.value.find((turn) => active(turn.status))
    if (pending) {
      entry.pending = pending
    }
    if (!loggedIn.value) {
      entry.importId = previous?.importId ?? crypto.randomUUID()
      entry.turns = turns.value
        .filter((turn) => !active(turn.status) && turn.text)
        .slice(-20)
        .map((turn) => ({ question: turn.question, answer: turn.text.slice(0, 4000) }))
    }
    try {
      localStorage.setItem(key.value, JSON.stringify(entry))
    } catch {
      // 浏览器禁用本地存储时保留当前页面状态，登录用户仍可从网站历史恢复。
    }
  }

  function apply(turn: SavedTurn, snapshot: ChatSnapshot) {
    turn.executionId = snapshot.executionId
    turn.status = snapshot.status
    turn.text = snapshot.text
    remember()
  }

  async function run(turn: SavedTurn) {
    if (!conversationId.value) {
      return
    }
    const runController = new AbortController()
    controller = runController
    generating.value = true
    error.value = ''
    const receive: Parameters<typeof api.resume>[1] = (event) => {
      if (controller !== runController) {
        return
      }
      switch (event.kind) {
        case 'accepted':
          turn.executionId = event.value.executionId
          turn.status = event.value.status
          remember()
          break
        case 'snapshot':
        case 'terminal':
          apply(turn, event.value)
          break
        case 'delta':
          if (!active(turn.status)) {
            break
          }
          if (turn.text.length !== event.value.offset) {
            throw new AgentChatError('回答进度已变化，请继续读取。', 0)
          }
          turn.text += event.value.text
          break
      }
    }
    try {
      if (turn.executionId) {
        await api.resume(turn.executionId, receive, runController.signal)
      } else {
        await api.submit(
          conversationId.value,
          { requestId: turn.requestId, question: turn.question },
          receive,
          runController.signal,
        )
      }
    } catch (failure) {
      if (!runController.signal.aborted && controller === runController) {
        error.value = failure instanceof Error ? failure.message : '模型服务暂不可用。'
        // 明确的输入拒绝不需要再查，网络或依赖故障则保留编号供续读。
        if (
          failure instanceof AgentChatError &&
          [400, 403, 404, 409, 422, 429].includes(failure.status)
        ) {
          turn.status = 'failed'
          if (failure.status === 404 && !loggedIn.value) {
            conversationId.value = undefined
            localStorage.removeItem(key.value)
          }
        }
        remember()
      }
    } finally {
      if (controller === runController) {
        controller = undefined
        generating.value = false
      }
    }
  }

  async function open(id?: string) {
    const saved = stored()
    const target = id ?? saved?.id
    if (!target) {
      return
    }
    const owner = key.value
    const result = await api.read(target)
    if (owner !== key.value) {
      return
    }
    conversationId.value = target
    turns.value = result.turns
    if (
      !id &&
      saved?.pending &&
      !turns.value.some((turn) => turn.requestId === saved.pending?.requestId)
    ) {
      turns.value.push(saved.pending)
    }
    remember()
    const pending = turns.value.find((turn) => active(turn.status))
    if (pending) {
      void run(pending)
    }
  }

  async function loadHistory(more = false) {
    if (!loggedIn.value) {
      return
    }
    const owner = key.value
    const page = await api.list(more ? (nextCursor.value ?? undefined) : undefined)
    if (owner !== key.value) {
      return
    }
    histories.value = more ? [...histories.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor
  }

  async function send(question: string, quote = '') {
    if (generating.value || turns.value.some((turn) => active(turn.status)) || !question.trim()) {
      return
    }
    const text = quote ? `参考以下摘录：\n${quote}\n\n${question.trim()}` : question.trim()
    turns.value.push({
      requestId: crypto.randomUUID(),
      executionId: null,
      question: text,
      text: '',
      status: 'pending',
    })
    const turn = turns.value.at(-1)!
    generating.value = true
    const owner = key.value
    try {
      if (!conversationId.value) {
        const created = await api.create()
        if (owner !== key.value) {
          return
        }
        conversationId.value = created.conversationId
      }
      remember()
      await run(turn)
    } catch (failure) {
      if (owner === key.value) {
        turn.status = 'failed'
        error.value = failure instanceof Error ? failure.message : '无法创建会话。'
      }
    } finally {
      if (owner === key.value) {
        generating.value = false
      }
    }
  }

  async function stop() {
    const turn = turns.value.find((item) => active(item.status))
    if (!turn?.executionId) {
      return
    }
    const owner = key.value
    try {
      const stopped = await api.stop(turn.executionId)
      if (owner === key.value) {
        apply(turn, stopped)
        controller?.abort()
      }
    } catch (failure) {
      error.value = failure instanceof Error ? failure.message : '暂时无法停止生成。'
    }
  }

  function resume() {
    const turn = turns.value.find((item) => active(item.status))
    if (turn && !generating.value) {
      void run(turn)
    }
  }

  async function clear() {
    if (!loggedIn.value || generating.value) {
      return
    }
    const owner = key.value
    const created = await api.create()
    if (owner !== key.value) {
      return
    }
    conversationId.value = created.conversationId
    turns.value = []
    remember()
    await loadHistory()
  }

  async function importAnonymous() {
    const saved = stored(visitorKey)
    if (!loggedIn.value || !saved?.importId || !saved.turns?.length) {
      return
    }
    const owner = key.value
    const result = await api.import(saved.importId, saved.turns)
    if (owner !== key.value) {
      return
    }
    localStorage.removeItem(visitorKey)
    hasAnonymousHistory.value = false
    await open(result.conversationId)
    await loadHistory()
  }

  async function initialize() {
    try {
      hasAnonymousHistory.value = Boolean(stored(visitorKey)?.turns?.length)
      if (enabled.value) {
        await open()
      }
    } catch (failure) {
      if (failure instanceof AgentChatError && failure.status === 404) {
        localStorage.removeItem(key.value)
      } else {
        error.value = failure instanceof Error ? failure.message : '无法恢复会话。'
      }
    }
  }

  onMounted(async () => {
    try {
      await auth.ensureSession()
      enabled.value = (await api.status()).enabled
      loaded = true
      await initialize()
    } catch {
      enabled.value = false
    }
  })
  watch(key, () => {
    if (!loaded) {
      return
    }
    controller?.abort()
    controller = undefined
    generating.value = false
    conversationId.value = undefined
    turns.value = []
    histories.value = []
    nextCursor.value = null
    error.value = ''
    void initialize()
  })
  onBeforeUnmount(() => controller?.abort())

  return {
    enabled,
    turns,
    generating,
    error,
    conversationId,
    histories,
    nextCursor,
    loggedIn,
    hasAnonymousHistory,
    latest: computed(() => turns.value.at(-1)),
    recoverable: computed(() => turns.value.some((turn) => active(turn.status))),
    canStop: computed(() => turns.value.some((turn) => active(turn.status) && turn.executionId)),
    send,
    stop,
    clear,
    resume,
    open,
    loadHistory,
    importAnonymous,
  }
}
