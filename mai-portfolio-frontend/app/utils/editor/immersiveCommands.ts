import type { Editor, ChainedCommands } from '@tiptap/core'
import { PluginKey } from '@tiptap/pm/state'
import { immersiveInsertionProblem, immersiveDocumentProblem } from './immersiveCapabilities'
import type { Node } from '@tiptap/pm/model'

/** 图片可以位于段落中，但段落包装不代表图片可以转换成文本标题。 */
export function canConvertImmersiveTextBlock(node: Node | null) {
  if (!node || !['paragraph', 'heading'].includes(node.type.name)) {
    return false
  }
  let hasImage = false
  node.descendants((child) => {
    hasImage ||= child.type.name === 'image'
    return !hasImage
  })
  return !hasImage
}

export interface ImmersiveMenuTarget {
  kind: 'block' | 'slash'
  from: number
  to: number
  query: string
}
export interface ImmersiveCommandItem {
  kind: string
  label: string
  icon: string
  aliases: string[]
  level?: 1 | 2 | 3 | 4 | 5 | 6
  alertKind?: string
}
export const IMMERSIVE_MENU_KEY = new PluginKey<ImmersiveMenuTarget | null>(
  'immersive-command-menu',
)
export const IMMERSIVE_COMMANDS: ImmersiveCommandItem[] = [
  { kind: 'paragraph', label: '正文', icon: 'i-lucide-text', aliases: ['text', 'paragraph'] },
  ...([1, 2, 3, 4, 5, 6] as const).map((level) => ({
    kind: 'heading',
    level,
    label: '标题 ' + level,
    icon: 'i-lucide-heading',
    aliases: ['h' + level, 'heading' + level],
  })),
  { kind: 'bulletList', label: '无序列表', icon: 'i-lucide-list', aliases: ['list', 'ul'] },
  { kind: 'orderedList', label: '有序列表', icon: 'i-lucide-list-ordered', aliases: ['ol'] },
  { kind: 'blockquote', label: '引用', icon: 'i-lucide-quote', aliases: ['quote'] },
  ...['NOTE', 'TIP', 'IMPORTANT', 'WARNING', 'CAUTION'].map((alertKind) => ({
    kind: 'alert',
    alertKind,
    label: alertKind + ' 提示块',
    icon: 'i-lucide-info',
    aliases: ['alert', alertKind.toLowerCase(), '提示'],
  })),
  { kind: 'codeBlock', label: '代码块', icon: 'i-lucide-code', aliases: ['code'] },
  { kind: 'table', label: '表格', icon: 'i-lucide-table', aliases: ['table'] },
  {
    kind: 'mermaid',
    label: 'Mermaid 图表',
    icon: 'i-lucide-git-branch',
    aliases: ['mermaid', '图表', '流程图'],
  },
  { kind: 'mathInline', label: '行内公式', icon: 'i-lucide-sigma', aliases: ['inline', 'math'] },
  { kind: 'math', label: '独立公式', icon: 'i-lucide-square-sigma', aliases: ['formula', 'latex'] },
  { kind: 'horizontalRule', label: '分隔线', icon: 'i-lucide-minus', aliases: ['hr'] },
]
export function openImmersiveMenu(editor: Editor, position: number) {
  editor.view.dispatch(
    editor.state.tr.setMeta(IMMERSIVE_MENU_KEY, {
      kind: 'block',
      from: position,
      to: position,
      query: '',
    }),
  )
}
export function nextImmersiveMenuIndex(disabled: boolean[], current: number, direction: -1 | 1) {
  for (let distance = 1; distance <= disabled.length; distance++) {
    const index = (current + direction * distance + disabled.length) % disabled.length
    if (!disabled[index]) {
      return index
    }
  }
  return -1
}
function insertionChain(
  editor: Editor,
  target: ImmersiveMenuTarget,
  item: ImmersiveCommandItem,
): ChainedCommands | null {
  let chain = editor.chain()
  if (target.kind === 'slash') {
    chain = chain.deleteRange({ from: target.from, to: target.to }).setTextSelection(target.from)
  } else {
    const node = editor.state.doc.nodeAt(target.from)
    if (!node) {
      return null
    }
    // 加号指向已有块时在该位置之前插入；空正文直接复用，取消菜单从不写入临时字符。
    if (node.type.name !== 'paragraph' || node.content.size) {
      chain = chain.insertContentAt(target.from, { type: 'paragraph' })
    }
    chain = chain.setTextSelection(target.from + 1)
  }
  switch (item.kind) {
    case 'paragraph':
      return chain.command(({ tr, commands }) => {
        // 已有正文也是一次成功命令；不能因 setParagraph 的无变化返回值把单元格正文禁用。
        if (tr.selection.$from.parent.type.name === 'paragraph') {
          return true
        }
        return commands.setParagraph()
      })
    case 'heading':
      return chain.setHeading({ level: item.level! })
    case 'bulletList':
      return chain.toggleBulletList()
    case 'orderedList':
      return chain.toggleOrderedList()
    case 'blockquote':
      return chain.toggleBlockquote()
    case 'alert':
      return chain.insertContent({
        type: 'canvasAlert',
        attrs: { kind: item.alertKind },
        content: [{ type: 'paragraph' }],
      })
    case 'codeBlock':
      return chain.setCodeBlock()
    case 'table':
      return chain.insertTable({ rows: 3, cols: 3, withHeaderRow: true })
    case 'mermaid':
      return chain
        .setCodeBlock({ language: 'mermaid' })
        .insertContent({ type: 'text', text: 'flowchart LR\n  A --> B' })
    case 'mathInline':
      return chain.insertContent({ type: 'preservedMathInline', attrs: { source: '$x^2$' } })
    case 'math':
      return chain.insertContent({ type: 'preservedMathBlock', attrs: { source: '$$\nx^2\n$$' } })
    case 'horizontalRule':
      return chain.setHorizontalRule()
    default:
      return null
  }
}
export function immersiveCommandProblem(
  editor: Editor,
  target: ImmersiveMenuTarget,
  item: ImmersiveCommandItem,
) {
  if (!editor.isEditable) {
    return '当前正文只读。'
  }
  const needed = item.kind === 'alert' ? 'canvasAlert' : undefined
  if (needed && !editor.schema.nodes[needed]) {
    return '当前文档未启用此语法。'
  }
  if (target.kind === 'slash') {
    const problem = immersiveInsertionProblem(editor, item.kind)
    if (problem) {
      return problem
    }
  }
  const chain = insertionChain(editor, target, item)
  // 使用真实命令构造候选事务，但不派发、不聚焦、不写历史；避免只靠 CSS 禁用。
  return chain
    ?.command(({ tr }) => {
      tr.setMeta('preventDispatch', true)
      return true
    })
    .run()
    ? ''
    : '当前位置不支持此操作。'
}
export function executeImmersiveCommand(
  editor: Editor,
  target: ImmersiveMenuTarget,
  item: ImmersiveCommandItem,
) {
  let problem = immersiveCommandProblem(editor, target, item)
  if (problem) {
    return problem
  }
  const chain = insertionChain(editor, target, item)
  const result = chain
    ?.command(({ tr }) => {
      try {
        problem = immersiveDocumentProblem(tr.doc)?.reason ?? ''
      } catch (error) {
        problem = error instanceof Error ? error.message : '无法转换为 Markdown。'
      }
      if (problem) {
        tr.setMeta('preventDispatch', true)
      }
      return !problem
    })
    .run()
  if (result) {
    editor.commands.focus(undefined, { scrollIntoView: false })
  }
  return problem || (result ? '' : '当前位置不能执行此操作。')
}
