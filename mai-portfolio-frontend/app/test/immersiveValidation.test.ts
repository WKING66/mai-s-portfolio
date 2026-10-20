import * as markdownOutput from '../utils/editor/markdownOutput'
import { ImmersiveMarkdownGuard } from '../utils/editor/immersiveCapabilities'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import { ref } from 'vue'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT } from '../utils/editor/markdownExtensions'
import { createImmersiveMarkdownOptions } from '../utils/editor/immersiveMarkdown'
import {
  createImmersiveValidation,
  type ImmersiveValidator,
  type ImmersiveValidationInput,
  type ImmersiveValidationResult,
  type ImmersiveValidationReply,
} from '../utils/editor/immersiveValidation'
import { createImmersiveValidator } from '../utils/editor/immersiveValidationClient'
import { useImmersiveDraft } from '../composables/useImmersiveDraft'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

function deferredSession() {
  vi.useFakeTimers()
  const process = createImmersiveValidation()
  const jobs: {
    input: ImmersiveValidationInput
    resolve: (result: ImmersiveValidationResult) => void
    reject: (error: Error) => void
  }[] = []
  let initial = true
  const validator: ImmersiveValidator = {
    check(input) {
      if (initial) {
        initial = false
        return Promise.resolve(process(input))
      }
      return new Promise((resolve, reject) => jobs.push({ input, resolve, reject }))
    },
    dispose() {},
  }
  const editor = new Editor({
    element: null,
    extensions: [
      StarterKit.configure(EDITOR_STARTER_KIT),
      ...BLOG_EDITOR_EXTENSIONS,
      Markdown.configure(createImmersiveMarkdownOptions()),
    ],
    content: '原文',
    contentType: 'markdown',
  })
  vi.spyOn(editor, 'isDestroyed', 'get').mockReturnValue(false)
  const publish = vi.fn()
  const draft = useImmersiveDraft('原文', publish, ref(true), undefined, validator)
  draft.attach(editor)
  return {
    editor,
    draft,
    jobs,
    process,
    publish,
    close: () => {
      draft.dispose()
      editor.destroy()
    },
  }
}

describe('后台完整校验的版本安全', () => {
  it('已在局部门禁通过的结构事务仍刷新后台位置映射', async () => {
    const s = deferredSession()
    try {
      await s.draft.ensureCurrent()
      const previous = s.draft.positionMap.value?.document
      const transaction = s.editor.state.tr
        .addMark(1, 3, s.editor.schema.marks.bold!.create())
        .setMeta('immersive-markdown', '**原文**')
      s.editor.view.dispatch(transaction)
      const save = s.draft.ensureCurrent()
      expect(s.jobs).toHaveLength(1)
      s.jobs[0]!.resolve(s.process(s.jobs[0]!.input))
      expect(await save).toBe(true)
      expect(s.draft.positionMap.value?.document).not.toBe(previous)
      expect(s.draft.positionMap.value?.document).toBe(s.editor.state.doc)
      expect(s.draft.positionMap.value?.source).toBe(s.draft.source.value)
      expect(s.draft.positionMap.value?.ranges).toHaveLength(1)
    } finally {
      s.close()
    }
  })
  it('新富文本已提交后，旧失败不覆盖最新 Markdown 或恢复阻断提示', async () => {
    const s = deferredSession()
    try {
      s.editor.commands.insertContent({ type: 'text', text: 'A' })
      const old = s.draft.flushRich()
      s.editor.commands.insertContent({ type: 'text', text: 'B' })
      const latest = s.draft.flushRich()
      expect(s.jobs).toHaveLength(2)
      s.jobs[1]!.resolve(s.process(s.jobs[1]!.input))
      expect(await latest).toBe(true)
      const accepted = s.draft.source.value
      s.jobs[0]!.reject(new Error('过期失败'))
      expect(await old).toBe(false)
      expect(s.draft.source.value).toBe(accepted)
      expect(accepted).toContain('AB')
      expect(s.draft.issue.value).toBe('')
      expect(s.draft.positionMap.value?.source).toBe(accepted)
    } finally {
      s.close()
    }
  })
  it('源码旧结果不应用到新画布，最新结果使用同一份已检查文档及位置映射', async () => {
    const s = deferredSession()
    try {
      s.draft.updateSource('旧输入')
      const old = s.draft.applySource()
      s.draft.updateSource('## 最新标题\n\n最新正文')
      const latest = s.draft.applySource()
      s.jobs[0]!.resolve(s.process(s.jobs[0]!.input))
      expect(await old).toBe(false)
      expect(s.editor.state.doc.textContent).toBe('原文')
      expect(s.draft.sourcePending.value).toBe(true)
      s.jobs[1]!.resolve(s.process(s.jobs[1]!.input))
      expect(await latest).toBe(true)
      expect(s.editor.state.doc.textContent).toBe('最新标题最新正文')
      expect(s.draft.positionMap.value?.document).toBe(s.editor.state.doc)
      expect(s.draft.positionMap.value?.ranges).toHaveLength(2)
    } finally {
      s.close()
    }
  })
  it('保存等待最新版本，等待期间的新输入会重新校验', async () => {
    const s = deferredSession()
    try {
      s.editor.commands.insertContent({ type: 'text', text: 'A' })
      const save = s.draft.ensureCurrent()
      s.editor.commands.insertContent({ type: 'text', text: 'B' })
      s.jobs[0]!.resolve(s.process(s.jobs[0]!.input))
      await Promise.resolve()
      await Promise.resolve()
      await Promise.resolve()
      expect(s.jobs).toHaveLength(2)
      expect(s.publish).not.toHaveBeenCalled()
      s.jobs[1]!.resolve(s.process(s.jobs[1]!.input))
      expect(await save).toBe(true)
      expect(s.draft.source.value).toContain('AB')
      expect(s.draft.unvalidated.value).toBe(false)
    } finally {
      s.close()
    }
  })
  it('真正失败时保留上次有效 Markdown 和当前可编辑输入，不冒充保存成功', async () => {
    const s = deferredSession()
    try {
      s.editor.commands.insertContent({ type: 'text', text: '未校验输入' })
      const save = s.draft.ensureCurrent()
      s.jobs[0]!.reject(new Error('校验器不可用'))
      expect(await save).toBe(false)
      expect(s.draft.source.value).toBe('原文')
      expect(s.editor.state.doc.textContent).toContain('未校验输入')
      expect(s.editor.isEditable).toBe(true)
      expect(s.draft.issue.value).toBe('校验器不可用')
      expect(s.publish).not.toHaveBeenCalled()
    } finally {
      s.close()
    }
  })
})

