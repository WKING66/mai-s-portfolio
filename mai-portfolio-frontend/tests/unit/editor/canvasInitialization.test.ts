import { describe, expect, it } from 'vitest'
import { EditorState } from '@tiptap/pm/state'
import { history, undoDepth, undo, redo } from '@tiptap/pm/history'
import { canvasSchema, importCanvasMarkdown } from '../../../app/utils/editor/markdown/canvasMarkdown'
import {
  CANVAS_INITIALIZATION,
  canvasInitializationGuard,
  initializeCanvasDocument,
} from '../../../app/utils/editor/session/canvasInitialization'
import { createImmersiveValidation } from '../../../app/utils/editor/markdown/immersiveValidation'

function fixture(loading: () => boolean = () => false) {
  const document = canvasSchema.nodeFromJSON(
    importCanvasMarkdown(
      Array.from({ length: 90 }, (_, index) => `第 ${index} 段 $x^2$。`).join('\n\n'),
    ),
  )
  let state = EditorState.create({
    schema: canvasSchema,
    plugins: [history(), canvasInitializationGuard(loading)],
  })
  const metadata: unknown[] = []
  const editor = {
    get state() {
      return state
    },
    isDestroyed: false,
    view: {
      dispatch(transaction: EditorState['tr']) {
        metadata.push(transaction.getMeta(CANVAS_INITIALIZATION))
        state = state.apply(transaction)
      },
    },
  }
  return { document, editor, metadata }
}

describe('单画布长文首次初始化', () => {
  it('加载中拒绝普通写入命令，仅允许初始化；就绪后正常编辑', async () => {
    let loading = true
    const { document, editor } = fixture(() => loading)
    const before = editor.state.doc
    editor.view.dispatch(editor.state.tr.insertText('拒绝', 1))
    expect(editor.state.doc).toBe(before)
    await initializeCanvasDocument(editor, document, new AbortController().signal, () => {
      const before = editor.state.doc
      editor.view.dispatch(editor.state.tr.insertText('拒绝', 1))
      expect(editor.state.doc).toBe(before)
    })
    expect(editor.state.doc.eq(document)).toBe(true)
    expect(undoDepth(editor.state)).toBe(0)
    loading = false
    editor.view.dispatch(editor.state.tr.insertText('允许', 1))
    expect(editor.state.doc.firstChild?.textContent).toContain('允许')
    expect(undoDepth(editor.state)).toBe(1)
  })

  it('后台导入沿用同一解析与资源转换，返回完整且可校验的文档', () => {
    const markdown = '## 章节\n\n公式 $x^2$\n\n![图片](local.png)'
    const resources = [
      {
        key: 'image',
        type: 'IMAGE' as const,
        status: 'READY' as const,
        assetId: 1,
        originalUri: 'local.png',
        resourceUrl: null,
        expiresAt: null,
      },
    ]
    const result = createImmersiveValidation()({ kind: 'canvas-import', markdown, resources })
    expect(result.kind).toBe('canvas-import')
    if (result.kind !== 'canvas-import') {
      throw new Error('Unexpected result')
    }
    expect(result.document).toEqual(importCanvasMarkdown(markdown, resources))
    const document = canvasSchema.nodeFromJSON(result.document)
    expect(() => document.check()).not.toThrow()
    expect(document.lastChild?.firstChild?.attrs.src).toBe('resource://image')
  })

  it('分批装载完整原生文档，给其他任务执行机会，不把初始化放入撤销历史', async () => {
    const { document, editor, metadata } = fixture()
    const loaded: number[] = []
    let otherTaskRan = false
    const done = initializeCanvasDocument(
      editor,
      document,
      new AbortController().signal,
      (count) => {
        loaded.push(count)
      },
    )
    setTimeout(() => {
      otherTaskRan = true
    }, 0)
    expect(editor.state.doc.eq(document)).toBe(false)
    await done
    expect(otherTaskRan).toBe(true)
    expect(loaded.length).toBeGreaterThan(1)
    expect(loaded.at(-1)).toBe(document.childCount)
    expect(editor.state.doc.eq(document)).toBe(true)
    expect(editor.state.selection.from).toBe(1)
    expect(metadata.every(Boolean)).toBe(true)
    expect(undoDepth(editor.state)).toBe(0)
    editor.view.dispatch(editor.state.tr.insertText('编辑', 1))
    expect(undoDepth(editor.state)).toBe(1)
    expect(undo(editor.state, editor.view.dispatch)).toBe(true)
    expect(editor.state.doc.eq(document)).toBe(true)
    expect(redo(editor.state, editor.view.dispatch)).toBe(true)
    expect(editor.state.doc.firstChild?.textContent).toContain('编辑')
  })

  it('中途取消时停止追加，待加载文档本身保持完整', async () => {
    const { document, editor } = fixture()
    const before = document.toJSON()
    const controller = new AbortController()
    let batches = 0
    await expect(
      initializeCanvasDocument(editor, document, controller.signal, () => {
        batches++
        controller.abort()
      }),
    ).rejects.toThrow('取消')
    expect(batches).toBe(1)
    expect(editor.state.doc.childCount).toBeLessThan(document.childCount)
    expect(document.toJSON()).toEqual(before)
  })

  it('事务被拒绝或编辑器销毁时不能把部分文档误报为装载完成', async () => {
    const { document, editor } = fixture()
    await expect(
      initializeCanvasDocument(
        { ...editor, view: { dispatch() {} } },
        document,
        new AbortController().signal,
        () => {},
      ),
    ).rejects.toThrow('部分文档')
    editor.isDestroyed = true
    await expect(
      initializeCanvasDocument(editor, document, new AbortController().signal, () => {}),
    ).rejects.toThrow('取消')
  })
})
