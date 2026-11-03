import { nextTick, onBeforeUnmount, onMounted, watch } from 'vue'
import { FEEDBACK_KINDS, type FeedbackKind } from '../constants/feedback'
import { ApiRequestError } from '../api/request'
import { AUTH_ERROR_CODES } from '../constants/auth'

/** 薄封装 Nuxt UI Toast：通知的状态、队列、计时与挂载全部交给组件库。 */
export function useFeedback() {
  const toast = useToast()
  function show(kind: FeedbackKind, message: string) {
    if (typeof window === 'undefined' || !message.trim()) {
      return
    }
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
      if (cause instanceof ApiRequestError && cause.code === AUTH_ERROR_CODES.sessionChanged) {
        return
      }
      // 只提取可展示的消息，未知对象使用业务提示，避免泄漏响应内部字段。
      let message = ''
      if (typeof cause === 'string') {
        message = cause
      } else if (cause instanceof Error) {
        message = cause.message
      }
      show('error', message.trim() ? message : fallback)
    },
  }
}

/** 公开 useFetch 的 SSR 错误在挂载后展示；后续失败继续响应，绝不暴露原始 FetchError 地址。 */
export function useReadErrorFeedback(
  source: () => unknown,
  message: string,
  retry?: () => Promise<unknown>,
) {
  if (retry) {
    const toast = useToast()
    const retryRequest = retry
    let notificationId: string | number | undefined
    let retrying = false
    let disposed = false
    function clearNotice() {
      if (notificationId !== undefined) {
        toast.remove(notificationId)
        notificationId = undefined
      }
    }
    function showNotice() {
      clearNotice()
      notificationId = toast.add({
        title: FEEDBACK_KINDS.error.title,
        description: message,
        color: 'error',
        icon: FEEDBACK_KINDS.error.icon,
        // 操作提示保留到重试，避免提示消失后只剩空白页。
        duration: 0,
        close: false,
        actions: [{ label: '重试', icon: 'i-lucide-rotate-cw', onClick: retryRead }],
      }).id
    }
    async function retryRead() {
      if (retrying || disposed) {
        return
      }
      retrying = true
      let failed = false
      try {
        await retryRequest()
      } catch {
        failed = true
        // 重试失败仍保留同一业务提示，不向访客暴露底层请求错误。
      } finally {
        // useFetch 的错误 watcher 先完成，避免同一次失败重复创建通知。
        await nextTick()
        retrying = false
        if (!disposed) {
          if (failed || source()) {
            showNotice()
          } else {
            clearNotice()
          }
        }
      }
    }
    onMounted(() => {
      watch(
        source,
        (cause) => {
          if (!retrying) {
            if (cause) {
              showNotice()
            } else {
              clearNotice()
            }
          }
        },
        { immediate: true },
      )
    })
    onBeforeUnmount(() => {
      disposed = true
      clearNotice()
    })
    return
  }
  const feedback = useFeedback()
  // SSR 期间不入通知队列；挂载后立即检查初始错误，再监听后续请求失败。
  onMounted(() => {
    watch(
      source,
      (cause) => {
        if (cause) {
          feedback.error(message)
        }
      },
      {
        immediate: true,
      },
    )
  })
}