it('后台转换仍拒绝未闭合围栏、不允许的资源及无损往返失败', () => {
  const process = createImmersiveValidation()
  const fence = process({ kind: 'source', markdown: '~~~ts\n未闭合', resources: [] })
  expect(fence.kind === 'source' && fence.issue).toContain('围栏尚未闭合')
  const denied = process({ kind: 'source', markdown: '![图](../未知目录/图.png)', resources: [] })
  expect(denied.kind === 'source' && denied.issue).not.toBe('')
  const source =
    '## 标题\n\n文本 $ $\n\n~~~ts [a.ts]\nconst n = 1\n~~~\n\n| A | B |\n|---|---|\n| $x$ | 2 |'
  const parsed = process({ kind: 'source', markdown: source, resources: [] })
  expect(parsed.kind).toBe('source')
  if (parsed.kind !== 'source' || !parsed.document) {
    throw new Error('转换未通过')
  }
  expect(parsed.issue).toBe('')
  expect(parsed.ranges).toHaveLength(4)
  const restored = process({ kind: 'rich', document: parsed.document })
  expect(restored.kind === 'rich' && restored.markdown).toContain('$ $')
})

it('Worker 运输只保留最新待执行任务，故障拒绝请求且不回退到主线程', async () => {
  const transports: Transport[] = []
  class Transport {
    onmessage: (event: MessageEvent<ImmersiveValidationReply>) => void = () => {}
    onerror = () => {}
    onmessageerror = () => {}
    sent: { id: number; input: ImmersiveValidationInput }[] = []
    terminate = vi.fn()
    constructor() {
      transports.push(this)
    }
    postMessage(message: { id: number; input: ImmersiveValidationInput }) {
      this.sent.push(message)
    }
  }
  vi.stubGlobal('Worker', Transport)
  const client = createImmersiveValidator()
  const first = client.check({ kind: 'source', markdown: 'A', resources: [] })
  const superseded = client
    .check({ kind: 'source', markdown: 'B', resources: [] })
    .catch((error: Error) => error.message)
  const latest = client
    .check({ kind: 'source', markdown: 'C', resources: [] })
    .catch((error: Error) => error.message)
  const transport = transports[0]!
  expect(transport.sent).toHaveLength(1)
  const request = transport.sent[0]!
  transport.onmessage(
    new MessageEvent('message', {
      data: { id: request.id, ok: true, value: createImmersiveValidation()(request.input) },
    }),
  )
  await first
  expect(await superseded).toContain('替代')
  expect(transport.sent).toHaveLength(2)
  expect(transport.sent[1]!.input).toMatchObject({ markdown: 'C' })
  transport.onerror()
  expect(await latest).toContain('后台校验不可用')
  await expect(client.check({ kind: 'source', markdown: 'D', resources: [] })).rejects.toThrow(
    '后台校验不可用',
  )
  expect(transport.terminate).toHaveBeenCalledTimes(1)
  client.dispose()
})

it('长文末段回车只严格检查变更邻域，完整文档仍通过后台往返', () => {
  const source = Array.from(
    { length: 120 },
    (_, index) => '段落 ' + index + '，正文 **强调** 与 $x$。',
  ).join('\n\n')
  const editor = new Editor({
    element: null,
    extensions: [
      StarterKit.configure(EDITOR_STARTER_KIT),
      ...BLOG_EDITOR_EXTENSIONS,
      Markdown.configure(createImmersiveMarkdownOptions()),
      ImmersiveMarkdownGuard,
    ],
    content: source,
    contentType: 'markdown',
  })
  editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
  const serialize = vi.spyOn(markdownOutput, 'serializeEditorDocument')
  try {
    const count = editor.state.doc.childCount
    editor.view.dispatch(editor.state.tr.split(editor.state.doc.content.size - 1))
    expect(editor.state.doc.childCount).toBe(count + 1)
    expect(serialize).toHaveBeenCalled()
    expect(serialize.mock.calls[0]![1].childCount).toBeLessThan(8)
    const checked = createImmersiveValidation()({
      kind: 'rich',
      document: editor.state.doc.toJSON(),
    })
    expect(checked.kind).toBe('rich')
  } finally {
    serialize.mockRestore()
    editor.destroy()
  }
})
