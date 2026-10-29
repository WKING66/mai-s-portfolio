import { parseBlogMarkdown } from '../utils/markdown/parser'

self.onmessage = async ({ data }: MessageEvent<{ id: number; markdown: string }>) => {
  try {
    const document = await parseBlogMarkdown(data.markdown)
    if (!document) {
      throw new Error('Empty parser result')
    }
    self.postMessage({ id: data.id, document })
  } catch {
    self.postMessage({ id: data.id, error: '正文解析失败，请重新读取。' })
  }
}
