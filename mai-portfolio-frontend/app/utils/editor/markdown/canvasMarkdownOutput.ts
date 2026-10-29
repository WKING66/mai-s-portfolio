import { MarkdownManager } from '@tiptap/markdown'
import type { JSONContent } from '@tiptap/core'
import { toMarkdown } from 'mdast-util-to-markdown'

const STYLE_TYPES = ['italic', 'bold']
const LIST_TYPES = new Set(['orderedList', 'bulletList', 'taskList'])
type PhrasingParent = Extract<
  Parameters<typeof toMarkdown>[0],
  { type: 'paragraph' | 'strong' | 'emphasis' | 'link' }
>
type InlineGroup = Extract<PhrasingParent, { type: 'strong' | 'emphasis' | 'link' }>

/** 保存/导出专用适配；只使用公开入口，其他块和链接、代码等仍由原扩展输出。 */
export class CanvasMarkdownWriter extends MarkdownManager {
  override renderNodeToMarkdown(
    node: JSONContent,
    parent?: JSONContent,
    index = 0,
    level = 0,
    meta: Record<string, unknown> = {},
  ): string {
    if (node.type === 'paragraph' || node.type === 'heading') {
      const inline = this.renderCanvasInline(node.content ?? [], node)
      if (node.type === 'heading') {
        return '#'.repeat(Number(node.attrs?.level) || 1) + (inline ? ' ' + inline : '')
      }
      // 字面编号/标题/引用/分隔线不能在下一次解析时变成新块。
      return (
        inline
          .replace(/^( {0,3})(\d{1,9})([.)])(?=[ \t]|$)/gm, '$1$2\\$3')
          .replace(/^( {0,3})(#{1,6})(?=[ \t]|$)/gm, '$1\\$2')
          .replace(/^( {0,3})([+>-])(?=[ \t]|$)/gm, '$1\\$2')
          .replace(/^( {0,3})(-)(?=(?:[ \t]*-){2,}[ \t]*$)/gm, '$1\\$2')
          // 段内的实际行首空白与 Markdown 的块缩进分开，防止被列表去缩进吞掉。
          .replace(/^[ \t]+/gm, (space) =>
            Array.from(space, (char) => (char === '\t' ? '&#9;' : '&#32;')).join(''),
          )
      )
    }
    if (node.type && LIST_TYPES.has(node.type)) {
      return (node.content ?? [])
        .map((item, itemIndex) => {
          let prefix = '- '
          if (node.type === 'orderedList') {
            prefix = String(Number(node.attrs?.start ?? 1) + itemIndex) + '. '
          } else if (node.type === 'taskList') {
            prefix = '- [' + (item.attrs?.checked ? 'x' : ' ') + '] '
          }
          const content = (item.content ?? [])
            .map((child, childIndex) =>
              this.renderNodeToMarkdown(child, item, childIndex, level + 1),
            )
            .join('\n\n')
          // GFM 的复选框属于首行正文；后续块只按列表标记缩进，不能把 [ ] 再计入缩进。
          const continuationIndent = node.type === 'taskList' ? 2 : prefix.length
          const emptyItem = !content.trim()
          return (
            // 空复选框仍需保留 ] 后的语法空格，不能按空列表标记裁剪。
            (emptyItem && node.type !== 'taskList' ? prefix.trimEnd() : prefix) +
            content
              .split('\n')
              .map((line, lineIndex) => (lineIndex ? ' '.repeat(continuationIndent) : '') + line)
              .join('\n') +
            // 空子列表后留出块边界；否则下一外层列表项会使空标记被解析为普通文字。
            (emptyItem ? '\n' : '')
          )
        })
        .join('\n')
    }
    return super.renderNodeToMarkdown(node, parent, index, level, meta)
  }

  private renderCanvasInline(nodes: JSONContent[], parent: JSONContent): string {
    const pieces = nodes.flatMap((node) => {
      if (
        node.type !== 'text' ||
        node.marks?.some((mark) => ['code', 'link'].includes(mark.type))
      ) {
        return [node]
      }
      // 只移出格式两端空白，内部空白继续留在同一格式范围，避免无意义拆分。
      const [, leading = '', body = '', trailing = ''] = (node.text ?? '').match(
        /^([ \t\n]*)([\s\S]*?)([ \t\n]*)$/,
      )!
      const plainWhitespace = (value: string): JSONContent => ({
        ...node,
        text: value,
        marks: node.marks?.filter((mark) => !['bold', 'italic'].includes(mark.type)),
      })
      return [
        ...(leading ? [plainWhitespace(leading)] : []),
        ...(body ? [{ ...node, text: body }] : []),
        ...(trailing ? [plainWhitespace(trailing)] : []),
      ]
    })
    let active: string[] = []
    const root: PhrasingParent = { type: 'paragraph', children: [] }
    const stack: PhrasingParent[] = [root]
    pieces.forEach((node, index) => {
      const link = node.marks?.find((mark) => mark.type === 'link')
      const next = [
        ...(link ? ['link:' + JSON.stringify([link.attrs?.href, link.attrs?.title])] : []),
        ...STYLE_TYPES.filter((type) => node.marks?.some((mark) => mark.type === type)),
      ]
      let shared = 0
      while (shared < active.length && active[shared] === next[shared]) {
        shared++
      }
      stack.splice(shared + 1)
      for (const type of next.slice(shared)) {
        const group: InlineGroup = type.startsWith('link:')
          ? {
              type: 'link',
              url: String(link!.attrs?.href ?? ''),
              title: link!.attrs?.title || null,
              children: [],
            }
          : {
              type: type === 'italic' ? 'emphasis' : 'strong',
              children: [],
            }
        stack.at(-1)!.children.push(group)
        stack.push(group)
      }
      active = next
      const marks = node.marks?.filter(
        (mark) => !STYLE_TYPES.includes(mark.type) && mark.type !== 'link',
      )
      const previous = pieces[index - 1]
      if (
        node.type === 'text' &&
        !marks?.length &&
        /^\d/.test(node.text ?? '') &&
        previous?.type === 'preservedMathInline' &&
        String(previous.attrs?.source).startsWith('$') &&
        !String(previous.attrs?.source).startsWith('$$')
      ) {
        // 画布已有公式后接数字，用标准实体分开词法边界，不能误变成货币或拒绝保存。
        stack
          .at(-1)!
          .children.push(
            { type: 'inlineCode', value: '&#' + node.text!.charCodeAt(0) + ';' },
            { type: 'text', value: node.text!.slice(1) },
          )
        return
      }
      // 普通文本交给标准输出器判断分隔符边界；复杂行内节点仍保留原处理器结果。
      // inlineCode 仅作不透明生成片段载体，处理器原样输出；不改变 PM 节点类型。
      stack
        .at(-1)!
        .children.push(
          node.type === 'text' && !marks?.length
            ? { type: 'text', value: node.text ?? '' }
            : { type: 'inlineCode', value: super.renderNodes([{ ...node, marks }], parent) },
        )
    })
    return toMarkdown(root, {
      strong: '_',
      // 普通文字不能在重新解析时变为数学节点、GFM 表格或删除线；真实原子节点仍原样输出。
      unsafe: [
        { character: '$', inConstruct: 'phrasing' },
        { character: '|', inConstruct: 'phrasing' },
        { character: '~', inConstruct: 'phrasing' },
      ],
      handlers: { inlineCode: (node) => (node.type === 'inlineCode' ? node.value : '') },
    }).replace(/\n$/, '')
  }
}
