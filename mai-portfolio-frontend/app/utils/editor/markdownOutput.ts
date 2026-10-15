import type { Editor, JSONContent } from '@tiptap/core'

/** 只忽略代码的保真辅助快照；真实节点、资源属性及数学表达式必须完全可恢复。 */
function contentMeaning(node: JSONContent): unknown {
  const {
    originalFence: _fence,
    originalText: _text,
    originalLanguage: _language,
    ...attrs
  } = node.attrs ?? {}
  // Enter 产生的空段落属于编辑占位，Markdown 不区分其数量；不能因此阻断继续输入。
  const content = node.content
    ?.filter((child) => child.type !== 'paragraph' || child.content?.length)
    .map(contentMeaning)
  return {
    type: node.type,
    text: node.text,
    attrs: Object.fromEntries(Object.entries(attrs).filter(([, value]) => value !== null)),
    marks: node.marks?.map((mark) => ({
      ...mark,
      attrs: Object.fromEntries(
        Object.entries(mark.attrs ?? {}).filter(([, value]) => value !== null),
      ),
    })),
    content: content?.length ? content : undefined,
  }
}

/** 不接受 UEditor 在序列化异常时降级产生的纯文本；保存入口只接收校验成功的 Markdown。 */
export function readEditorMarkdown(editor: Editor, emittedMarkdown: string): string {
  if (!editor.markdown) {
    throw new Error('Markdown 编辑能力尚未就绪，请稍后重试。')
  }
  const markdown = editor.getMarkdown()
  const restored = editor.schema.nodeFromJSON(editor.markdown.parse(markdown))
  // ProseMirror 的空文档必须有一个空段落；Markdown 空串与该占位段落语义相同。
  const restoredDocument = restored.childCount
    ? restored
    : editor.schema.topNodeType.createAndFill()
  if (!restoredDocument) {
    throw new Error('无法恢复 Markdown 文档结构，请使用源码模式编辑。')
  }
  if (
    markdown !== emittedMarkdown ||
    JSON.stringify(contentMeaning(restoredDocument.toJSON())) !==
      JSON.stringify(contentMeaning(editor.getJSON()))
  ) {
    throw new Error('此次富文本操作无法可靠转换为 Markdown，请撤销操作或在源码模式编辑。')
  }
  return markdown
}
