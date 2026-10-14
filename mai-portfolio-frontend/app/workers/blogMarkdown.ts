import { parseBlogMarkdown } from '../utils/markdown'
const worker = self as unknown as {
  onmessage:
    ((event: MessageEvent<{ id: number; markdown: string; probe?: boolean }>) => void) | null
  postMessage(value: unknown): void
}
// 解析入口与 SSR 复用同一安全管线；任务标识和正文结果契约保持一致。
worker.onmessage = async ({ data }) => {
  const counts: Record<string, number> = {}
  if (data.probe) {
    globalThis.__blogReadingProbe = (name) => {
      counts[name] = (counts[name] || 0) + 1
    }
  }
  try {
    const parsedDocument = await parseBlogMarkdown(data.markdown)
    if (!parsedDocument) {
      throw new Error('Empty parser result')
    }
    worker.postMessage({ id: data.id, document: parsedDocument, ...(data.probe ? { counts } : {}) })
  } catch {
    worker.postMessage({ id: data.id, error: '正文解析失败，请重新读取。' })
  } finally {
    if (data.probe) {
      globalThis.__blogReadingProbe = undefined
    }
  }
}
