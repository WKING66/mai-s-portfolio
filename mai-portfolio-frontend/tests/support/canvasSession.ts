import type { Ref } from 'vue'
import type { DocumentResource } from '../../app/api/blogs'
import { useCanvasDocument } from '../../app/composables/useCanvasDocument'
import { createCanvasResources } from '../../app/utils/editor/session/canvasResources'
import type {
  CanvasMetadata,
  CanvasPersistence,
  CanvasSaveAdapter,
  CanvasSaveSnapshot,
} from '../../app/utils/editor/session/types'
/** 仅供测试的内存保存端口。标识始终为模拟保存，不写后端、OSS 或本机恢复存储。 */
export function createCanvasMockAdapter(
  fail: () => boolean = () => false,
): CanvasSaveAdapter<CanvasSaveSnapshot> & { readonly snapshot: CanvasSaveSnapshot | undefined } {
  let lastSaved: CanvasSaveSnapshot | undefined
  return {
    get snapshot() {
      return lastSaved ? structuredClone(lastSaved) : undefined
    },
    async save(value) {
      await new Promise((resolve) => setTimeout(resolve, 300))
      if (fail()) {
        throw new Error('模拟保存失败，请重试；当前正文未丢失。')
      }
      if (typeof value.markdown !== 'string') {
        throw new Error('正文尚未通过 Markdown 校验。')
      }
      lastSaved = structuredClone(value)
    },
  }
}

export function createTestResources(fail: () => boolean = () => false) {
  let identity = 100
  return createCanvasResources(async () => {
    await new Promise((resolve) => setTimeout(resolve, 300))
    if (fail()) {
      throw new Error('模拟资源处理失败；文件仍保留。')
    }
    return 'asset://' + identity++
  })
}
export function useTestCanvasDocument(
  initial: Parameters<typeof useCanvasDocument>[0],
  metadata: Ref<CanvasMetadata>,
  resources: DocumentResource[],
  fail: () => boolean = () => false,
  resourceFailure: () => boolean = () => false,
  provided?: CanvasPersistence,
) {
  const local = provided?.resources ?? createTestResources(resourceFailure)
  const canvas = useCanvasDocument(
    initial,
    metadata,
    resources,
    provided ?? {
      adapter: createCanvasMockAdapter(fail),
      resources: local,
      saveProperties: async () => {},
    },
  )
  const dispose = canvas.dispose
  return {
    ...canvas,
    dispose() {
      dispose()
      if (!provided) {
        local.dispose()
      }
    },
  }
}
