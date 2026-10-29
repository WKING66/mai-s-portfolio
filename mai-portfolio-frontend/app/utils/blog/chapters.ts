import type { ElementNode, Node } from 'comark'
import type { TocLink } from 'comark/plugins/toc'
import { textContent } from 'comark/utils'
import type { InjectionKey, Ref } from 'vue'

export const BLOG_CHAPTER_STATE: InjectionKey<{
  idPrefix: string
  collapsed: Ref<Set<string>>
  ready: Ref<Set<string> | null>
  serverRendered: boolean
  hydrated: (id: string) => void
  toggle: (id: string) => void
  observeBody: (element: HTMLElement, measured: (height: number) => void) => () => void
}> = Symbol('blog-chapters')
export interface BlogChapter extends TocLink {
  heading: ElementNode
  body: Node[]
  children: BlogChapter[]
  parentId?: string
}

/** 阅读与编辑共用边界：同级/更高标题结束上一章，引用或列表内部标题由调用方排除。 */
export function headingParent<T extends { depth: number }>(
  ancestors: T[],
  depth: number,
): T | undefined {
  while (ancestors.length && ancestors.at(-1)!.depth >= depth) {
    ancestors.pop()
  }
  return ancestors.at(-1)
}

/** 单次遍历 Comark 顶层 AST，章节直到下一个同级或更高标题；引用/列表内部标题不割裂块。 */
export function buildBlogChapters(nodes: Node[]) {
  // 1. 栈维护当前标题的祖先链，只按正文结构构建，不读取折叠状态。
  const chapters: BlogChapter[] = [],
    preamble: Node[] = [],
    ancestorStack: BlogChapter[] = []
  const byId = new Map<string, BlogChapter>()
  for (const node of nodes) {
    if (Array.isArray(node) && /^h[1-6]$/.test(String(node[0]))) {
      const depth = Number(String(node[0]).slice(1))
      const parentChapter = headingParent(ancestorStack, depth)
      const chapter: BlogChapter = {
        id: String(node[1].id),
        text: String(node[1]['data-toc-label'] ?? textContent(node)),
        depth,
        heading: node as ElementNode,
        body: [],
        children: [],
        parentId: parentChapter?.id,
      }
      ;(parentChapter?.children ?? chapters).push(chapter)
      byId.set(chapter.id, chapter)
      ancestorStack.push(chapter)
    } else {
      ;(ancestorStack.at(-1)?.body ?? preamble).push(node)
    }
  }
  // 2. 将章节映射为稳定的组件节点，原 AST 保持不变，正文槽沿用原节点。
  function render(chapter: BlogChapter): ElementNode {
    return [
      'blog-chapter',
      { chapterId: chapter.id, title: chapter.text, level: chapter.depth },
      ['template', { name: 'heading' }, chapter.heading],
      ['template', { name: 'default' }, ...chapter.body, ...chapter.children.map(render)],
    ]
  }
  return { chapters, byId, nodes: [...preamble, ...chapters.map(render)] }
}

export function visibleToc(
  items: TocLink[],
  contentCollapsed: Set<string>,
  tocCollapsed: Set<string>,
): TocLink[] {
  return items.flatMap((item) => [
    item,
    ...(contentCollapsed.has(item.id) || tocCollapsed.has(item.id)
      ? []
      : visibleToc(item.children || [], contentCollapsed, tocCollapsed)),
  ])
}

export function chapterAncestors(id: string, chapters: ReadonlyMap<string, { parentId?: string }>) {
  const ancestorIds: string[] = []
  let parentId = chapters.get(id)?.parentId
  while (parentId) {
    ancestorIds.push(parentId)
    parentId = chapters.get(parentId)?.parentId
  }
  return ancestorIds
}
