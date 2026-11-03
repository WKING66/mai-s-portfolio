import { beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, ref } from 'vue'
import { useReadErrorFeedback } from '../../../app/composables/useFeedback'

const hooks = vi.hoisted(() => ({ mounted: [] as (() => void)[], unmounted: [] as (() => void)[] }))
vi.mock('vue', async (original) => ({
  ...(await original<typeof import('vue')>()),
  onMounted: (callback: () => void) => hooks.mounted.push(callback),
  onBeforeUnmount: (callback: () => void) => hooks.unmounted.push(callback),
}))
const add = vi.fn((notice) => ({ ...notice, id: 'read-' + add.mock.calls.length }))
const remove = vi.fn()
beforeEach(() => {
  vi.clearAllMocks()
  hooks.mounted = []
  hooks.unmounted = []
  vi.stubGlobal('useToast', () => ({ add, remove }))
})
function mount(source: () => unknown, retry: () => Promise<unknown>) {
  const scope = effectScope()
  scope.run(() => {
    useReadErrorFeedback(source, '文章读取失败，请重试。', retry)
    hooks.mounted.forEach((callback) => callback())
  })
  return () => {
    hooks.unmounted.forEach((callback) => callback())
    scope.stop()
  }
}

describe('retryable reading failure', () => {
  it('keeps retry inside the notice and restores it after another failure', async () => {
    const failure = ref<unknown>(new Error('private URL'))
    const retry = vi.fn(async () => {
      failure.value = new Error('still offline')
    })
    const dispose = mount(() => failure.value, retry)
    const notice = add.mock.calls[0]![0]
    expect(notice).toMatchObject({
      duration: 0,
      close: false,
      description: '文章读取失败，请重试。',
    })
    expect(notice.actions[0].label).toBe('重试')
    await notice.actions[0].onClick()
    expect(retry).toHaveBeenCalledOnce()
    expect(add).toHaveBeenCalledTimes(2)
    expect(add.mock.calls[1]![0].description).not.toContain('offline')
    dispose()
  })
  it('prevents duplicate retries and clears the notice after success', async () => {
    const failure = ref<unknown>(new Error('offline'))
    let finish = () => {}
    const retry = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        }),
    )
    const dispose = mount(() => failure.value, retry)
    const action = add.mock.calls[0]![0].actions[0].onClick
    const pending = action()
    await action()
    expect(retry).toHaveBeenCalledOnce()
    failure.value = null
    finish()
    await pending
    expect(add).toHaveBeenCalledOnce()
    expect(remove).toHaveBeenCalledWith('read-1')
    dispose()
  })
  it('does not recreate a notification after leaving during a retry', async () => {
    let finish = () => {}
    const retry = () =>
      new Promise<void>((resolve) => {
        finish = resolve
      })
    const dispose = mount(() => new Error('offline'), retry)
    const pending = add.mock.calls[0]![0].actions[0].onClick()
    dispose()
    finish()
    await pending
    await nextTick()
    expect(add).toHaveBeenCalledOnce()
    expect(remove).toHaveBeenCalledWith('read-1')
  })
  it('retains retry when the callback rejects without changing the error source', async () => {
    const failure = ref<unknown>(new Error('offline'))
    const stop = mount(
      () => failure.value,
      async () => {
        throw new Error('offline')
      },
    )
    await add.mock.calls[0]![0].actions[0].onClick()
    expect(add).toHaveBeenCalledTimes(2)
    stop()
  })
})
