import { computed, ref } from 'vue'

import type { CanvasSaveAdapter } from '../utils/editor/session/types'

/** 单请求与版本快照；输入只增加修订号和重置空闲计时，不导出正文。 */
export function useCanvasSave<T>(
  snapshot: () => T,
  prepare: (value: T) => Promise<T>,
  adapter: CanvasSaveAdapter<T>,
  idleMs = 60000,
) {
  const revision = ref(0)
  const savedRevision = ref(0)
  const saving = ref(false)
  const error = ref('')
  const outcome = ref<'saved' | 'unchanged' | 'failed'>()
  const dirty = computed(() => revision.value !== savedRevision.value)
  let disposed = false
  let timer: ReturnType<typeof setTimeout> | undefined
  let running: Promise<boolean> | undefined
  let runningRevision = -1
  let requested = -1

  function changed(body = true) {
    revision.value++
    error.value = ''
    if (body) {
      clearTimeout(timer)
      timer = setTimeout(() => {
        void save()
      }, idleMs)
    }
  }
  function save(): Promise<boolean> {
    if (disposed) {
      return Promise.resolve(false)
    }
    if (!dirty.value) {
      return Promise.resolve(true)
    }
    requested = revision.value
    if (running) {
      return running
    }
    clearTimeout(timer)
    saving.value = true
    error.value = ''
    running = (async () => {
      try {
        do {
          runningRevision = revision.value
          const value = snapshot()
          const prepared = await prepare(value)
          if (disposed) {
            return false
          }
          const result = await adapter.save(prepared)
          if (disposed) {
            return false
          }
          // 旧快照成功只确认该修订；后续输入仍是 dirty，绝不误报为全部保存。
          savedRevision.value = runningRevision
          outcome.value = result === 'unchanged' ? 'unchanged' : 'saved'
        } while (requested > runningRevision)
        return true
      } catch (cause) {
        if (!disposed) {
          outcome.value = 'failed'
          error.value = cause instanceof Error ? cause.message : '保存失败，当前内容仍在画布中。'
        }
        return false
      }
    })().finally(() => {
      // finally 回调总在 Promise 赋值后执行，快照同步抛错也不会留下已失败的请求占位。
      saving.value = false
      running = undefined
    })
    return running
  }
  function dispose() {
    disposed = true
    clearTimeout(timer)
  }
  return { revision, savedRevision, dirty, saving, error, outcome, changed, save, dispose }
}
