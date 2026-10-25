/** 画布短批次之间归还事件循环；避免嵌套定时器的最小延时累积成漫长装载。 */
export function scheduleCanvasTask(task: () => void): () => void {
  const channel = new MessageChannel()
  const cancel = () => {
    channel.port1.onmessage = null
    channel.port1.close()
    channel.port2.close()
  }
  channel.port1.onmessage = () => {
    cancel()
    task()
  }
  channel.port2.postMessage(null)
  return cancel
}
