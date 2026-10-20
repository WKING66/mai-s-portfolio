import { Marked, type marked } from 'marked'
import { MarkdownManager } from '@tiptap/markdown'
import StarterKit from '@tiptap/starter-kit'
import { getSchema, decodeHtmlEntities, type MarkdownToken } from '@tiptap/core'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT, isEditorUrl } from './markdownExtensions'

export const RICH_TEXT_CHARACTER_LIMIT = 60000
export const editorSchema = getSchema([
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

function localResourceAllowed(uri: string, allowed: ReadonlySet<string>): boolean {
  if (allowed.has(uri)) {
    return true
  }
  try {
    return allowed.has(decodeURI(uri))
  } catch {
    return false
  }
}

function isParagraphPlaceholder(token: MarkdownToken): boolean {
  return (
    token.type === 'paragraph' &&
    (!token.tokens?.length ||
      (token.tokens.length === 1 &&
        token.tokens[0]?.type === 'text' &&
        ['&nbsp;', '\u00a0'].includes(token.tokens[0].text ?? '')))
  )
}

function tokenMeaning(token: MarkdownToken): unknown {
  const children = (tokens: MarkdownToken[] | undefined) => {
    // 转义字符与实体只是 Markdown 的不同拼写；比较解码后的连续文本，仍逐层比较格式和块结构。
    const normalized: MarkdownToken[] = []
    for (const item of tokens ?? []) {
      if (item.type === 'space' || isParagraphPlaceholder(item)) {
        continue
      }
      if ((item.type === 'text' && !item.tokens?.length) || item.type === 'escape') {
        const text =
          item.type === 'escape' ? (item.text ?? '') : decodeHtmlEntities(item.text ?? '')
        const previous = normalized.at(-1)
        if (previous?.type === 'text' && !previous.tokens?.length) {
          previous.text += text
        } else {
          normalized.push({ type: 'text', text, raw: text })
        }
      } else {
        const previous = normalized.at(-1)
        if (
          previous &&
          previous.type === item.type &&
          ['strong', 'em', 'del'].includes(item.type ?? '')
        ) {
          previous.tokens = [...(previous.tokens ?? []), ...(item.tokens ?? [])]
        } else {
          normalized.push({ ...item })
        }
      }
    }
    const meaningful = normalized.map(tokenMeaning)
    // lexer 的空 children 数组与未生成 children 均表示没有子内容，不是正文信息差异。
    return meaningful?.length ? meaningful : undefined
  }
  // HTML 块尾空行只分隔相邻块；正文内的空格、属性和源码仍逐字参与校验。
  const text = token.type === 'html' && token.block ? token.text?.replace(/\n+$/, '') : token.text
  return {
    // tight/loose 列表中 lexer 分别使用 text/paragraph 表示同一个段落。
    type: token.type === 'text' && token.tokens?.length ? 'paragraph' : token.type,
    text: token.tokens?.length ? undefined : text,
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

export function inspectBlogMarkdown(
  source: string,
  manager = createBlogMarkdownManager(),
  allowedLocalResources: ReadonlySet<string> = new Set(),
): string {
  if (source.length > RICH_TEXT_CHARACTER_LIMIT) {
    return '正文较长，使用源码模式以避免大型编辑文档阻塞浏览器。仍可在阅读预览中查看当前草稿。'
  }
  try {
    const tokens = manager.instance.lexer(source)
    // 1. 跨块引用定义无法安全独立保护；整篇保留源码，避免丢失定义和引用关系。
    if (Object.keys(tokens.links).length) {
      return '含引用式链接或脚注定义，请在源码模式编辑，以保留定义和引用关系。'
    }
    let unsafe = false
    manager.instance.walkTokens(tokens, (token) => {
      if (
        (token.type === 'html' && !token.block) ||
        ('href' in token &&
          !isEditorUrl(String(token.href)) &&
          // CommonMark 空目标链接合法；新增链接输入仍受 isEditorUrl 限制。
          !(token.type === 'link' && token.href === '') &&
          !(
            token.type === 'image' &&
            localResourceAllowed(String(token.href), allowedLocalResources)
          ))
      ) {
        unsafe = true
      }
    })
    if (unsafe) {
      return '含无法独立保护的行内 HTML、特殊路径或不安全链接，请在源码模式编辑；这些内容不会直接进入编辑器 DOM。'
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
          .filter((token) => token.type !== 'space' && !isParagraphPlaceholder(token))
          .map(tokenMeaning),
      )
    const sourceMeaning = JSON.stringify(
      tokens
        .filter((token) => token.type !== 'space' && !isParagraphPlaceholder(token))
        .map(tokenMeaning),
    )
    if (sourceMeaning !== meaning(output)) {
      return '当前内容包含无法可靠往返转换的格式，已保留完整 Markdown，请使用源码模式编辑。'
    }
    return ''
  } catch {
    return '富文本兼容性检查失败，已保留完整 Markdown，请使用源码模式编辑。'
  }
}
