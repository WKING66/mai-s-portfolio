import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { createSourceFile, isImportDeclaration, ScriptTarget, transpileModule } from 'typescript'
import { ref, shallowRef } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Editor } from '@tiptap/core'
import { Markdown } from '@tiptap/markdown'
import StarterKit from '@tiptap/starter-kit'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT } from '../utils/editor/markdownExtensions'
import {
  createBlogMarkdownManager,
  createBlogMarkdownOptions,
  inspectBlogMarkdown,
} from '../utils/editor/markdownCompatibility'
import { readEditorMarkdown } from '../utils/editor/markdownOutput'

// 执行组件实际脚本中的模式事务，不复制切换算法；TipTap 使用真实 Markdown Schema。
const component = readFileSync(
  new URL('../components/blog/BlogMarkdownEditor.client.vue', import.meta.url),
  'utf8',
)
const scriptSource = createSourceFile(
  'BlogMarkdownEditor.ts',
  component.split('<script setup lang="ts">')[1]!.split('</script>')[0]!,
  ScriptTarget.ES2022,
  true,
)
const script = transpileModule(
  scriptSource.statements
    .filter((statement) => !isImportDeclaration(statement))
    .map((statement) => statement.getFullText(scriptSource))
    .join('\n'),
  { compilerOptions: { target: ScriptTarget.ES2022 } },
).outputText
const editors: Editor[] = []
afterEach(() => {
  editors.splice(0).forEach((editor) => editor.destroy())
  vi.restoreAllMocks()
})
function session(markdown = '**保留原文**') {
  const editor = new Editor({
    element: null,
    extensions: [
      StarterKit.configure(EDITOR_STARTER_KIT),
      ...BLOG_EDITOR_EXTENSIONS,
      Markdown.configure(createBlogMarkdownOptions()),
    ],
    content: markdown,
    contentType: 'markdown',
  })
  editors.push(editor)
  const props = { modelValue: markdown, disabled: false }
  const emit = vi.fn()
  const runtime = runInNewContext(
    script +
      ';({ prepareRichEditor, switchMode, updateRich, flushRichUpdate, mode, sessionId, conversionIssue, keepOriginalMarkdown });',
    {
      exports: {},
      Error,
      defineProps: () => props,
      defineEmits: () => emit,
      defineExpose() {},
      ref,
      shallowRef,
      useTemplateRef: () => ({ value: { editor } }),
      watch() {},
      onBeforeUnmount() {},
      createBlogMarkdownManager,
      createBlogMarkdownOptions,
      inspectBlogMarkdown,
      readEditorMarkdown,
      BLOG_EDITOR_EXTENSIONS: [],
      setTimeout,
      clearTimeout,
    },
  )
  runtime.prepareRichEditor(markdown)
  emit.mockClear()
  return { editor, props, emit, runtime }
}

describe('Markdown 模式切换事务', () => {
  it('未修改时切换不提交正文，不重建富文本会话', () => {
    const { runtime, emit } = session()
    const sessionId = runtime.sessionId.value
    expect(runtime.switchMode('source')).toBe(true)
    expect(runtime.switchMode('rich')).toBe(true)
    expect(runtime.sessionId.value).toBe(sessionId)
    expect(emit).not.toHaveBeenCalledWith('update:modelValue', expect.anything())
  })
  it('切换前同步处理待校验输入，失败留在原模式和原文档', () => {
    const { editor, runtime, emit, props } = session()
    const originalDocument = editor.state.doc
    vi.spyOn(editor, 'getMarkdown').mockImplementation(() => {
      throw new Error('serializer failed')
    })
    runtime.updateRich('保留原文')
    expect(runtime.switchMode('source')).toBe(false)
    expect(runtime.mode.value).toBe('rich')
    expect(runtime.conversionIssue.value).toBe('serializer failed')
    expect(editor.state.doc).toBe(originalDocument)
    expect(props.modelValue).toBe('**保留原文**')
    expect(emit).toHaveBeenLastCalledWith('update:blocked', true)
    expect(emit).not.toHaveBeenCalledWith('update:modelValue', expect.anything())
    // 错误提示出现后再次切换仍不能绕过校验。
    expect(runtime.switchMode('source')).toBe(false)
  })
  it('纯文本降级输出不能用于源码切换或草稿提交', () => {
    const { runtime, emit } = session()
    runtime.updateRich('保留原文')
    expect(runtime.switchMode('source')).toBe(false)
    expect(runtime.mode.value).toBe('rich')
    expect(emit).not.toHaveBeenCalledWith('update:modelValue', expect.anything())
  })
  it('后续合法编辑校验成功后可以解除阻塞并切换', () => {
    const { editor, runtime, emit } = session()
    runtime.updateRich('保留原文')
    expect(runtime.switchMode('source')).toBe(false)
    runtime.updateRich(editor.getMarkdown())
    expect(runtime.switchMode('source')).toBe(true)
    expect(runtime.mode.value).toBe('source')
    expect(runtime.conversionIssue.value).toBe('')
    expect(emit).toHaveBeenCalledWith('update:blocked', false)
  })
  it('不兼容源码切换失败时保留原模式、源码及编辑会话', () => {
    const { runtime, props, emit } = session()
    runtime.switchMode('source')
    const sessionId = runtime.sessionId.value
    props.modelValue = '文字 <span onclick="alert(1)">原始内容</span>'
    expect(runtime.switchMode('rich')).toBe(false)
    expect(runtime.mode.value).toBe('source')
    expect(runtime.sessionId.value).toBe(sessionId)
    expect(props.modelValue).toContain('onclick')
    expect(emit).not.toHaveBeenCalledWith('update:modelValue', expect.anything())
  })
})
