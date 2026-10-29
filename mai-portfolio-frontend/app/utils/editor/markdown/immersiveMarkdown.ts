import type { Token, TokensList } from 'marked'
import { MarkdownManager } from '@tiptap/markdown'
import StarterKit from '@tiptap/starter-kit'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT } from './markdownExtensions'
import { createBlogMarkdownOptions } from './markdownCompatibility'

/** Showcase 沿用同一个 lexer；无法独立编辑的行内 HTML 连同所属完整块保留原文。 */
export function createImmersiveMarkdownOptions() {
  const options = createBlogMarkdownOptions()
  const instance = options.marked
  const BaseLexer = instance.Lexer
  // 同一会话内，预检、真实解析和往返检查会重复读取相同源码。
  // 只缓存最近两份确定性 token；逐次复制，解析器不能修改下一次校验的输入。
  const lexed = new WeakMap<object, Map<string, TokensList>>()
  class ProtectedLexer<ParserOutput = string, RendererOutput = string> extends BaseLexer<
    ParserOutput,
    RendererOutput
  > {
    override lex(source: string) {
      let recent = lexed.get(this.options)
      if (!recent) {
        recent = new Map()
        lexed.set(this.options, recent)
      }
      const cached = recent.get(source)
      if (cached) {
        return structuredClone(cached)
      }
      const tokens = super.lex(source)
      // CommonMark 允许同类强调嵌套，但 ProseMirror 的 Mark 集合不允许重复同类 Mark。
      // 仅规范化解析 token 的冗余嵌套，原始 Markdown 与其他强调层次保持不变。
      const inline = (items: Token[], active = new Set<string>()): Token[] =>
        items.flatMap((item) => {
          const emphasis = ['strong', 'em', 'del'].includes(item.type ?? '')
          const next = new Set(active)
          if (emphasis) {
            next.add(item.type ?? '')
          }
          if ('tokens' in item && item.tokens) {
            item.tokens = inline(item.tokens, next)
          }
          if (emphasis && active.has(item.type ?? '')) {
            return 'tokens' in item ? (item.tokens ?? []) : []
          }
          return [item]
        })
      instance.walkTokens(tokens, (token) => {
        // 空列表项仍需要合法的段落光标容器；不会向 Markdown 添加正文。
        if (token.type === 'list_item' && !token.tokens?.length) {
          token.tokens = [{ type: 'paragraph', raw: '', text: '', tokens: [] }]
        }
        if ('tokens' in token && token.tokens) {
          token.tokens = inline(
            token.tokens,
            new Set(['strong', 'em', 'del'].includes(token.type) ? [token.type] : []),
          )
        }
      })
      tokens.forEach((token, index) => {
        if (token.type === 'code' || token.type === 'space' || token.type === 'html') {
          return
        }
        let protectedInline = false
        instance.walkTokens([token], (child) => {
          if (child.type === 'paragraph' && /^\d+\\?[.)]\s/.test(child.text ?? '')) {
            protectedInline = true
          }
          if (child.type === 'list' && /\n[ \t]{6,}\S/.test(child.raw ?? '')) {
            protectedInline = true
          }
          if (child.type === 'em') {
            instance.walkTokens([child], (nested) => {
              if (nested.type === 'strong') {
                protectedInline = true
              }
            })
          }
          if (['strong', 'em', 'del'].includes(child.type)) {
            // 当前 Markdown 序列化器不能保留原子公式上的外层 Mark；原块保真保护，明确从源码编辑。
            instance.walkTokens([child], (nested) => {
              if (nested.type === 'preservedMathInline' || nested.type === 'image') {
                protectedInline = true
              }
            })
          }
          if (child.type === 'html' && !child.block) {
            protectedInline = true
          }
        })
        if (protectedInline) {
          tokens[index] = { type: 'html', block: true, pre: false, raw: token.raw, text: token.raw }
        }
      })
      recent.set(source, structuredClone(tokens))
      if (recent.size > 2) {
        recent.delete(recent.keys().next().value!)
      }
      return tokens
    }
  }
  instance.Lexer = ProtectedLexer
  instance.lexer = (source, lexerOptions) =>
    new ProtectedLexer(lexerOptions ?? instance.defaults).lex(source)
  return options
}
export function createImmersiveMarkdownManager() {
  return new MarkdownManager({
    extensions: [StarterKit.configure(EDITOR_STARTER_KIT), ...BLOG_EDITOR_EXTENSIONS],
    ...createImmersiveMarkdownOptions(),
  })
}
