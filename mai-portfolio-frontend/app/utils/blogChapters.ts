import type { ElementNode, Node } from 'comark'
import type { TocLink } from 'comark/plugins/toc'
import { textContent } from 'comark/utils'
import type { InjectionKey, Ref } from 'vue'
import { countBlogReading } from './blogReadingProbe'

export const BLOG_CHAPTER_STATE: InjectionKey<{ idPrefix: string; collapsed: Ref<Set<string>>; ready: Ref<Set<string> | null>; serverRendered: boolean; hydrated: (id: string) => void; toggle: (id: string) => void; observeBody: (element: HTMLElement, measured: (height: number) => void) => () => void }> = Symbol('blog-chapters')
export interface BlogChapter extends TocLink { heading: ElementNode; body: Node[]; children: BlogChapter[]; parentId?: string }

/** 单次遍历 Comark 顶层 AST，章节直到下一个同级或更高标题；引用/列表内部标题不割裂块。 */
export function buildBlogChapters(nodes: Node[]) {
  countBlogReading('chapterTree')
  const chapters: BlogChapter[] = [], preamble: Node[] = [], stack: BlogChapter[] = []
  const byId = new Map<string, BlogChapter>()
  for (const node of nodes) {
    if (Array.isArray(node) && /^h[1-6]$/.test(String(node[0]))) {
      const depth = Number(String(node[0]).slice(1))
      while (stack.length && stack.at(-1)!.depth >= depth) stack.pop()
      const parent = stack.at(-1)
      const chapter: BlogChapter = { id: String(node[1].id), text: String(node[1]['data-toc-label'] ?? textContent(node)), depth, heading: node as ElementNode, body: [], children: [], parentId: parent?.id }
      ;(parent?.children ?? chapters).push(chapter)
      byId.set(chapter.id, chapter); stack.push(chapter)
    } else (stack.at(-1)?.body ?? preamble).push(node)
  }
  function render(chapter: BlogChapter): ElementNode {
    return ['blog-chapter', { chapterId: chapter.id, title: chapter.text, level: chapter.depth },
      ['template', { name: 'heading' }, chapter.heading],
      ['template', { name: 'default' }, ...chapter.body, ...chapter.children.map(render)]]
  }
  return { chapters, byId, nodes: [...preamble, ...chapters.map(render)] }
}

export function visibleToc(items: TocLink[], contentCollapsed: Set<string>, tocCollapsed: Set<string>): TocLink[] {
  return items.flatMap(item => [item, ...(contentCollapsed.has(item.id) || tocCollapsed.has(item.id) ? [] : visibleToc(item.children || [], contentCollapsed, tocCollapsed))])
}

export function chapterAncestors(id: string, chapters: Map<string, BlogChapter>) {
  const parents: string[] = []
  let parent = chapters.get(id)?.parentId
  while (parent) { parents.push(parent); parent = chapters.get(parent)?.parentId }
  return parents
}
