import type { Editor } from '@tiptap/core'
import { Fragment, type Node } from '@tiptap/pm/model'
import { Plugin, TextSelection } from '@tiptap/pm/state'
import { scheduleCanvasTask } from './canvasTasks'

export const CANVAS_INITIALIZATION = 'canvas-initialization'
export const CANVAS_SYNC_BLOCKS = 64

/** 加载锁在事务边界生效，异步控件和命令也不能修改尚未完整装载的正文。 */
export function canvasInitializationGuard(loading: () => boolean) {
  return new Plugin({
    filterTransaction: (transaction) =>
      !loading() || !transaction.docChanged || Boolean(transaction.getMeta(CANVAS_INITIALIZATION)),
  })
}

function yieldToBrowser(signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const abort = () => {
      cancel()
      signal.removeEventListener('abort', abort)
      reject(new Error('文档加载已取消。'))
    }
    const cancel = scheduleCanvasTask(() => {
      signal.removeEventListener('abort', abort)
      resolve()
    })
    signal.addEventListener('abort', abort, { once: true })
    if (signal.aborted) {
      signal.removeEventListener('abort', abort)
      abort()
    }
  })
}

/** 只用于首次挂载已校验文档；不替换编辑器实例，不把初始化加入用户历史。 */
export async function initializeCanvasDocument(
  editor: Pick<Editor, 'state' | 'isDestroyed'> & { view: Pick<Editor['view'], 'dispatch'> },
  document: Node,
  signal: AbortSignal,
  progress: (loaded: number) => void,
) {
  const nodes: Node[] = []
  document.forEach((node) => nodes.push(node))
  let loaded = 0
  let batch = 8
  while (loaded < nodes.length) {
    await yieldToBrowser(signal)
    if (signal.aborted || editor.isDestroyed) {
      throw new Error('文档加载已取消。')
    }
    const started = performance.now()
    const end = Math.min(nodes.length, loaded + batch)
    const content = Fragment.fromArray(nodes.slice(loaded, end))
    const transaction = editor.state.tr
    if (!loaded) {
      transaction.replaceWith(0, transaction.doc.content.size, content)
      transaction.setSelection(TextSelection.atStart(transaction.doc))
    } else {
      transaction.insert(transaction.doc.content.size, content)
    }
    editor.view.dispatch(
      transaction
        .setMeta(CANVAS_INITIALIZATION, true)
        .setMeta('addToHistory', false)
        .setMeta('preventUpdate', true),
    )
    if (editor.state.doc.childCount !== end) {
      throw new Error('部分文档未能装载，编辑与保存已暂停，请重新加载。')
    }
    loaded = end
    progress(loaded)
    // 等待本批 Vue 更新，再按实际成本调整下一批，不累计成一次同步长任务。
    await Promise.resolve()
    const elapsed = Math.max(1, performance.now() - started)
    batch = Math.max(1, Math.min(32, Math.floor((content.childCount * 8) / elapsed)))
  }
  if (!editor.state.doc.eq(document)) {
    throw new Error('加载结果与原文不一致，编辑与保存已暂停，请重新加载。')
  }
}
