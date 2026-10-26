import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ElementNode } from 'comark'

class TestWorker {
  static instances: TestWorker[] = []
  onmessage?: (event: MessageEvent) => void
  onerror?: () => void
  postMessage = vi.fn()
  terminate = vi.fn()
  constructor() {
    TestWorker.instances.push(this)
  }
}
afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetModules()
  TestWorker.instances = []
})
async function client() {
  vi.stubGlobal('Worker', TestWorker)
  return (await import('../utils/codeHighlightClient')).highlightCodeInWorker
}
const node: ElementNode = ['pre', {}, ['code', {}, 'complete source']]
describe('后台高亮结果隔离', () => {
  it('共享单一 Worker，异步返回顺序不会串块', async () => {
    const highlight = await client()
    const first = highlight('one', 'c', new AbortController().signal)
    const second = highlight('two', 'java', new AbortController().signal)
    expect(TestWorker.instances).toHaveLength(1)
    const worker = TestWorker.instances[0]!
    const [a, b] = worker.postMessage.mock.calls.map(([message]) => message.id)
    worker.onmessage!({ data: { id: b, node } } as MessageEvent)
    expect(await second).toEqual(node)
    worker.onmessage!({ data: { id: a, node } } as MessageEvent)
    expect(await first).toEqual(node)
  })
  it('输入变更/卸载取消任务，迟到的旧颜色不会应用', async () => {
    const highlight = await client()
    const controller = new AbortController()
    const result = highlight('old source', 'c', controller.signal)
    const rejected = expect(result).rejects.toThrow('取消')
    const worker = TestWorker.instances[0]!
    const id = worker.postMessage.mock.calls[0]![0].id
    controller.abort()
    await rejected
    expect(worker.postMessage).toHaveBeenLastCalledWith({ id, cancel: true })
    expect(() => worker.onmessage!({ data: { id, node } } as MessageEvent)).not.toThrow()
  })
  it('故障拒绝待处理请求，下次可创建新 Worker 重试', async () => {
    const highlight = await client()
    const result = highlight('source', 'c', new AbortController().signal)
    const rejected = expect(result).rejects.toThrow('加载失败')
    TestWorker.instances[0]!.onerror!()
    await rejected
    const retry = highlight('source', 'c', new AbortController().signal)
    const worker = TestWorker.instances[1]!
    worker.onmessage!({
      data: { id: worker.postMessage.mock.calls[0]![0].id, node },
    } as MessageEvent)
    expect(await retry).toEqual(node)
  })
})
