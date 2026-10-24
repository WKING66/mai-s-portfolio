import { computed, markRaw, ref, shallowRef, toRaw, type Ref } from 'vue'
import type { Editor, JSONContent } from '@tiptap/core'
import type { Transaction } from '@tiptap/pm/state'
import type { DocumentResource } from '../api/blogs'
import { canvasSchema, importCanvasMarkdown } from '../utils/editor/canvasMarkdown'
import {
  CANVAS_INITIALIZATION,
  CANVAS_SYNC_BLOCKS,
  initializeCanvasDocument,
} from '../utils/editor/canvasInitialization'
import { createImmersiveValidator } from '../utils/editor/immersiveValidationClient'
import {
  createCanvasMockAdapter,
  type CanvasMetadata,
  type CanvasSaveSnapshot,
} from '../utils/editor/canvasSave'
import { useCanvasSave } from './useCanvasSave'
import {
  CANVAS_RESOURCE_NORMALIZATION,
  createCanvasResources,
  normalizeCanvasResources,
} from '../utils/editor/canvasResources'

/** 编辑期间只读写同一 PM 文档；Markdown 与 JSON 快照仅在显式转换边界创建。 */
export function useCanvasDocument(
  initial: string | JSONContent,
  metadata: Ref<CanvasMetadata>,
  resources: DocumentResource[],
  fail: () => boolean,
  resourceFailure: () => boolean = () => false,
) {
  // 本地导入先检查成功再替换画布；复用该快照，避免挂载时再次解析整篇 Markdown。
  const initialDocument = markRaw(
    typeof initial === 'string' ? importCanvasMarkdown(initial, resources) : initial,
  )
  const editor = shallowRef<Editor | null>(null)
  const totalBlocks = initialDocument.content?.length ?? 0
  const batched = totalBlocks > CANVAS_SYNC_BLOCKS
  const editorDocument = batched
    ? markRaw(canvasSchema.topNodeType.createAndFill()!.toJSON())
    : initialDocument
  const loading = ref(batched)
  const loadedBlocks = ref(batched ? 0 : totalBlocks)
  const initializationError = ref('')
  const ready = computed(() => !loading.value && !initializationError.value)
  let initialization: AbortController | undefined
  const validator = createImmersiveValidator()
  const adapter = createCanvasMockAdapter(fail)
  const localResources = createCanvasResources(resourceFailure)
  let disposed = false
  const snapshot = (): CanvasSaveSnapshot => {
    if (!ready.value) {
      throw new Error('文档尚未完整加载，当前内容未保存。')
    }
    if (!editor.value) {
      throw new Error('编辑器尚未就绪。')
    }
    return {
      document: editor.value.state.doc.toJSON(),
      metadata: structuredClone(toRaw(metadata.value)),
    }
  }
  async function prepare(value: CanvasSaveSnapshot): Promise<CanvasSaveSnapshot> {
    const prepared = await localResources.prepare(value.document)
    const result = await validator.check({ kind: 'canvas', document: prepared.document })
    if (result.kind !== 'canvas') {
      throw new Error('保存校验结果不匹配，当前正文未改变。')
    }
    if (!disposed && editor.value && !editor.value.isDestroyed) {
      normalizeCanvasResources(editor.value, prepared.replacements)
    }
    return { ...value, document: prepared.document, markdown: result.markdown }
  }
  const saveState = useCanvasSave(snapshot, prepare, adapter)
  const saved = {
    ...saveState,
    save: () => (ready.value ? saveState.save() : Promise.resolve(false)),
  }
  async function initialize() {
    initialization?.abort()
    const controller = new AbortController()
    initialization = controller
    const instance = editor.value
    if (!instance || !batched) {
      return
    }
    loading.value = true
    initializationError.value = ''
    loadedBlocks.value = 0
    try {
      const document = instance.schema.nodeFromJSON(initialDocument)
      document.check()
      await initializeCanvasDocument(instance, document, controller.signal, (loaded) => {
        loadedBlocks.value = loaded
      })
    } catch (cause) {
      if (!controller.signal.aborted && !disposed) {
        initializationError.value =
          cause instanceof Error ? cause.message : '文档加载失败，请重新加载。'
      }
    } finally {
      if (initialization === controller && !controller.signal.aborted && !disposed) {
        loading.value = false
      }
    }
  }
  let detach = () => {}
  function attach(instance: Editor | null) {
    initialization?.abort()
    detach()
    editor.value = instance ? markRaw(instance) : null
    if (!instance) {
      return
    }
    // UEditor 的此回调仅输出 modelValue；单画布不消费它，避免每次按键导出整篇 JSON。
    // 只摘除这个具名订阅，保留 NodeView、Vue 与工具栏的其他更新监听。
    const modelUpdate = instance.options.onUpdate
    instance.off('update', modelUpdate)
    const changed = ({ transaction }: { transaction: Transaction }) => {
      if (
        transaction.docChanged &&
        !transaction.getMeta(CANVAS_RESOURCE_NORMALIZATION) &&
        !transaction.getMeta(CANVAS_INITIALIZATION)
      ) {
        saved.changed()
      }
    }
    instance.on('transaction', changed)
    detach = () => {
      instance.off('transaction', changed)
      instance.on('update', modelUpdate)
    }
    if (batched) {
      void initialize()
    }
  }
  const status = computed(() => {
    if (loading.value) {
      return `正在加载文档 · ${loadedBlocks.value}/${totalBlocks}`
    }
    if (initializationError.value) {
      return initializationError.value
    }
    if (saved.saving.value) {
      return '正在模拟保存…'
    }
    if (saved.error.value) {
      return saved.error.value
    }
    if (saved.dirty.value) {
      return '有未保存修改 · 正文空闲 60 秒后模拟保存'
    }
    return saved.savedRevision.value
      ? '已模拟保存 · 未上传服务器，刷新不恢复'
      : '尚未修改 · 当前仅为演示页面'
  })
  async function exportMarkdown() {
    return (await prepare(snapshot())).markdown!
  }
  function dispose() {
    disposed = true
    initialization?.abort()
    detach()
    saved.dispose()
    validator.dispose()
    localResources.dispose()
  }
  return {
    initialDocument,
    editorDocument,
    editor,
    attach,
    status,
    saved,
    localResources,
    exportMarkdown,
    dispose,
    loading,
    loadedBlocks,
    totalBlocks,
    initializationError,
    ready,
    initialize,
  }
}
