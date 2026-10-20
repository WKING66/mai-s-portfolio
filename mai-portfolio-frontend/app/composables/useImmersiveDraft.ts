import type { PreparedImmersiveSourceMap } from '../utils/editor/immersiveSourceMap'
import { computed, ref, shallowRef, type Ref } from 'vue'
import type { Editor } from '@tiptap/core'
import type { Transaction } from '@tiptap/pm/state'
import { patchImmersiveDocument } from '../utils/editor/immersiveDraft'
import { createImmersiveValidator } from '../utils/editor/immersiveValidationClient'
import type { ImmersiveValidator } from '../utils/editor/immersiveValidation'

/** 一个 Markdown 草稿、一个稳定会话；只有最新版本的发起方能够接受后台转换结果。 */
export function useImmersiveDraft(
  initial: string,
  publish: (markdown: string) => void,
  editing: Ref<boolean>,
  localResources: () => ReadonlySet<string> = () => new Set(),
  validator: ImmersiveValidator = createImmersiveValidator(),
) {
  const positionMap = shallowRef<PreparedImmersiveSourceMap>()
  let initialJob: { version: number; promise: Promise<boolean> } | undefined
  const source = ref(initial)
  const issue = ref('')
  const sourcePending = ref(false)
  const checking = ref(0)
  const editor = shallowRef<Editor | null>(null)
  let timer: ReturnType<typeof setTimeout> | undefined
  let revision = 0
  let disposed = false
  let applyingSource = false
  const richPending = ref(false)
  let richJob: { version: number; promise: Promise<boolean> } | undefined
  let sourceJob: { version: number; promise: Promise<boolean> } | undefined
  let detach = () => {}

  function updateEditable() {
    editor.value?.setEditable(editing.value && !sourcePending.value, false)
  }
  function accept(markdown: string) {
    if (source.value !== markdown) {
      source.value = markdown
      publish(markdown)
    }
  }
  function current(version: number, instance: Editor) {
    return !disposed && version === revision && editor.value === instance && !instance.isDestroyed
  }
  // 普通输入的完整往返在 Worker 中执行；主线程继续接受键盘、选区和滚动。
  async function flushRich(): Promise<boolean> {
    const instance = editor.value
    if (!instance || sourcePending.value || disposed) {
      return false
    }
    if (!richPending.value) {
      return !issue.value
    }
    clearTimeout(timer)
    if (instance.view.composing) {
      timer = setTimeout(() => {
        void flushRich()
      }, 150)
      return false
    }
    const version = revision
    const document = instance.state.doc
    if (richJob?.version === version) {
      return richJob.promise
    }
    checking.value++
    const promise = (async () => {
      try {
        const result = await validator.check({ kind: 'rich', document: document.toJSON() })
        if (!current(version, instance) || sourcePending.value) {
          return false
        }
        if (result.kind !== 'rich') {
          throw new Error('后台校验类型不匹配，当前输入已保留。')
        }
        richPending.value = false
        issue.value = ''
        accept(result.markdown)
        positionMap.value = { source: result.markdown, document, ranges: result.ranges }
        return true
      } catch (cause) {
        if (current(version, instance) && !sourcePending.value) {
          issue.value = cause instanceof Error ? cause.message : '转换失败，当前编辑会话已保留。'
        }
        return false
      } finally {
        checking.value--
        if (richJob?.version === version) {
          richJob = undefined
        }
      }
    })()
    richJob = { version, promise }
    return promise
  }
  function onRichUpdate({ transaction }: { transaction: Transaction }) {
    if (applyingSource || transaction.getMeta('immersive-source') || sourcePending.value) {
      return
    }
    revision++
    // 局部门禁即便已覆盖小文档，也由同一后台任务提交正文与新位置映射。
    // 避免格式/结构修改后仍沿用上一版映射。
    richPending.value = true
    // 新一轮输入已替代旧失败结果；当前版本将在后台重新检查。
    issue.value = ''
    clearTimeout(timer)
    timer = setTimeout(() => {
      void flushRich()
    }, 120)
  }
  function updateSource(markdown: string, composing = false) {
    clearTimeout(timer)
    revision++
    richPending.value = false
    // 源码是用户原始输入；不会由旧画布或旧 Worker 结果覆盖。
    accept(markdown)
    sourcePending.value = true
    issue.value = '正在检查源码，画布暂时只读…'
    updateEditable()
    if (!composing) {
      const expected = revision
      timer = setTimeout(() => {
        void applySource(expected)
      }, 120)
    }
  }
  async function applySource(expected = revision): Promise<boolean> {
    const instance = editor.value
    if (!instance || disposed || expected !== revision) {
      return false
    }
    if (!sourcePending.value) {
      return !issue.value
    }
    if (sourceJob?.version === expected) {
      return sourceJob.promise
    }
    clearTimeout(timer)
    const markdown = source.value
    checking.value++
    const promise = (async () => {
      try {
        const result = await validator.check({
          kind: 'source',
          markdown,
          resources: [...localResources()],
        })
        if (!current(expected, instance) || !sourcePending.value) {
          return false
        }
        if (result.kind !== 'source') {
          throw new Error('后台校验类型不匹配，源码已保留。')
        }
        if (result.issue) {
          issue.value = result.issue
          return false
        }
        if (!result.document) {
          throw new Error('后台未返回有效文档，源码已保留。')
        }
        applyingSource = true
        // 同一份已检查文档应用最小差异；不在主线程再次全文解析。
        patchImmersiveDocument(instance, markdown, result.document)
        sourcePending.value = false
        issue.value = ''
        positionMap.value = {
          source: markdown,
          document: instance.state.doc,
          ranges: result.ranges,
        }
        return true
      } catch (cause) {
        if (current(expected, instance)) {
          issue.value = cause instanceof Error ? cause.message : '源码未应用，保留最后有效画布。'
        }
        return false
      } finally {
        applyingSource = false
        checking.value--
        if (sourceJob?.version === expected) {
          sourceJob = undefined
        }
        updateEditable()
      }
    })()
    sourceJob = { version: expected, promise }
    return promise
  }
  /** 保存和模式切换等待当前版本；等待期间的新输入使旧结果失效并重新检查。 */
  async function ensureCurrent(): Promise<boolean> {
    while (!disposed) {
      const version = revision
      let valid: boolean
      if (sourcePending.value) {
        valid = await applySource(version)
      } else if (richPending.value) {
        valid = await flushRich()
      } else if (initialJob) {
        valid = await initialJob.promise
      } else {
        valid = !issue.value
      }
      if (version !== revision) {
        continue
      }
      return valid && !sourcePending.value && !richPending.value
    }
    return false
  }
  function attach(instance: Editor | null) {
    detach()
    revision++
    editor.value = instance
    if (!instance) {
      return
    }
    const version = revision,
      markdown = source.value
    checking.value++
    const initialPromise = (async () => {
      try {
        const result = await validator.check({
          kind: 'source',
          markdown,
          resources: [...localResources()],
        })
        if (!current(version, instance)) {
          return false
        }
        if (result.kind !== 'source' || result.issue || !result.document) {
          issue.value = result.kind === 'source' ? result.issue : '后台校验类型不匹配。'
          return false
        }
        const candidate = instance.schema.nodeFromJSON(result.document)
        if (candidate.eq(instance.state.doc)) {
          positionMap.value = {
            source: markdown,
            document: instance.state.doc,
            ranges: result.ranges,
          }
        }
        return true
      } catch (cause) {
        if (current(version, instance)) {
          issue.value = cause instanceof Error ? cause.message : '后台校验失败。'
        }
        return false
      } finally {
        checking.value--
        if (initialJob?.version === version) {
          initialJob = undefined
        }
      }
    })()
    initialJob = { version, promise: initialPromise }
    instance.on('update', onRichUpdate)
    detach = () => instance.off('update', onRichUpdate)
    updateEditable()
  }
  function dispose() {
    disposed = true
    revision++
    clearTimeout(timer)
    detach()
    validator.dispose()
  }
  return {
    source,
    positionMap,
    issue,
    sourcePending,
    checking,
    unvalidated: computed(() => richPending.value || sourcePending.value),
    editor,
    attach,
    updateSource,
    applySource,
    flushRich,
    ensureCurrent,
    updateEditable,
    dispose,
  }
}
