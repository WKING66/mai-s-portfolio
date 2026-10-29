import { directImmersiveValidator } from '../../support/immersiveValidationFixture'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { Markdown } from '@tiptap/markdown'
import { ref } from 'vue'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT } from '../../../app/utils/editor/markdown/markdownExtensions'
import { createBlogMarkdownOptions } from '../../../app/utils/editor/markdown/markdownCompatibility'
import { inspectImmersiveSource, patchImmersiveDocument } from '../../../app/utils/editor/legacy/immersiveDraft'
import { complexSelectionDecorations, codePaint } from '../../../app/utils/editor/interaction/immersiveDecorations'
import { useImmersiveDraft } from '../../../app/composables/useImmersiveDraft'
import { AllSelection, TextSelection } from '@tiptap/pm/state'

const editors: Editor[] = []
const sessions: ReturnType<typeof useImmersiveDraft>[] = []
function createEditor(content: string) {
  const editor = new Editor({
    element: null,
    extensions: [
      StarterKit.configure(EDITOR_STARTER_KIT),
      ...BLOG_EDITOR_EXTENSIONS,
      Markdown.configure(createBlogMarkdownOptions()),
    ],
    content,
    contentType: 'markdown',
  })
  vi.spyOn(editor, 'isDestroyed', 'get').mockReturnValue(false)
  editors.push(editor)
  return editor
}
afterEach(() => {
  sessions.forEach((session) => session.dispose())
  sessions.length = 0
  editors.forEach((editor) => editor.destroy())
  editors.length = 0
  vi.useRealTimers()
})
describe('沉浸工作台单草稿与局部同步', () => {
  it('拒绝尚未闭合的围栏，保留完整源码', () => {
    expect(inspectImmersiveSource('## 标题\n\n```ts\nconst a = 1')).toContain('围栏尚未闭合')
    expect(inspectImmersiveSource('~~~mermaid\nflowchart LR\n A-->B\n~~~')).toBe('')
  })
  it('源码局部修改不替换无关代码节点或编辑器实例', () => {
    const editor = createEditor('## 标题\n\n原文\n\n~~~ts [a.ts]\nconst a = 1\n~~~')
    const code = editor.state.doc.lastChild
    patchImmersiveDocument(editor, '## 标题\n\n修改后\n\n~~~ts [a.ts]\nconst a = 1\n~~~')
    expect(editor.state.doc.textContent).toContain('修改后')
    expect(editor.state.doc.lastChild).toBe(code)
    expect(editor.getMarkdown()).toContain('~~~ts [a.ts]')
  })
  it.each([
    '',
    '# 新标题\n\n- A\n- B',
    '| A | B |\n|---|---|\n| 1 | 2 |',
    '$$\nx^2\n$$',
    '![图片](resource://key)',
  ])('结构变化安全恢复：%s', (source) => {
    const editor = createEditor('## 原有标题\n\n旧正文')
    patchImmersiveDocument(editor, source)
    const parsed = editor.markdown!.parse(source)
    const expected = parsed.content?.length
      ? editor.schema.nodeFromJSON(parsed)
      : editor.schema.topNodeType.createAndFill()!
    expect(editor.state.doc.eq(expected)).toBe(true)
  })
  it('无效源码不会被旧画布更新覆盖，修复后可以继续双向编辑', async () => {
    vi.useFakeTimers()
    const editor = createEditor('## 标题\n\n原文')
    const publish = vi.fn()
    const session = useImmersiveDraft('## 标题\n\n原文', publish, ref(true), undefined, directImmersiveValidator())
    sessions.push(session)
    session.attach(editor)
    const incomplete = '## 标题\n\n```ts\nconst a = 1'
    session.updateSource(incomplete)
    await vi.advanceTimersByTimeAsync(450)
    expect(session.source.value).toBe(incomplete)
    expect(session.sourcePending.value).toBe(true)
    expect(editor.isEditable).toBe(false)
    editor.commands.insertContent({ type: 'text', text: '旧画布更新' })
    await vi.advanceTimersByTimeAsync(300)
    expect(session.source.value).toBe(incomplete)
    session.updateSource(incomplete + '\n```')
    await vi.advanceTimersByTimeAsync(450)
    expect(session.sourcePending.value).toBe(false)
    expect(editor.isEditable).toBe(true)
    expect(editor.getMarkdown()).toContain('const a = 1')
  })
  it('输入法组合期间不应用源码，结束后才更新画布', async () => {
    vi.useFakeTimers()
    const editor = createEditor('原文')
    const session = useImmersiveDraft('原文', vi.fn(), ref(true), undefined, directImmersiveValidator())
    sessions.push(session)
    session.attach(editor)
    session.updateSource('中文组合输入', true)
    await vi.advanceTimersByTimeAsync(1000)
    expect(editor.state.doc.textContent).toBe('原文')
    session.updateSource('中文完成输入')
    await vi.advanceTimersByTimeAsync(450)
    expect(editor.state.doc.textContent).toBe('中文完成输入')
  })
})
describe('真实复杂节点选区与可编辑高亮', () => {
  it('跨块全选时覆盖代码、图片、表格和公式，空选区移除边界', () => {
    const editor = createEditor(
      '段落\n\n~~~ts\nx=1\n~~~\n\n![图](resource://key)\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n$$\nx^2\n$$\n\n末尾',
    )
    editor.view.dispatch(editor.state.tr.setSelection(new AllSelection(editor.state.doc)))
    expect(complexSelectionDecorations(editor.state)).toHaveLength(4)
    editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, 1)))
    expect(complexSelectionDecorations(editor.state)).toHaveLength(0)
  })
  it('装饰偏移保留换行和中文字符，不改写内容', () => {
    const result = codePaint([
      'pre',
      { style: '--shiki-light:#111' },
      [
        'code',
        {},
        ['span', { style: '--shiki-light:#222' }, '中文'],
        '\n',
        ['span', { style: '--shiki-light:#333' }, 'next'],
      ],
    ])
    expect(result.tokens).toEqual([
      { from: 0, to: 2, style: '--shiki-light:#222' },
      { from: 3, to: 7, style: '--shiki-light:#333' },
    ])
  })
})
