import type { ComarkPlugin, MarkdownExitPlugin } from 'comark'
import { matchMathSource } from './mathSyntax'

/** 在 Markdown token 层识别公式，先于 escape/link/image；不处理或重写只读 DOM。 */
export const blogMathSyntax: ComarkPlugin = {
  name: 'blog-math-syntax',
  markdownItPlugins: [
    (md: Parameters<MarkdownExitPlugin>[0]) => {
      md.inline.ruler.before('escape', 'blog-math-inline', (state, silent) => {
        const match = matchMathSource(state.src.slice(state.pos, state.posMax), false)
        if (!match) {
          return false
        }
        if (!silent) {
          const token = state.push('math_inline', 'math', 0)
          token.content = match.content
        }
        state.pos += match.raw.length
        return true
      })
      // 替换默认美元行内规则，避免它在共享边界拒绝后又吞掉货币或转义。
      md.inline.ruler.disable('math_inline')
      md.block.ruler.before(
        'fence',
        'blog-math-block',
        (state, startLine, endLine, silent) => {
          if (state.sCount[startLine]! - state.blkIndent >= 4) {
            return false
          }
          const start = state.bMarks[startLine]! + state.tShift[startLine]!
          if (!state.src.startsWith('$$', start) && !state.src.startsWith('\\[', start)) {
            return false
          }
          // getLines 沿用 Markdown 引用/列表的缩进语义，不能按原字符串跨出当前容器。
          let limit = startLine + 1
          while (
            limit < endLine &&
            (state.isEmpty(limit) || state.sCount[limit]! >= state.blkIndent)
          ) {
            limit++
          }
          const source = state
            .getLines(startLine, limit, state.blkIndent, true)
            .replace(/^ {0,3}/, '')
          const match = matchMathSource(source, true)
          if (!match) {
            return false
          }
          if (silent) {
            return true
          }
          const lines = match.raw.split('\n').length - (match.raw.endsWith('\n') ? 1 : 0)
          const token = state.push('math_block', 'math', 0)
          token.content = match.content
          token.block = true
          token.map = [startLine, startLine + lines]
          // 下游受控渲染沿用 class 判断块公式。
          token.attrSet('class', 'math-block')
          state.line = startLine + lines
          return true
        },
        { alt: ['paragraph', 'reference', 'blockquote', 'list'] },
      )
      md.block.ruler.disable('math_block')
    },
  ],
}
