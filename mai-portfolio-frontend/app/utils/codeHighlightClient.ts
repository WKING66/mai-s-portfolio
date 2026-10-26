import type { ElementNode } from 'comark'

let worker: Worker | undefined
let sequence = 0
const pending = new Map<
  number,
  { resolve: (node: ElementNode) => void; reject: (error: Error) => void }
>()

/** 正文先挂载完整纯文本，高亮在共享后台线程准备；取消只丢弃旧结果。 */
export function highlightCodeInWorker(source: string, language: string, signal: AbortSignal) {
  if (signal.aborted) {
    return Promise.reject(new Error('代码高亮已取消'))
  }
  worker ??= new Worker(new URL('../workers/codeHighlight.ts', import.meta.url), { type: 'module' })
  worker.onmessage = ({
    data,
  }: MessageEvent<{ id: number; node?: ElementNode; error?: string }>) => {
    const task = pending.get(data.id)
    if (!task) {
      return
    }
    pending.delete(data.id)
    if (data.node) {
      task.resolve(data.node)
    } else {
      task.reject(new Error(data.error || '代码高亮失败'))
    }
  }
  worker.onerror = () => {
    worker?.terminate()
    worker = undefined
    for (const task of pending.values()) {
      task.reject(new Error('代码高亮组件加载失败'))
    }
    pending.clear()
  }
  const id = ++sequence
  return new Promise<ElementNode>((resolve, reject) => {
    const finish = (error?: Error, node?: ElementNode) => {
      pending.delete(id)
      signal.removeEventListener('abort', abort)
      if (error) {
        reject(error)
      } else {
        resolve(node!)
      }
    }
    const abort = () => {
      worker?.postMessage({ id, cancel: true })
      finish(new Error('代码高亮已取消'))
    }
    pending.set(id, {
      resolve: (node) => finish(undefined, node),
      reject: (error) => finish(error),
    })
    signal.addEventListener('abort', abort, { once: true })
    worker!.postMessage({ id, source, language })
  })
}

/** 两帧之间让出主线程，调用方逐块/逐批增加颜色，不一次性重建全文。 */
export function nextCodePaint() {
  return new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
}
