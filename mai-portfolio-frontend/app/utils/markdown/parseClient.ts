import type { parseBlogMarkdown } from './parser'
type MarkdownDocument = Awaited<ReturnType<typeof parseBlogMarkdown>>
let worker: Worker | undefined,
  taskSequence = 0
const pendingTasks = new Map<
  number,
  { resolve: (value: MarkdownDocument) => void; reject: (error: Error) => void }
>()

/** 浏览器解析/分词移到单个按需 Worker；SSR 仍生成正文 AST，水合直接复用 payload。 */
export function parseBlogMarkdownInWorker(markdown: string): Promise<MarkdownDocument> {
  // 1. 按需创建唯一 Worker，响应通过任务标识匹配，不让并发文章相互覆盖。
  if (!worker) {
    worker = new Worker(new URL('../../workers/blogMarkdown.ts', import.meta.url), {
      type: 'module',
    })
    worker.onmessage = ({ data }) => {
      const task = pendingTasks.get(data.id)
      if (!task) {
        return
      }
      pendingTasks.delete(data.id)
      if (data.error) {
        task.reject(new Error(data.error))
      } else {
        task.resolve(data.document)
      }
    }
    // 2. Worker 故障时拒绝全部待处理任务并释放实例，下次解析允许重新创建。
    worker.onerror = () => {
      worker?.terminate()
      worker = undefined
      for (const task of pendingTasks.values()) {
        task.reject(new Error('正文解析暂不可用，请重新读取。'))
      }
      pendingTasks.clear()
    }
  }
  // 3. 先登记任务再发送正文，保留异步解析的成功与失败出口。
  const id = ++taskSequence
  return new Promise((resolve, reject) => {
    pendingTasks.set(id, { resolve, reject })
    worker!.postMessage({ id, markdown })
  })
}
