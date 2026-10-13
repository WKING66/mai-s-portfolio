import type { parseBlogMarkdown } from './markdown'
type Document = Awaited<ReturnType<typeof parseBlogMarkdown>>
let worker: Worker | undefined, revision = 0
const pending = new Map<number, { resolve: (value: Document) => void; reject: (error: Error) => void }>()

/** 浏览器解析/分词移到单个按需 Worker；SSR 仍生成正文 AST，水合直接复用 payload。 */
export function parseBlogMarkdownInWorker(markdown: string): Promise<Document> {
  if (!worker) {
    worker = new Worker(new URL('../workers/blogMarkdown.ts', import.meta.url), { type: 'module' })
    worker.onmessage = ({ data }) => {
      const task = pending.get(data.id)
      if (!task) return
      pending.delete(data.id)
      if (data.error) task.reject(new Error(data.error))
      else task.resolve(data.document)
    }
    worker.onerror = () => {
      worker?.terminate(); worker = undefined
      for (const task of pending.values()) task.reject(new Error('正文解析暂不可用，请重新读取。'))
      pending.clear()
    }
  }
  const id = ++revision
  return new Promise((resolve, reject) => { pending.set(id, { resolve, reject }); worker!.postMessage({ id, markdown }) })
}
