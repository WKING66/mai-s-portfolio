import { afterEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Plugin } from '@tiptap/pm/state'
import { useCanvasDocument } from '../../../app/composables/useCanvasDocument'
import {
  CANVAS_EDITOR_EXTENSIONS,
  CANVAS_STARTER_KIT,
  exportCanvasMarkdown,
} from '../../../app/utils/editor/markdown/canvasMarkdown'
import {
  CANVAS_RESOURCE_NORMALIZATION,
  createCanvasResources,
  insertCanvasFiles,
} from '../../../app/utils/editor/session/canvasResources'
import { ImmersiveMarkdownGuard } from '../../../app/utils/editor/interaction/immersiveCapabilities'

// 资源和保存控制器定向测试使用真实转换函数；Worker 线程传输另有独立测试和浏览器检查。
vi.mock('../../../app/utils/editor/markdown/immersiveValidationClient', async () => {
  const { createImmersiveValidation } = await import('../../../app/utils/editor/markdown/immersiveValidation')
  return {
    createImmersiveValidator: () => ({
      check: async (input: Parameters<ReturnType<typeof createImmersiveValidation>>[0]) =>
        createImmersiveValidation()(input),
      dispose() {},
    }),
  }
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

function setup(resourceFailure = () => false) {
  const metadata = ref({
    title: '标题',
    slug: 'test',
    summary: '',
    allowDownload: true,
    remoteRightsConfirmed: false,
    tagIds: [3],
  })
  const canvas = useCanvasDocument('正文', metadata, [], () => false, resourceFailure)
  const editor = new Editor({
    element: null,
    extensions: [
      StarterKit.configure(CANVAS_STARTER_KIT),
      ...CANVAS_EDITOR_EXTENSIONS,
      ImmersiveMarkdownGuard.configure({ roundtrip: false }),
    ],
    content: canvas.initialDocument,
  })
  // 无 DOM 的 Tiptap 会将未挂载视图视为已销毁；这里模拟仍挂载的会话。
  vi.spyOn(editor, 'isDestroyed', 'get').mockReturnValue(false)
  editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
  canvas.attach(editor)
  return {
    canvas,
    editor,
    metadata,
    dispose() {
      canvas.dispose()
      editor.destroy()
    },
  }
}
function references(editor: Editor) {
  const result: string[] = []
  editor.state.doc.descendants((node) => {
    if (node.type.name === 'image') {
      result.push(node.attrs.src)
    }
    result.push(
      ...node.marks.filter((mark) => mark.type.name === 'link').map((mark) => mark.attrs.href),
    )
  })
  return result
}

describe('Showcase 本地资源准备与保存', () => {
  it('业务会话注入的资源不随画布局部重挂载销毁，由持有者最终释放', () => {
    const resources = createCanvasResources()
    const canvas = useCanvasDocument(
      '正文',
      ref({ title: '', slug: '', summary: '', allowDownload: true, remoteRightsConfirmed: false }),
      [],
      () => false,
      () => false,
      { adapter: { save: async () => {} }, resources, saveProperties: async () => {} },
    )
    canvas.dispose()
    expect(() => resources.add(new File(['a'], '附件.txt'))).not.toThrow()
    resources.dispose()
    expect(() => resources.add(new File(['a'], '附件.txt'))).toThrow('已经关闭')
  })
  it('文章设置中的响应式标签数组生成独立快照，保存中继续修改不会丢失', async () => {
    vi.useFakeTimers()
    const { canvas, metadata, dispose } = setup()
    try {
      metadata.value = { ...metadata.value, tagIds: ref([3, 4]).value }
      canvas.saved.changed(false)
      const saving = canvas.saved.save()
      metadata.value.tagIds.push(5)
      canvas.saved.changed(false)
      await vi.advanceTimersByTimeAsync(300)
      expect(await saving).toBe(true)
      expect(metadata.value.tagIds).toEqual([3, 4, 5])
      expect(canvas.saved.dirty.value).toBe(true)
    } finally {
      dispose()
    }
  })
  it('正式上传端口失败保留文件，成功后重试正文保存不重复上传，不输出签名地址', async () => {
    const upload = vi
      .fn()
      .mockRejectedValueOnce(new Error('上传失败'))
      .mockResolvedValue('asset://73')
    const resources = createCanvasResources(() => false, upload)
    try {
      const file = new File(['图片'], '图.png', { type: 'image/png' })
      const node = resources.add(file)
      const document = { type: 'doc', content: [{ type: 'paragraph', content: [node] }] }
      const before = structuredClone(document)
      await expect(resources.prepare(document)).rejects.toThrow('上传失败')
      expect(document).toEqual(before)
      expect(resources.preview(String(node.attrs?.src))).toMatch(/^blob:/)
      const prepared = await resources.prepare(document)
      expect(exportCanvasMarkdown(prepared.document)).toContain('asset://73')
      expect(prepared.replacements.get(String(node.attrs?.src))).toBe('asset://73')
      expect(upload).toHaveBeenCalledTimes(2)
      await resources.prepare(document)
      expect(upload).toHaveBeenCalledTimes(2)
      expect(document).toEqual(before)
    } finally {
      resources.dispose()
    }
  })
  it('资源属性事务被插件拒绝时不能提示保存成功', async () => {
    vi.useFakeTimers()
    const { canvas, editor, dispose } = setup()
    try {
      insertCanvasFiles(editor, canvas.localResources, [new File(['a'], '附件.txt')])
      const pending = references(editor)
      editor.registerPlugin(
        new Plugin({
          filterTransaction: (transaction) => !transaction.getMeta(CANVAS_RESOURCE_NORMALIZATION),
        }),
      )
      const saving = canvas.saved.save()
      await vi.advanceTimersByTimeAsync(600)
      expect(await saving).toBe(false)
      expect(canvas.saved.dirty.value).toBe(true)
      expect(canvas.saved.error.value).toContain('资源引用更新被编辑器拒绝')
      expect(references(editor)).toEqual(pending)
    } finally {
      dispose()
    }
  })
  it('资源失败保留文件与正文；重试成功才确认修订，并仅替换资源引用', async () => {
    vi.useFakeTimers()
    let failed = true
    const { canvas, editor, dispose } = setup(() => failed)
    try {
      expect(
        insertCanvasFiles(editor, canvas.localResources, [
          new File(['image'], '图.png', { type: 'image/png' }),
          new File(['file'], '附件.txt'),
        ]),
      ).toBe(true)
      const pending = references(editor)
      const before = editor.state.doc.toJSON()
      const preview = canvas.localResources.preview(pending[0]!)
      const revision = canvas.saved.revision.value
      const first = canvas.saved.save()
      await vi.advanceTimersByTimeAsync(300)
      expect(await first).toBe(false)
      expect(editor.state.doc.toJSON()).toEqual(before)
      expect(canvas.saved.savedRevision.value).toBe(0)
      expect(canvas.saved.error.value).toContain('模拟资源处理失败')
      expect(canvas.localResources.preview(pending[0]!)).toBe(preview)
      failed = false
      const retry = canvas.saved.save()
      await vi.advanceTimersByTimeAsync(600)
      expect(await retry).toBe(true)
      expect(references(editor)).toHaveLength(2)
      expect(references(editor)).toEqual(
        pending.map((uri) => uri.replace('showcase-pending-', 'showcase-')),
      )
      expect(canvas.saved.revision.value).toBe(revision)
      expect(canvas.saved.dirty.value).toBe(false)
      expect(canvas.localResources.preview(references(editor)[0]!)).toBe(preview)
      expect(canvas.localResources.preview(pending[0]!)).toBe(preview)
      expect(exportCanvasMarkdown(editor.state.doc.toJSON())).not.toContain('pending-')
      expect(editor.commands.undo()).toBe(true)
      expect(references(editor)).toHaveLength(0)
      expect(editor.commands.redo()).toBe(true)
      expect(references(editor)).toHaveLength(2)
      expect(await canvas.exportMarkdown()).not.toContain('pending-')
    } finally {
      dispose()
    }
  })
  it('资源处理期间继续输入，旧保存结果不能覆盖文字或清除新修订', async () => {
    vi.useFakeTimers()
    const { canvas, editor, dispose } = setup()
    try {
      insertCanvasFiles(editor, canvas.localResources, [new File(['a'], '附件.txt')])
      const saving = canvas.saved.save()
      editor.view.dispatch(
        editor.state.tr.insertText('继续编辑', editor.state.doc.content.size - 1),
      )
      await vi.advanceTimersByTimeAsync(600)
      expect(await saving).toBe(true)
      expect(editor.state.doc.textContent).toContain('继续编辑')
      expect(canvas.saved.dirty.value).toBe(true)
      expect(canvas.saved.savedRevision.value).toBeLessThan(canvas.saved.revision.value)
    } finally {
      dispose()
    }
  })
  it('关闭页面释放预览，未完成的资源准备不能继续写入文档', async () => {
    vi.useFakeTimers()
    const revoke = vi.spyOn(URL, 'revokeObjectURL')
    const { canvas, editor, dispose } = setup()
    insertCanvasFiles(editor, canvas.localResources, [
      new File(['a'], '图.png', { type: 'image/png' }),
    ])
    const preview = canvas.localResources.preview(references(editor)[0]!)
    const before = editor.state.doc.toJSON()
    const saving = canvas.saved.save()
    canvas.dispose()
    await vi.advanceTimersByTimeAsync(600)
    expect(await saving).toBe(false)
    expect(revoke).toHaveBeenCalledExactlyOnceWith(preview)
    expect(editor.state.doc.toJSON()).toEqual(before)
    dispose()
  })
  it('未知临时引用明确拒绝；代码文字不被当成资源处理', async () => {
    const resources = createCanvasResources()
    try {
      await expect(
        resources.prepare({
          type: 'doc',
          content: [
            {
              type: 'paragraph',
              content: [{ type: 'image', attrs: { src: 'resource://showcase-pending-lost' } }],
            },
          ],
        }),
      ).rejects.toThrow('不属于当前页面')
      const code = {
        type: 'doc',
        content: [
          {
            type: 'codeBlock',
            content: [{ type: 'text', text: 'resource://showcase-pending-lost' }],
          },
        ],
      }
      expect((await resources.prepare(code)).document).toEqual(code)
    } finally {
      resources.dispose()
    }
  })
  it('代码块拒绝文件插入，不接管原有文本粘贴或块拖拽', () => {
    const { canvas, editor, dispose } = setup()
    try {
      editor.commands.setCodeBlock()
      const before = editor.state.doc.toJSON()
      expect(insertCanvasFiles(editor, canvas.localResources, [new File(['a'], '附件.txt')])).toBe(
        false,
      )
      expect(editor.state.doc.toJSON()).toEqual(before)
    } finally {
      dispose()
    }
  })
})
