import { directImmersiveValidator } from '../../support/immersiveValidationFixture'
import { ref } from 'vue'
import { Markdown } from '@tiptap/markdown'
import { useImmersiveDraft } from '../../../app/composables/useImmersiveDraft'
import {
  createImmersiveMarkdownOptions,
  createImmersiveMarkdownManager,
} from '../../../app/utils/editor/markdown/immersiveMarkdown'
import { describe, expect, it, vi } from 'vitest'
import { Editor, getSchema } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT } from '../../../app/utils/editor/markdown/markdownExtensions'
import { inspectBlogMarkdown } from '../../../app/utils/editor/markdown/markdownCompatibility'
import { editorContentMeaning } from '../../../app/utils/editor/markdown/markdownOutput'

const schema = getSchema([StarterKit.configure(EDITOR_STARTER_KIT), ...BLOG_EDITOR_EXTENSIONS])
describe('长文真实语法的安全编辑边界', () => {
  it('重复强调与空列表项产生合法 Schema，文字和有效格式保留', () => {
    const manager = createImmersiveMarkdownManager()
    const source = '**外层 **内层** 外层**\n\n- \n- 后项'
    const parsed = manager.parse(source)
    expect(() => schema.nodeFromJSON(parsed).check()).not.toThrow()
    expect(inspectBlogMarkdown(source, manager)).toBe('')
    expect(schema.nodeFromJSON(parsed).textContent).toContain('外层 内层 外层')
    expect(JSON.stringify(parsed)).toContain('"type":"bold"')
  })
  it('转义文本、实体和列表松紧等价；正文结构仍严格区分', () => {
    const manager = createImmersiveMarkdownManager()
    expect(inspectBlogMarkdown('文字\\+与\\>和 &amp;\n\n- 第一项\n\n- 第二项', manager)).toBe('')
    expect(inspectBlogMarkdown('![图](未知目录/图.png)', manager)).not.toBe('')
  })
  it('只放行本次清单内图片，原始中文与百分号引用不改写', () => {
    const manager = createImmersiveMarkdownManager()
    const source = '![图](图片和附件/image%201.png)'
    const allowed = new Set(['图片和附件/image 1.png'])
    expect(inspectBlogMarkdown(source, manager, allowed)).toBe('')
    expect(manager.parse(source).content?.[0]?.content?.[0]?.attrs?.src).toBe(
      '图片和附件/image%201.png',
    )
    expect(inspectBlogMarkdown('![图](../其他/秘密.png)', manager, allowed)).not.toBe('')
  })
  it('含行内 HTML 或带格式原子公式的块逐字保护，往返不丢内容', () => {
    const manager = createImmersiveMarkdownManager()
    const source = '| A | B |\n| --- | --- |\n| 第一行<br>第二行 | 结果 |\n\n**公式 $x^2$ 及文字**'
    const parsed = manager.parse(source)
    expect(parsed.content?.filter((node) => node.type === 'preservedMarkdownBlock')).toHaveLength(2)
    expect(inspectBlogMarkdown(source, manager)).toBe('')
    expect(editorContentMeaning(manager.parse(manager.serialize(parsed)))).toEqual(
      editorContentMeaning(parsed),
    )
    expect(manager.serialize(parsed)).toContain('第一行<br>第二行')
  })
})

it('画布待提交回调不取消正在进行的源码同步', async () => {
  vi.useFakeTimers()
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
  const draft = useImmersiveDraft('原文', () => {}, ref(true), undefined, directImmersiveValidator())
  try {
    draft.attach(editor)
    draft.updateSource('修改后的源码')
    draft.flushRich()
    await vi.advanceTimersByTimeAsync(400)
    expect(draft.sourcePending.value).toBe(false)
    expect(editor.state.doc.textContent).toBe('修改后的源码')
    expect(draft.issue.value).toBe('')
  } finally {
    draft.dispose()
    editor.destroy()
    vi.useRealTimers()
  }
})
