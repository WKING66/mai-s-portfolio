import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { useTestCanvasDocument } from '../../support/canvasSession'
import {
  CANVAS_EDITOR_EXTENSIONS,
  CANVAS_STARTER_KIT,
  importCanvasMarkdown,
} from '../../../app/utils/editor/markdown/canvasMarkdown'

describe('Showcase 单画布事件边界', () => {
  it('长文装载期间阻止保存和导出，完成后完整正文保持 clean 且可继续编辑', async () => {
    const prepared = importCanvasMarkdown(
      Array.from({ length: 90 }, (_, i) => `第 ${i} 段。`).join('\n\n'),
    )
    const metadata = ref({
      title: '标题',
      slug: 'test',
      summary: '',
      allowDownload: true,
      remoteRightsConfirmed: false,
    })
    const canvas = useTestCanvasDocument(prepared, metadata, [], () => false)
    const editor = new Editor({
      element: null,
      extensions: [StarterKit.configure(CANVAS_STARTER_KIT), ...CANVAS_EDITOR_EXTENSIONS],
      content: canvas.editorDocument,
    })
    // 无 DOM 的真实 PM 编辑器默认报告未挂载；这里只模拟已挂载的生命周期状态。
    const mounted = vi.spyOn(editor, 'isDestroyed', 'get').mockReturnValue(false)
    try {
      expect(canvas.loading.value).toBe(true)
      expect(await canvas.saved.save()).toBe(false)
      await expect(canvas.exportMarkdown()).rejects.toThrow('尚未完整加载')
      canvas.attach(editor)
      await canvas.initialize()
      expect(canvas.initializationError.value).toBe('')
      expect(canvas.ready.value).toBe(true)
      expect(canvas.loadedBlocks.value).toBe(90)
      expect(editor.state.doc.toJSON()).toEqual(prepared)
      expect(canvas.saved.revision.value).toBe(0)
      expect(canvas.saved.dirty.value).toBe(false)
      editor.view.dispatch(editor.state.tr.insertText('编辑', 1))
      expect(canvas.saved.dirty.value).toBe(true)
      expect(canvas.saved.revision.value).toBe(1)
    } finally {
      canvas.dispose()
      mounted.mockRestore()
      editor.destroy()
    }
  })

  it('复用导入边界已经检查的文档，不把整个文档再次当作 Markdown 解析', () => {
    const prepared = importCanvasMarkdown('## 章节\n\n正文 $x^2$。')
    const metadata = ref({
      title: '标题',
      slug: 'test',
      summary: '',
      allowDownload: true,
      remoteRightsConfirmed: false,
    })
    const canvas = useTestCanvasDocument(prepared, metadata, [], () => false)
    try {
      expect(canvas.initialDocument).toBe(prepared)
      expect(
        canvas.initialDocument.content?.[1]?.content?.some(
          (node) => node.type === 'preservedMathInline',
        ),
      ).toBe(true)
      expect(canvas.saved.dirty.value).toBe(false)
    } finally {
      canvas.dispose()
    }
  })

  it('输入不调用未使用的整篇 JSON 输出回调，其他更新订阅与 dirty 仍正常', () => {
    const metadata = ref({
      title: '标题',
      slug: 'test',
      summary: '',
      allowDownload: true,
      remoteRightsConfirmed: false,
    })
    const canvas = useTestCanvasDocument('正文', metadata, [], () => false)
    const editor = new Editor({
      element: null,
      extensions: [StarterKit.configure(CANVAS_STARTER_KIT), ...CANVAS_EDITOR_EXTENSIONS],
      content: canvas.initialDocument,
      // Nuxt UI UEditor 的 onUpdate 当前只生成并发出 modelValue；Showcase 不消费它。
      onUpdate: ({ editor }) => {
        editor.getJSON()
      },
    })
    const json = vi.spyOn(editor, 'getJSON')
    const otherUpdate = vi.fn()
    editor.on('update', otherUpdate)
    try {
      canvas.attach(editor)
      editor.view.dispatch(editor.state.tr.insertText('一'))
      editor.view.dispatch(editor.state.tr.insertText('二'))
      expect(json).not.toHaveBeenCalled()
      expect(otherUpdate).toHaveBeenCalledTimes(2)
      expect(canvas.saved.revision.value).toBe(2)
      expect(canvas.saved.dirty.value).toBe(true)
      expect(editor.state.doc.textContent).toContain('一二')
      canvas.attach(null)
      editor.view.dispatch(editor.state.tr.insertText('三'))
      expect(json).toHaveBeenCalledTimes(1)
      expect(canvas.saved.revision.value).toBe(2)
    } finally {
      canvas.dispose()
      editor.destroy()
    }
  })
})
