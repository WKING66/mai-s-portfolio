import type { Node as DocumentNode } from '@tiptap/pm/model'
import type { Editor, JSONContent } from '@tiptap/core'

/** Markdown 将强调边缘空白移出标记；仅比较副本，不删除编辑器中的 Mark 或任何字符。 */
function inlineMeaning(content: JSONContent[] | undefined): JSONContent[] | undefined {
  const result: JSONContent[] = []
  for (const node of content ?? []) {
    if (node.type !== 'text') {
      result.push(node)
      continue
    }
    for (const text of node.text?.match(/[ \t]+|[^ \t]+/g) ?? []) {
      const marks = node.marks?.filter(
        (mark) => !(/^[ \t]+$/.test(text) && ['bold', 'italic'].includes(mark.type)),
      )
      const previous = result.at(-1)
      if (
        previous?.type === 'text' &&
        JSON.stringify(previous.marks ?? []) === JSON.stringify(marks ?? [])
      ) {
        previous.text += text
      } else {
        result.push({ type: 'text', text, ...(marks?.length ? { marks } : {}) })
      }
    }
  }
  return content ? result : undefined
}
/** 空白段落承载输入位置，不是额外的 Markdown 正文块。 */
export function isEditorPlaceholderParagraph(node: JSONContent): boolean {
  return (
    node.type === 'paragraph' &&
    (!node.content?.length ||
      node.content.every(
        (child) =>
          child.type === 'text' &&
          /^[ \t]*$/.test(child.text ?? '') &&
          !child.marks?.some((mark) => !['bold', 'italic'].includes(mark.type)),
      ))
  )
}
/** 代码快照不影响语义；真实节点、文字、链接、资源属性与公式始终保留校验。 */
export function editorContentMeaning(node: JSONContent): unknown {
  const {
    originalFence: _fence,
    originalText: _text,
    originalLanguage: _language,
    ...attrs
  } = node.attrs ?? {}
  // Enter 产生的空段落属于编辑占位，Markdown 不区分其数量；不能因此阻断继续输入。
  const children = inlineMeaning(node.content)
  if (node.type === 'paragraph' || node.type === 'heading') {
    // 段末换行是等待下一行输入的光标占位；中间换行仍逐个参与结构校验。
    while (children?.length) {
      const last = children.at(-1)!
      if (last.type === 'hardBreak') {
        children.pop()
        continue
      }
      // 段末软换行在 lexer 中暂时恢复成两个空格；这些尾白不改变 Markdown 正文语义。
      // 链接、行内代码和其他原子内容不参与此规范化。
      if (
        last.type === 'text' &&
        !last.marks?.some((mark) => !['bold', 'italic'].includes(mark.type))
      ) {
        last.text = last.text?.replace(/[ \t]+$/, '')
        if (!last.text) {
          children.pop()
          continue
        }
      }
      break
    }
  }
  const content = children
    ?.filter((child) => !isEditorPlaceholderParagraph(child))
    .map(editorContentMeaning)
  return {
    type: node.type,
    text: node.text,
    attrs: Object.fromEntries(Object.entries(attrs).filter(([, value]) => value !== null)),
    marks:
      node.type === 'hardBreak'
        ? undefined
        : node.marks?.map((mark) => ({
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
  const markdown = serializeEditorDocument(editor, editor.state.doc)
  if (markdown !== emittedMarkdown) {
    throw new Error('当前转换结果已过期，请重试。')
  }
  return markdown
}

/** 候选事务提交前同样走真实序列化和往返校验，不拿旧 Markdown 比较新文档。 */
export function serializeEditorDocument(
  editor: Pick<Editor, 'markdown' | 'schema'>,
  document: DocumentNode,
): string {
  if (!editor.markdown) {
    throw new Error('Markdown 编辑能力尚未就绪。')
  }
  const markdown = editor.markdown.serialize(document.toJSON())
  const restored = editor.schema.nodeFromJSON(editor.markdown.parse(markdown))
  // ProseMirror 的空文档必须有一个空段落；Markdown 空串与该占位段落语义相同。
  const restoredDocument = restored.childCount
    ? restored
    : editor.schema.topNodeType.createAndFill()
  if (!restoredDocument) {
    throw new Error('无法恢复 Markdown 文档结构，请使用源码模式编辑。')
  }
  if (
    JSON.stringify(editorContentMeaning(restoredDocument.toJSON())) !==
    JSON.stringify(editorContentMeaning(document.toJSON()))
  ) {
    throw new Error('此次富文本操作无法可靠转换为 Markdown，请撤销操作或在源码模式编辑。')
  }
  return markdown
}
