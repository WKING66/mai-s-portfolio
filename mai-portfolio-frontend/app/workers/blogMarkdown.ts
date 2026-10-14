import { parseBlogMarkdown } from '../utils/markdown'
const worker = self as unknown as { onmessage: ((event: MessageEvent<{ id: number; markdown: string; probe?: boolean }>) => void) | null; postMessage(value: unknown): void }
worker.onmessage = async ({ data }) => {
  const counts: Record<string, number> = {}
  if (data.probe) globalThis.__blogReadingProbe = name => { counts[name] = (counts[name] || 0) + 1 }
  try {
    const document = await parseBlogMarkdown(data.markdown)
    if (!document) throw new Error('Empty parser result')
    worker.postMessage({ id: data.id, document, ...(data.probe ? { counts } : {}) })
  }
  catch { worker.postMessage({ id: data.id, error: '正文解析失败，请重新读取。' }) }
  finally {
   if (data.probe) globalThis.__blogReadingProbe = undefined
   }
}
