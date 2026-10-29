import { afterEach, describe, expect, it, vi } from 'vitest'
import { useCanvasSave } from '../../../app/composables/useCanvasSave'

afterEach(() => vi.useRealTimers())
const deferred = () => {
  let resolve!: () => void
  let reject!: (error: Error) => void
  const promise = new Promise<void>((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}

describe('Showcase 统一模拟保存流程', () => {
  it('与服务器一致时单独报告无需上传，不冒充一次实际写入', async () => {
    const saved = useCanvasSave(
      () => '原正文',
      async (value) => value,
      { save: async () => 'unchanged' as const },
    )
    saved.changed()
    expect(await saved.save()).toBe(true)
    expect(saved.dirty.value).toBe(false)
    expect(saved.outcome.value).toBe('unchanged')
    saved.dispose()
  })
  it('取快照同步失败后释放保存状态，允许再次重试', async () => {
    const snapshot = vi
      .fn()
      .mockImplementationOnce(() => {
        throw new Error('编辑器暂未就绪')
      })
      .mockReturnValue('重试正文')
    const persist = vi.fn(async () => {})
    const saved = useCanvasSave(snapshot, async (value: string) => value, { save: persist })
    saved.changed()
    expect(await saved.save()).toBe(false)
    expect(saved.saving.value).toBe(false)
    expect(saved.dirty.value).toBe(true)
    expect(await saved.save()).toBe(true)
    expect(persist).toHaveBeenCalledExactlyOnceWith('重试正文')
    saved.dispose()
  })
  it('输入不转换正文，正文最后一次修改后空闲60秒保存；无修改不发请求', async () => {
    vi.useFakeTimers()
    let text = '初始'
    const snapshot = vi.fn(() => text)
    const prepare = vi.fn(async (value: string) => value)
    const persist = vi.fn(async () => {})
    const saved = useCanvasSave(snapshot, prepare, { save: persist })
    await saved.save()
    expect(persist).not.toHaveBeenCalled()
    text = '修改一'
    saved.changed()
    await vi.advanceTimersByTimeAsync(59000)
    expect(prepare).not.toHaveBeenCalled()
    text = '修改二'
    saved.changed()
    await vi.advanceTimersByTimeAsync(59000)
    expect(persist).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1000)
    expect(snapshot).toHaveBeenCalledTimes(1)
    expect(persist).toHaveBeenCalledExactlyOnceWith('修改二')
    expect(saved.dirty.value).toBe(false)
    await saved.save()
    await saved.save()
    expect(snapshot).toHaveBeenCalledTimes(1)
    expect(prepare).toHaveBeenCalledTimes(1)
    expect(persist).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(120000)
    expect(persist).toHaveBeenCalledTimes(1)
    saved.dispose()
  })
  it('旧保存成功不能清除后续输入的 dirty 状态，新修改保留自己的60秒时限', async () => {
    vi.useFakeTimers()
    let text = '第一版'
    const first = deferred()
    const persist = vi.fn().mockReturnValueOnce(first.promise).mockResolvedValue(undefined)
    const saved = useCanvasSave(
      () => text,
      async (value) => value,
      { save: persist },
    )
    saved.changed()
    const saving = saved.save()
    await Promise.resolve()
    text = '第二版'
    saved.changed()
    first.resolve()
    expect(await saving).toBe(true)
    expect(saved.dirty.value).toBe(true)
    expect(persist).toHaveBeenCalledExactlyOnceWith('第一版')
    await vi.advanceTimersByTimeAsync(60000)
    expect(persist).toHaveBeenLastCalledWith('第二版')
    expect(saved.dirty.value).toBe(false)
    saved.dispose()
  })
  it('保存中重复点击合并为一条最新待保存快照，不并发覆盖', async () => {
    let text = '第一版'
    const first = deferred()
    const persist = vi.fn().mockReturnValueOnce(first.promise).mockResolvedValue(undefined)
    const saved = useCanvasSave(
      () => text,
      async (value) => value,
      { save: persist },
    )
    saved.changed()
    const pending = saved.save()
    await Promise.resolve()
    text = '第二版'
    saved.changed()
    const queued = saved.save()
    expect(queued).toBe(pending)
    text = '第三版'
    saved.changed()
    saved.save()
    expect(persist).toHaveBeenCalledTimes(1)
    first.resolve()
    expect(await pending).toBe(true)
    expect(persist.mock.calls.map(([value]) => value)).toEqual(['第一版', '第三版'])
    expect(saved.dirty.value).toBe(false)
    saved.dispose()
  })
  it('转换、资源准备或适配器失败时不确认已保存，重试仍使用当前快照', async () => {
    const prepare = vi
      .fn()
      .mockRejectedValueOnce(new Error('资源尚未完成'))
      .mockResolvedValue('稳定资源正文')
    const persist = vi
      .fn()
      .mockRejectedValueOnce(new Error('模拟网络失败'))
      .mockResolvedValue(undefined)
    const saved = useCanvasSave(() => '当前正文', prepare, { save: persist })
    saved.changed()
    expect(await saved.save()).toBe(false)
    expect(persist).not.toHaveBeenCalled()
    expect(saved.dirty.value).toBe(true)
    expect(await saved.save()).toBe(false)
    expect(saved.error.value).toBe('模拟网络失败')
    expect(saved.savedRevision.value).toBe(0)
    expect(await saved.save()).toBe(true)
    expect(saved.dirty.value).toBe(false)
    saved.dispose()
  })
  it('元数据修改可手动保存，但不会伪装成正文输入启动60秒计时', async () => {
    vi.useFakeTimers()
    const persist = vi.fn(async () => {})
    const saved = useCanvasSave(
      () => '设置',
      async (value) => value,
      { save: persist },
    )
    saved.changed(false)
    await vi.advanceTimersByTimeAsync(120000)
    expect(persist).not.toHaveBeenCalled()
    expect(await saved.save()).toBe(true)
    expect(persist).toHaveBeenCalledExactlyOnceWith('设置')
    saved.dispose()
  })
})
