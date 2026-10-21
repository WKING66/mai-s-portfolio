import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { useCanvasDocument } from '../composables/useCanvasDocument'
import { CANVAS_EDITOR_EXTENSIONS } from '../utils/editor/canvasMarkdown'
import { EDITOR_STARTER_KIT } from '../utils/editor/markdownExtensions'

describe('Showcase 单画布事件边界', () => {
  it('输入不调用未使用的整篇 JSON 输出回调，其他更新订阅与 dirty 仍正常', () => {
    const metadata = ref({
      title: '标题',
      slug: 'test',
      summary: '',
      allowDownload: true,
      remoteRightsConfirmed: false,
    })
    const canvas = useCanvasDocument('正文', metadata, [], () => false)
    const editor = new Editor({
      element: null,
      extensions: [StarterKit.configure(EDITOR_STARTER_KIT), ...CANVAS_EDITOR_EXTENSIONS],
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
