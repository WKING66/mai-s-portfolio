import type { ElementNode } from 'comark'
import { compactBlogMath, mathFallback, renderBlogMath } from '../blogMath'
import { scheduleCanvasTask } from './canvasTasks'

/** 一个画布共用预览队列；长文的公式分批绘制，正在编辑的公式优先。 */
export function createMathPreviewQueue() {
  const pending: {
    source: string
    block: boolean
    signal?: AbortSignal
    resolve: (node: ElementNode | undefined) => void
  }[] = []
  let cancelPending: (() => void) | undefined
  let running = false
  let disposed = false

  function schedule() {
    if (!running && !cancelPending && pending.length && !disposed) {
      cancelPending = scheduleCanvasTask(() => void flush())
    }
  }
  async function flush() {
    cancelPending = undefined
    running = true
    const started = performance.now()
    try {
      while (pending.length && !disposed) {
        const job = pending.shift()!
        if (job.signal?.aborted) {
          job.resolve(undefined)
          continue
        }
        let result: ElementNode
        try {
          result = await compactBlogMath(await renderBlogMath(job.source, job.block))
        } catch {
          result = mathFallback(job.source, job.block)
        }
        job.resolve(disposed || job.signal?.aborted ? undefined : result)
        // 不让数百次 await 连成同一轮微任务，留出处理输入、滚动和绘制的机会。
        if (performance.now() - started >= 8) {
          break
        }
      }
    } finally {
      running = false
      schedule()
    }
  }
  return {
    render(source: string, block: boolean, signal?: AbortSignal, editing = false) {
      if (disposed || signal?.aborted) {
        return Promise.resolve(undefined)
      }
      return new Promise<ElementNode | undefined>((resolve) => {
        const job = { source, block, signal, resolve }
        if (editing) {
          pending.unshift(job)
        } else {
          pending.push(job)
        }
        schedule()
      })
    },
    dispose() {
      disposed = true
      cancelPending?.()
      for (const job of pending.splice(0)) {
        job.resolve(undefined)
      }
    },
  }
}
