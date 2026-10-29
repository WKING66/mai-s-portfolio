import { afterEach, describe, expect, it, vi } from 'vitest'
import * as math from '../../../app/utils/markdown/blogMath'
import { createMathPreviewQueue } from '../../../app/utils/editor/math/mathPreviewQueue'

// 单测用可控时钟验证队列次序；原生任务调度由初始化测试与浏览器实测覆盖。
vi.mock('../../../app/utils/editor/session/canvasTasks', () => ({
  scheduleCanvasTask: (task: () => void) => {
    const timer = setTimeout(task, 0)
    return () => clearTimeout(timer)
  },
}))

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})

function renderingLoop() {
  vi.useFakeTimers()
  let time = 0
  vi.spyOn(performance, 'now').mockImplementation(() => time)
  const render = vi.spyOn(math, 'renderBlogMath').mockImplementation(async (source, block) => {
    time += 10
    return ['blog-math', { block }, source]
  })
  return { render, queue: createMathPreviewQueue() }
}

describe('长文公式预览调度', () => {
  it('首次绘制不立即渲染全部公式，短批次之间交还事件循环，仍返回安全的完整结果', async () => {
    const { queue, render } = renderingLoop()
    const first = queue.render('x < y', false)
    const second = queue.render('z', true)
    expect(render).not.toHaveBeenCalled()
    let renderedAtHeartbeat = 0
    setTimeout(() => {
      renderedAtHeartbeat = render.mock.calls.length
    }, 0)
    await vi.runAllTimersAsync()
    expect(renderedAtHeartbeat).toBe(1)
    expect((await first)?.[1].html).toContain('x &lt; y')
    expect((await second)?.[1].block).toBe(true)
    expect(render).toHaveBeenCalledTimes(2)
    queue.dispose()
  })

  it('编辑中的公式优先于后台预览；过期预览取消后不再运行', async () => {
    const { queue, render } = renderingLoop()
    const cancelled = new AbortController()
    const stale = queue.render('old', false, cancelled.signal)
    const background = queue.render('background', false)
    const editing = queue.render('editing', false, undefined, true)
    cancelled.abort()
    await vi.runAllTimersAsync()
    expect(render.mock.calls.map(([source]) => source)).toEqual(['editing', 'background'])
    expect(await stale).toBeUndefined()
    expect((await background)?.[1].html).toContain('background')
    expect((await editing)?.[1].html).toContain('editing')
    queue.dispose()
  })

  it('卸载画布后释放等待的预览，拒绝新任务，并保留渲染错误的原文', async () => {
    const { queue, render } = renderingLoop()
    const pending = queue.render('x', false)
    queue.dispose()
    await vi.runAllTimersAsync()
    expect(await pending).toBeUndefined()
    expect(await queue.render('y', false)).toBeUndefined()
    expect(render).not.toHaveBeenCalled()

    const retry = createMathPreviewQueue()
    render.mockRejectedValueOnce(new Error('render unavailable'))
    const invalid = retry.render('<invalid>', true)
    await vi.runAllTimersAsync()
    expect(await invalid).toEqual(math.mathFallback('<invalid>', true))
    retry.dispose()
  })
})
