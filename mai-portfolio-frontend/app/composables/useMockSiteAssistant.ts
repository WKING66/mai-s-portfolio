import { computed, onBeforeUnmount, ref, toValue, type MaybeRefOrGetter } from 'vue'
import {
  createMockAnswer,
  streamMockAnswer,
  type AssistantContext,
  type AssistantSource,
} from '../utils/site/mockAssistant'

export interface AssistantTurn {
  id: number
  question: string
  quote: string
  content: string
  sources: AssistantSource[]
  status: 'generating' | 'complete' | 'stopped' | 'error'
}

export function useMockSiteAssistant(context: MaybeRefOrGetter<AssistantContext>) {
  const turns = ref<AssistantTurn[]>([])
  const generating = ref(false)
  let controller: AbortController | undefined
  let sequence = 0

  function stop() {
    controller?.abort()
    generating.value = false
    const turn = turns.value.find((item) => item.status === 'generating')
    if (turn) {
      turn.status = 'stopped'
    }
  }

  async function send(question: string, quote = '') {
    if (!question.trim() || generating.value) {
      return
    }
    const answer = createMockAnswer(question.trim(), toValue(context), quote)
    const run = new AbortController()
    controller = run
    const id = ++sequence
    turns.value.push({
      id,
      question: question.trim(),
      quote,
      content: '',
      sources: answer.sources,
      status: 'generating',
    })
    generating.value = true
    try {
      for await (const chunk of streamMockAnswer(answer.content, run.signal)) {
        const turn = turns.value.find((item) => item.id === id)
        if (!turn || run.signal.aborted) {
          break
        }
        turn.content += chunk
      }
      const turn = turns.value.find((item) => item.id === id)
      if (turn && !run.signal.aborted) {
        turn.status = 'complete'
      }
    } catch {
      const turn = turns.value.find((item) => item.id === id)
      if (turn) {
        turn.status = 'error'
      }
    } finally {
      if (controller === run) {
        generating.value = false
      }
    }
  }

  function clear() {
    stop()
    turns.value = []
  }
  onBeforeUnmount(stop)
  const latest = computed(() => turns.value.at(-1))
  return { turns, generating, latest, send, stop, clear }
}
