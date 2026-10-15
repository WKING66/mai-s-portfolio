import { Marked, type marked } from 'marked'
import { MarkdownManager } from '@tiptap/markdown'
import StarterKit from '@tiptap/starter-kit'
import { getSchema, type MarkdownToken } from '@tiptap/core'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT, isEditorUrl } from './markdownExtensions'

export const RICH_TEXT_CHARACTER_LIMIT = 60000
const editorSchema = getSchema([
  StarterKit.configure(EDITOR_STARTER_KIT),
  ...BLOG_EDITOR_EXTENSIONS,
])

/** 每个编辑会话独立注册 tokenizer，避免多次打开正文后在全局 marked 累积扩展。 */
export function createBlogMarkdownOptions() {
  // TipTap 类型沿用 marked 的可调用外观，运行时仅使用 Marked 实例的 Lexer/use/parse 等方法。
  return { marked: new Marked() as unknown as typeof marked, markedOptions: { gfm: true } }
}

/** 复用 TipTap 自带的 Markdown lexer；这里只校验 token 语义，不另写 Markdown 解析器。 */
export function createBlogMarkdownManager() {
  return new MarkdownManager({
    extensions: [StarterKit.configure(EDITOR_STARTER_KIT), ...BLOG_EDITOR_EXTENSIONS],
    ...createBlogMarkdownOptions(),
  })
}

function tokenMeaning(token: MarkdownToken): unknown {
  const children = (tokens: MarkdownToken[] | undefined) =>
    tokens?.filter((item) => item.type !== 'space').map(tokenMeaning)
  return {
    type: token.type,
    text: token.tokens?.length ? undefined : token.text,
    depth: token.depth,
    ordered: token.ordered,
    start: token.ordered ? token.start : undefined,
    checked: token.checked,
    lang: token.lang,
    href: token.href,
    title: token.title || undefined,
    align: token.align,
    tokens: children(token.tokens),
    items: children(token.items),
    header: token.header?.map((cell: MarkdownToken) => children(cell.tokens)),
    rows: token.rows?.map((row: MarkdownToken[]) => row.map((cell) => children(cell.tokens))),
  }
}

export function inspectBlogMarkdown(source: string, manager = createBlogMarkdownManager()): string {
  if (source.length > RICH_TEXT_CHARACTER_LIMIT) {
    return '正文较长，使用源码模式以避免大型编辑文档阻塞浏览器。仍可在阅读预览中查看当前草稿。'
  }
  try {
    const tokens = manager.instance.lexer(source)
    // 1. HTML、引用定义和不安全 URL 不交给富文本 HTML 解析，原文留在源码模式。
    if (Object.keys(tokens.links).length) {
      return '含引用式链接或脚注定义，请在源码模式编辑，以保留定义和引用关系。'
    }
    let unsafe = false
    manager.instance.walkTokens(tokens, (token) => {
      if (token.type === 'html' || ('href' in token && !isEditorUrl(String(token.href)))) {
        unsafe = true
      }
    })
    if (unsafe) {
      return '含原始 HTML、特殊路径或不安全链接，请在源码模式编辑；这些内容不会直接进入编辑器 DOM。'
    }
    // 2. 真正转换并比较语义；不能仅因解析未报错就认为表格、资源或公式没有丢失。
    const document = manager.parse(source)
    if (document.content?.length) {
      editorSchema.nodeFromJSON(document).check()
    }
    const output = manager.serialize(document)
    const meaning = (markdown: string) =>
      JSON.stringify(
        manager.instance
          .lexer(markdown)
          .filter((token) => token.type !== 'space')
          .map(tokenMeaning),
      )
    if (meaning(source) !== meaning(output)) {
      return '当前内容包含无法可靠往返转换的格式，已保留完整 Markdown，请使用源码模式编辑。'
    }
    return ''
  } catch {
    return '富文本兼容性检查失败，已保留完整 Markdown，请使用源码模式编辑。'
  }
}
