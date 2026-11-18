import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { AgentChatError, createAgentChatApi } from '../api/agentChat'
import type { ChatSnapshot, ConversationSummary, SavedTurn } from '../api/agentChat'
import { useAuthState } from './useAuthState'
import {
  rememberAnonymousConversation,
  restoreAnonymousConversation,
} from '../utils/site/assistantStorage'

const active = (status: SavedTurn['status']) =>
  status === 'pending' || status === 'accepted' || status === 'running'

export function useSiteAssistant() {
  const auth = useAuthState()
  const api = createAgentChatApi(() => auth.state.value.csrfToken || '')
  const enabled = ref(false)
  const turns = ref<SavedTurn[]>([])
  const generating = ref(false)
  const error = ref('')
  const restoring = ref(false)
  const opening = ref(false)
  const conversationId = ref<string>()
  const histories = ref<ConversationSummary[]>([])
  const nextCursor = ref<string | null>(null)
  const loggedIn = computed(() => auth.state.value.loggedIn)
  const key = computed(() =>
    loggedIn.value ? `account:${auth.state.value.username}` : 'anonymous',
  )
  let controller: AbortController | undefined
  let loaded = false
  let generation = 0
  let openSequence = 0
  let historySequence = 0

  function remember() {
    if (!loggedIn.value) {
      rememberAnonymousConversation(conversationId.value)
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
            rememberAnonymousConversation()
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
    if (generating.value) {
      return
    }
    const target = id ?? restoreAnonymousConversation()
    if (!target) {
      return
    }
    const owner = generation
    const sequence = ++openSequence
    opening.value = true
    try {
      const result = await api.read(target)
      if (owner !== generation || sequence !== openSequence) {
        return
      }
      conversationId.value = target
      turns.value = result.turns
      remember()
      const pending = turns.value.find((turn) => active(turn.status))
      if (pending) {
        void run(pending)
      }
    } finally {
      if (owner === generation && sequence === openSequence) {
        opening.value = false
      }
    }
  }

  async function loadHistory(more = false) {
    if (!loggedIn.value) {
      return
    }
    const owner = generation
    const sequence = ++historySequence
    const page = await api.list(more ? (nextCursor.value ?? undefined) : undefined)
    if (owner !== generation) {
      return
    }
    if (sequence !== historySequence) {
      return
    }
    histories.value = more ? [...histories.value, ...page.items] : page.items
    nextCursor.value = page.nextCursor
  }

  async function send(question: string, quote = '') {
    if (
      restoring.value ||
      opening.value ||
      generating.value ||
      turns.value.some((turn) => active(turn.status)) ||
      !question.trim()
    ) {
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
    const owner = generation
    try {
      if (!conversationId.value) {
        const created = await api.create()
        if (owner !== generation) {
          return
        }
        conversationId.value = created.conversationId
      }
      remember()
      await run(turn)
    } catch (failure) {
      if (owner === generation) {
        turn.status = 'failed'
        error.value = failure instanceof Error ? failure.message : '无法创建会话。'
      }
    } finally {
      if (owner === generation) {
        generating.value = false
      }
    }
  }

  async function stop() {
    const turn = turns.value.find((item) => active(item.status))
    if (!turn?.executionId) {
      return
    }
    const owner = generation
    const stoppedController = controller
    try {
      const stopped = await api.stop(turn.executionId)
      if (owner === generation && turns.value.includes(turn)) {
        apply(turn, stopped)
        stoppedController?.abort()
      }
    } catch (failure) {
      if (owner === generation) {
        error.value = failure instanceof Error ? failure.message : '暂时无法停止生成。'
      }
    }
  }

  function resume() {
    const turn = turns.value.find((item) => active(item.status))
    if (turn && !generating.value) {
      void run(turn)
    }
  }

  async function clear() {
    if (!loggedIn.value || generating.value || restoring.value || opening.value) {
      return
    }
    const owner = generation
    generating.value = true
    try {
      const created = await api.create()
      if (owner !== generation) {
        return
      }
      conversationId.value = created.conversationId
      turns.value = []
      await loadHistory()
    } finally {
      if (owner === generation) {
        generating.value = false
      }
    }
  }

  async function initialize() {
    const owner = generation
    const anonymousId = restoreAnonymousConversation()
    if (!enabled.value) {
      return
    }
    restoring.value = true
    try {
      if (anonymousId && loggedIn.value) {
        // HttpOnly 访客 Cookie 决定可认领的会话，不上传正文或客户端身份。
        const claimed = await api.claim()
        if (owner !== generation) {
          return
        }
        if (claimed.conversationId) {
          await open(claimed.conversationId)
          if (owner === generation) {
            rememberAnonymousConversation()
          }
          return
        }
        rememberAnonymousConversation()
      } else if (anonymousId) {
        try {
          await open(anonymousId)
          return
        } catch (failure) {
          if (!(failure instanceof AgentChatError) || ![403, 404].includes(failure.status)) {
            throw failure
          }
          if (owner !== generation) {
            return
          }
          rememberAnonymousConversation()
        }
      }
      if (loggedIn.value && owner === generation) {
        await loadHistory()
        const latestConversation = histories.value[0]
        if (owner === generation && latestConversation) {
          await open(latestConversation.conversationId)
        }
      }
    } catch (failure) {
      if (owner === generation) {
        error.value = failure instanceof Error ? failure.message : '无法恢复会话。'
      }
    } finally {
      if (owner === generation) {
        restoring.value = false
      }
    }
  }

  onMounted(async () => {
    const owner = generation
    try {
      await auth.ensureSession()
      const status = await api.status()
      if (owner !== generation) {
        return
      }
      enabled.value = status.enabled
      loaded = true
      await initialize()
    } catch {
      if (owner === generation) {
        enabled.value = false
      }
    }
  })
  watch(key, () => {
    if (!loaded) {
      return
    }
    generation++
    controller?.abort()
    controller = undefined
    opening.value = false
    restoring.value = false
    generating.value = false
    conversationId.value = undefined
    turns.value = []
    histories.value = []
    nextCursor.value = null
    error.value = ''
    void initialize()
  })
  onBeforeUnmount(() => {
    generation++
    controller?.abort()
  })

  return {
    enabled,
    restoring: computed(() => restoring.value || opening.value),
    turns,
    generating,
    error,
    conversationId,
    histories,
    nextCursor,
    loggedIn,
    latest: computed(() => turns.value.at(-1)),
    recoverable: computed(() => turns.value.some((turn) => active(turn.status))),
    canStop: computed(() => turns.value.some((turn) => active(turn.status) && turn.executionId)),
    send,
    stop,
    clear,
    resume,
    open,
    loadHistory,
  }
}
