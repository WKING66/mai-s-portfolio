import { highlightCode } from '../utils/codeHighlight'

// 同一 Worker 复用语法和主题，串行处理块，避免首屏并发加载所有语言。
let queue = Promise.resolve()
const cancelled = new Set<number>()
const queued = new Set<number>()
self.onmessage = ({
  data,
}: MessageEvent<{ id: number; source: string; language: string; cancel?: boolean }>) => {
  if (data.cancel) {
    if (queued.has(data.id)) {
      cancelled.add(data.id)
    }
    return
  }
  queued.add(data.id)
  queue = queue.then(async () => {
    try {
      if (cancelled.has(data.id)) {
        return
      }
      const node = await highlightCode(data.source, data.language)
      if (!cancelled.has(data.id)) {
        self.postMessage({ id: data.id, node })
      }
    } catch {
      self.postMessage({ id: data.id, error: '代码高亮暂不可用，完整代码仍可阅读和编辑。' })
    } finally {
      cancelled.delete(data.id)
      queued.delete(data.id)
    }
  })
}
