import { resolveCodeLanguage } from '../../markdown/code/codeLanguages'

/** 在主线程归一化语言，Worker 不打包一份 Shiki 目录；完成/失败/卸载均释放。 */
export function formatCodeInWorker(source: string, language: string, signal: AbortSignal) {
  return new Promise<string>((resolve, reject) => {
    const worker = new Worker(new URL('../../../workers/codeFormatting.ts', import.meta.url), {
      type: 'module',
    })
    const finish = (error?: Error, code?: string) => {
      worker.terminate()
      signal.removeEventListener('abort', abort)
      if (error) {
        reject(error)
      } else {
        resolve(code!)
      }
    }
    const abort = () => finish(new Error('格式化已取消；原代码已保留。'))
    worker.onmessage = (event: MessageEvent<{ code?: string; error?: string }>) => {
      if (typeof event.data.code === 'string') {
        finish(undefined, event.data.code)
      } else {
        finish(new Error(event.data.error || '格式化失败；原代码已保留。'))
      }
    }
    worker.onerror = () => finish(new Error('格式化组件加载失败，请重试；原代码已保留。'))
    signal.addEventListener('abort', abort, { once: true })
    if (signal.aborted) {
      abort()
    } else {
      worker.postMessage({ source, language: resolveCodeLanguage(language) })
    }
  })
}
