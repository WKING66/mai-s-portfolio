import type { Node } from '@tiptap/pm/model'
import type { Mapping } from '@tiptap/pm/transform'
import type { TocLink } from 'comark/plugins/toc'

export interface EditorHeading {
  id: string
  text: string
  depth: number
  position: number
}

/** 目录直接读取现有 ProseMirror 文档，绝不为标题再次解析 Markdown。 */
export function collectEditorHeadings(
  document: Node,
  previous: EditorHeading[],
  mapping: Mapping | undefined,
  createId: () => string,
): EditorHeading[] {
  const identities = new Map<number, string>()
  for (const heading of previous) {
    const mapped = mapping?.mapResult(heading.position)
    if (!mapped?.deleted) {
      identities.set(mapped?.pos ?? heading.position, heading.id)
    }
  }
  const headings: EditorHeading[] = []
  document.descendants((node, position) => {
    if (node.type.name === 'heading') {
      headings.push({
        id: identities.get(position) ?? createId(),
        text: node.textContent || '未命名标题',
        depth: Number(node.attrs.level),
        position,
      })
    }
    // 段落、代码和标题的行内节点不可能再包含章节标题，不逐字遍历。
    return !node.isTextblock
  })
  return headings
}

export function editorHeadingTree(headings: EditorHeading[], previous: TocLink[] = []): TocLink[] {
  const roots: TocLink[] = []
  const ancestors: TocLink[] = []
  for (const heading of headings) {
    const link: TocLink = { id: heading.id, text: heading.text, depth: heading.depth, children: [] }
    while (ancestors.length && ancestors[ancestors.length - 1]!.depth >= heading.depth) {
      ancestors.pop()
    }
    const parent = ancestors[ancestors.length - 1]
    if (parent) {
      parent.children!.push(link)
    } else {
      roots.push(link)
    }
    ancestors.push(link)
  }
  if (!previous.length) {
    return roots
  }
  const byId = new Map<string, TocLink>()
  const remember = (items: TocLink[]) =>
    items.forEach((item) => {
      byId.set(item.id, item)
      remember(item.children ?? [])
    })
  remember(previous)
  // 保持无变化分支的身份，让目录行已有的 v-memo 只刷新真正变化的标题及祖先。
  function share(items: TocLink[], prior: TocLink[]): TocLink[] {
    const next = items.map((item) => {
      const old = byId.get(item.id)
      const children = share(item.children ?? [], old?.children ?? [])
      return old && old.text === item.text && old.depth === item.depth && children === old.children
        ? old
        : { ...item, children }
    })
    return next.length === prior.length && next.every((item, index) => item === prior[index])
      ? prior
      : next
  }
  return share(roots, previous)
}
