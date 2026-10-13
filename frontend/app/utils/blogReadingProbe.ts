/** 浏览器性能回归可显式安装计数回调；正常阅读不创建日志、计时器或响应式状态。 */
declare global { var __blogReadingProbe: ((operation: string) => void) | undefined }
export function countBlogReading(operation: string) { globalThis.__blogReadingProbe?.(operation) }
