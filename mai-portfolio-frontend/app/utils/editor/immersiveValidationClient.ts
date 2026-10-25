import type {
  ImmersiveValidationInput,
  ImmersiveValidationResult,
  ImmersiveValidationReply,
  ImmersiveValidator,
} from './immersiveValidation'

interface Job {
  id: number
  input: ImmersiveValidationInput
  resolve: (value: ImmersiveValidationResult) => void
  reject: (cause: Error) => void
}

/** 每会话一个 Worker；至多一份在算、一份待算，连续输入不堆积旧全文任务。 */
export function createImmersiveValidator(): ImmersiveValidator {
  let worker: Worker | undefined
  let active: Job | undefined
  let waiting: Job | undefined
  let sequence = 0
  let closed: Error | undefined
  let timer: ReturnType<typeof setTimeout> | undefined

  function close(cause: Error) {
    closed = cause
    clearTimeout(timer)
    worker?.terminate()
    worker = undefined
    active?.reject(cause)
    waiting?.reject(cause)
    active = waiting = undefined
  }
  function pump() {
    if (active || !waiting || closed) {
      return
    }
    if (!worker) {
      try {
        worker = new Worker(new URL('./immersiveValidation.worker.ts', import.meta.url), {
          type: 'module',
        })
        worker.onmessage = (event: MessageEvent<ImmersiveValidationReply>) => {
          const reply = event.data
          if (!active || active.id !== reply.id) {
            return
          }
          clearTimeout(timer)
          const job = active
          active = undefined
          if (reply.ok) {
            job.resolve(reply.value)
          } else {
            job.reject(new Error(reply.error))
          }
          pump()
        }
        worker.onerror = () =>
          close(new Error('后台校验不可用，当前输入仍在此页；请复制当前内容后刷新重试。'))
        worker.onmessageerror = () => close(new Error('后台校验结果无法读取，当前输入已保留。'))
      } catch {
        close(new Error('无法启动后台校验，当前输入已保留。'))
        return
      }
    }
    active = waiting
    waiting = undefined
    timer = setTimeout(() => close(new Error('后台校验超时，当前输入已保留。')), 15000)
    try {
      worker.postMessage({ id: active.id, input: active.input })
    } catch {
      close(new Error('无法提交后台校验，当前输入已保留。'))
    }
  }
  return {
    check(input) {
      if (closed) {
        return Promise.reject(closed)
      }
      return new Promise<ImmersiveValidationResult>((resolve, reject) => {
        waiting?.reject(new Error('旧校验任务已由更新的输入替代。'))
        waiting = { id: ++sequence, input, resolve, reject }
        pump()
      })
    },
    dispose() {
      close(new Error('编辑会话已关闭。'))
    },
  }
}
