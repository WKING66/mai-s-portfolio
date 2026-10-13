import { onMounted, watch } from 'vue'
import { FEEDBACK_KINDS, type FeedbackKind } from '../constants/feedback'
import { ApiRequestError } from '../api/request'
import { AUTH_ERROR_CODES } from '../constants/auth'

/** 薄封装 Nuxt UI Toast：通知的状态、队列、计时与挂载全部交给组件库。 */
export function useFeedback() {
  const toast = useToast()
  function show(kind: FeedbackKind, message: string) {
    if (typeof window === 'undefined' || !message.trim()) return
    toast.add({
      title: FEEDBACK_KINDS[kind].title,
      description: message,
      icon: FEEDBACK_KINDS[kind].icon,
      color: kind,
      ui: { description: 'whitespace-pre-wrap break-words' },
    })
  }
  return {
    success: (message: string) => show('success', message),
    info: (message: string) => show('info', message),
    error: (cause: unknown, fallback = '操作失败，请重试。') => {
      // 切换账号后被丢弃的旧响应不是当前账号的失败，不弹出过期提示。
      if (cause instanceof ApiRequestError && cause.code === AUTH_ERROR_CODES.sessionChanged) return
      const message = typeof cause === 'string' ? cause : cause instanceof Error ? cause.message : ''
      show('error', message.trim() ? message : fallback)
    },
  }
}

/** 公开 useFetch 的 SSR 错误在挂载后展示；后续失败继续响应，绝不暴露原始 FetchError 地址。 */
export function useReadErrorFeedback(source: () => unknown, message: string) {
  const feedback = useFeedback()
  onMounted(() => {
    watch(source, cause => {
      if (cause) feedback.error(message)
    }, {
      immediate: true
    }) })
}
