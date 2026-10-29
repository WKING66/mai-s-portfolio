import { formatCodeSource } from '../utils/editor/code/codeFormatting'

self.onmessage = async (event: MessageEvent<{ source: string; language: string }>) => {
  try {
    self.postMessage({ code: await formatCodeSource(event.data.source, event.data.language) })
  } catch (cause) {
    self.postMessage({
      error: cause instanceof Error ? cause.message : '格式化失败；原代码已保留。',
    })
  }
}
