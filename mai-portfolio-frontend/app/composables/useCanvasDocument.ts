import { computed, markRaw, shallowRef, toRaw, type Ref } from 'vue'
import type { Editor } from '@tiptap/core'
import type { Transaction } from '@tiptap/pm/state'
import type { DocumentResource } from '../api/blogs'
import { importCanvasMarkdown } from '../utils/editor/canvasMarkdown'
import { createImmersiveValidator } from '../utils/editor/immersiveValidationClient'
import {
  createCanvasMockAdapter,
  type CanvasMetadata,
  type CanvasSaveSnapshot,
} from '../utils/editor/canvasSave'
import { useCanvasSave } from './useCanvasSave'

/** 编辑期间只读写同一 PM 文档；Markdown 与 JSON 快照仅在显式转换边界创建。 */
export function useCanvasDocument(
  initial: string,
  metadata: Ref<CanvasMetadata>,
  resources: DocumentResource[],
  fail: () => boolean,
) {
  const initialDocument = markRaw(importCanvasMarkdown(initial, resources))
  const editor = shallowRef<Editor | null>(null)
  const validator = createImmersiveValidator()
  const adapter = createCanvasMockAdapter(fail)
  const snapshot = (): CanvasSaveSnapshot => {
    if (!editor.value) {
      throw new Error('编辑器尚未就绪。')
    }
    return {
      document: editor.value.state.doc.toJSON(),
      metadata: structuredClone(toRaw(metadata.value)),
    }
  }
  async function prepare(value: CanvasSaveSnapshot): Promise<CanvasSaveSnapshot> {
    const result = await validator.check({ kind: 'canvas', document: value.document })
    if (result.kind !== 'canvas') {
      throw new Error('保存校验结果不匹配，当前正文未改变。')
    }
    return { ...value, markdown: result.markdown }
  }
  const saved = useCanvasSave(snapshot, prepare, adapter)
  let detach = () => {}
  function attach(instance: Editor | null) {
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
      if (transaction.docChanged) {
        saved.changed()
      }
    }
    instance.on('transaction', changed)
    detach = () => {
      instance.off('transaction', changed)
      instance.on('update', modelUpdate)
    }
  }
  const status = computed(() => {
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
    detach()
    saved.dispose()
    validator.dispose()
  }
  return { initialDocument, editor, attach, status, saved, exportMarkdown, dispose }
}
