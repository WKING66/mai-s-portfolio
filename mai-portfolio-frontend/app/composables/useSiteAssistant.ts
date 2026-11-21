import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { AgentChatError, createAgentChatApi } from '../api/agentChat'
import type {
  ChatProgressPhase,
  ChatSnapshot,
  ConversationSummary,
  SavedTurn,
} from '../api/agentChat'
import { ASSISTANT_QUESTION_LIMIT } from '../utils/site/assistantQuestion'
import { useAuthState } from './useAuthState'
import {
  rememberAnonymousConversation,
  restoreAnonymousConversation,
} from '../utils/site/assistantStorage'

export type DisplayPhase = ChatProgressPhase | 'output'
const active = (status: SavedTurn['status']) =>
  status === 'pending' || status === 'accepted' || status === 'running'

export function useSiteAssistant() {
  const auth = useAuthState()
  const api = createAgentChatApi(() => auth.state.value.csrfToken || '')
  const enabled = ref(false)
  const turns = ref<SavedTurn[]>([])
  const generating = ref(false)
  const progress = ref<{ requestId: string; phases: DisplayPhase[] }>()
  // A timeline belongs to its displayed turn and is not persisted as conversation data.
  const finishedProgress = reactive(new WeakMap<SavedTurn, ChatProgressPhase[]>())
  const error = ref('')
  const restoring = ref(false)
  const opening = ref(false)
  const clearing = ref(false)
  const canClearContent = computed(
    () =>
      Boolean(conversationId.value) &&
      turns.value.length > 0 &&
      !generating.value &&
      !restoring.value &&
      !opening.value &&
      !clearing.value &&
      !turns.value.some((turn) => active(turn.status)),
  )
  const conversationId = ref<string>()
  const histories = ref<ConversationSummary[]>([])
  const nextCursor = ref<string | null>(null)
  const loggedIn = computed(() => auth.state.value.loggedIn)
  const key = computed(() =>
    loggedIn.value ? `account:${auth.state.value.username}` : 'anonymous',
  )
  const pendingAcceptance = new Map<string, () => void>()
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

  function finishProgress(turn: SavedTurn) {
    const current = progress.value
    if (current?.requestId !== turn.requestId) {
      return
    }
    const stages = current.phases.filter((phase) => phase !== 'output')
    if (stages.length) {
      finishedProgress.set(turn, stages)
    }
    progress.value = undefined
  }

  async function run(turn: SavedTurn) {
    if (!conversationId.value) {
      return
    }
    const runController = new AbortController()
    controller = runController
    progress.value = { requestId: turn.requestId, phases: [] }
    generating.value = true
    function recordPhase(phase: DisplayPhase) {
      const phases = progress.value?.phases
      if (phases && !phases.includes(phase) && !phases.includes('output')) {
        phases.push(phase)
      }
    }
    error.value = ''
    const markAccepted = () => {
      const notify = pendingAcceptance.get(turn.requestId)
      pendingAcceptance.delete(turn.requestId)
      notify?.()
    }
    const receive: Parameters<typeof api.resume>[1] = (event) => {
      if (controller !== runController) {
        return
      }
      switch (event.kind) {
        case 'accepted':
          markAccepted()
          turn.executionId = event.value.executionId
          turn.status = event.value.status
          remember()
          break
        case 'progress':
          if (active(turn.status)) {
            recordPhase(event.value.phase)
          }
          break
        case 'snapshot':
        case 'terminal':
          if (event.value.executionId) {
            markAccepted()
          }
          apply(turn, event.value)
          if (event.kind === 'terminal' || !active(turn.status)) {
            pendingAcceptance.delete(turn.requestId)
            finishProgress(turn)
          } else if (turn.text.length) {
            recordPhase('output')
          }
          break
        case 'delta':
          if (!active(turn.status)) {
            break
          }
          if (turn.text.length !== event.value.offset) {
            throw new AgentChatError('回答进度已变化，请继续读取。', 0)
          }
          turn.text += event.value.text
          if (event.value.text.length) {
            recordPhase('output')
          }
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
          pendingAcceptance.delete(turn.requestId)
          finishProgress(turn)
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
        progress.value = undefined
        generating.value = false
      }
    }
  }

  async function open(id?: string) {
    if (generating.value || clearing.value) {
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

  async function send(text: string, onAccepted?: () => void) {
    if (
      restoring.value ||
      opening.value ||
      clearing.value ||
      generating.value ||
      turns.value.some((turn) => active(turn.status)) ||
      !text.trim() ||
      text.length > ASSISTANT_QUESTION_LIMIT
    ) {
      return
    }

    turns.value.push({
      requestId: crypto.randomUUID(),
      executionId: null,
      question: text,
      text: '',
      status: 'pending',
    })
    const turn = turns.value.at(-1)!
    if (onAccepted) {
      pendingAcceptance.set(turn.requestId, onAccepted)
    }
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
      pendingAcceptance.delete(turn.requestId)
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
        if (controller === stoppedController && !active(turn.status)) {
          finishProgress(turn)
        }
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
    if (turn && !generating.value && !clearing.value) {
      void run(turn)
    }
  }

  async function clearContent() {
    const id = conversationId.value
    if (!id || !canClearContent.value) {
      return false
    }
    const owner = generation
    clearing.value = true
    try {
      const result = await api.clearContent(id)
      if (owner !== generation || id !== conversationId.value) {
        return false
      }
      turns.value = result.turns
      error.value = ''
      // Ignore history requests started before the clear response.
      historySequence++
      histories.value = histories.value.map((item) =>
        item.conversationId === id
          ? { ...item, title: result.title, createdAt: result.createdAt }
          : item,
      )
      remember()
      return true
    } catch (failure) {
      if (owner !== generation || id !== conversationId.value) {
        return false
      }
      if (failure instanceof AgentChatError && failure.status === 409) {
        throw new AgentChatError('当前会话正在生成回复，请先停止生成，再清空聊天内容。', 409)
      }
      throw failure
    } finally {
      if (owner === generation) {
        clearing.value = false
      }
    }
  }

  async function newConversation() {
    if (!loggedIn.value || generating.value || restoring.value || opening.value || clearing.value) {
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
      if (loggedIn.value) {
        // 即使本地编号丢失，服务端仍可通过 HttpOnly 访客 Cookie 认领。
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
          if (
            failure.status === 404 &&
            /^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i.test(anonymousId)
          ) {
            // The migrated conversation is looked up by the existing guest cookie.
            const restored = await api.create()
            if (owner === generation) {
              await open(restored.conversationId)
            }
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
    pendingAcceptance.clear()
    controller?.abort()
    controller = undefined
    progress.value = undefined
    opening.value = false
    clearing.value = false
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
    pendingAcceptance.clear()
    controller?.abort()
  })

  return {
    enabled,
    restoring: computed(() => restoring.value || opening.value),
    turns,
    generating,
    progress,
    finishedProgress,
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
    clearing,
    canClearContent,
    clearContent,
    newConversation,
    resume,
    open,
    loadHistory,
  }
}
