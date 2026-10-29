import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { afterEach, describe, expect, it } from 'vitest'
import { collectEditorHeadings, editorHeadingTree } from '../../../app/utils/editor/interaction/editorOutline'

const editors: Editor[] = []
function documentEditor() {
  const editor = new Editor({
    element: null,
    extensions: [StarterKit],
    content: {
      type: 'doc',
      content: [
        { type: 'heading', attrs: { level: 1 }, content: [{ type: 'text', text: '第一章' }] },
        { type: 'paragraph', content: [{ type: 'text', text: '正文段落' }] },
        { type: 'heading', attrs: { level: 3 }, content: [{ type: 'text', text: '子章节' }] },
        { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: '第二节' }] },
      ],
    },
  })
  editors.push(editor)
  let identity = 0
  return { editor, createId: () => 'heading-' + ++identity }
}
afterEach(() => editors.splice(0).forEach((editor) => editor.destroy()))

describe('沉浸编辑目录直接读取 ProseMirror 文档', () => {
  it('构建多级目录并保留实际标题顺序', () => {
    const { editor, createId } = documentEditor()
    const headings = collectEditorHeadings(editor.state.doc, [], undefined, createId)
    expect(headings.map((heading) => heading.text)).toEqual(['第一章', '子章节', '第二节'])
    const tree = editorHeadingTree(headings)
    expect(tree).toHaveLength(1)
    expect(tree[0]!.children!.map((heading) => heading.text)).toEqual(['子章节', '第二节'])
  })
  it('正文输入仅改变后续位置，不改变标题身份或目录内容', () => {
    const { editor, createId } = documentEditor()
    const previous = collectEditorHeadings(editor.state.doc, [], undefined, createId)
    const paragraphPosition = editor.state.doc.firstChild!.nodeSize + 1
    const transaction = editor.state.tr.insertText('继续写作', paragraphPosition)
    const next = collectEditorHeadings(transaction.doc, previous, transaction.mapping, createId)
    expect(editorHeadingTree(next)).toEqual(editorHeadingTree(previous))
    expect(next[1]!.position).toBe(previous[1]!.position + 4)
  })
  it('修改标题文字保留标题身份，实时反映新标题', () => {
    const { editor, createId } = documentEditor()
    const previous = collectEditorHeadings(editor.state.doc, [], undefined, createId)
    const transaction = editor.state.tr.insertText('新', 1)
    const next = collectEditorHeadings(transaction.doc, previous, transaction.mapping, createId)
    expect(next[0]!.id).toBe(previous[0]!.id)
    expect(next[0]!.text).toBe('新第一章')
  })
  it('删除一个标题不把其身份错误分配给下一标题', () => {
    const { editor, createId } = documentEditor()
    const previous = collectEditorHeadings(editor.state.doc, [], undefined, createId)
    const transaction = editor.state.tr.delete(0, editor.state.doc.firstChild!.nodeSize)
    const next = collectEditorHeadings(transaction.doc, previous, transaction.mapping, createId)
    expect(next.map((heading) => heading.id)).toEqual(
      previous.slice(1).map((heading) => heading.id),
    )
    expect(next[0]!.text).toBe('子章节')
  })
})
