import { Extension, type Command } from '@tiptap/core'
import type { Node as DocumentNode } from '@tiptap/pm/model'
import { Plugin, PluginKey, TextSelection } from '@tiptap/pm/state'
import { Decoration, DecorationSet } from '@tiptap/pm/view'
import { ReplaceStep } from '@tiptap/pm/transform'
import { chapterAncestors, headingParent } from '../blogChapters'
import { collectEditorHeadings, type EditorHeading } from './editorOutline'

export interface ImmersiveChapter extends EditorHeading {
  bodyFrom: number
  end: number
  parentId?: string
}
interface ChapterState {
  chapters: ImmersiveChapter[]
  collapsed: Set<string>
  decorations: DecorationSet
}
let chapterViewId = 0
export const IMMERSIVE_CHAPTER_KEY = new PluginKey<ChapterState>('immersive-chapters')
export function immersiveChapters(state: import('@tiptap/pm/state').EditorState) {
  return IMMERSIVE_CHAPTER_KEY.getState(state)
}
export function buildImmersiveChapters(document: DocumentNode, headings: EditorHeading[]) {
  const ancestors: ImmersiveChapter[] = []
  const chapters: ImmersiveChapter[] = []
  for (const heading of headings) {
    if (document.resolve(heading.position).depth !== 0) {
      continue
    }
    ancestors
      .filter((chapter) => chapter.depth >= heading.depth)
      .forEach((chapter) => {
        chapter.end = heading.position
      })
    const parent = headingParent(ancestors, heading.depth)
    const chapter: ImmersiveChapter = {
      ...heading,
      parentId: parent?.id,
      bodyFrom: heading.position + document.nodeAt(heading.position)!.nodeSize,
      end: document.content.size,
    }
    chapters.push(chapter)
    ancestors.push(chapter)
  }
  return chapters
}
/** 只改变视图状态；跨章节选区保持原样，光标在将隐藏的正文中时先移到可见标题。 */
export function toggleImmersiveChapter(
  id: string,
  notice: (message: string) => void = () => {},
): Command {
  return ({ state, tr }) => {
    const current = immersiveChapters(state)
    const chapter = current?.chapters.find((item) => item.id === id)
    if (!current || !chapter || !state.doc.nodeAt(chapter.position)?.textContent.trim()) {
      return false
    }
    const next = new Set(current.collapsed)
    if (next.has(id)) {
      next.delete(id)
    } else {
      const overlaps = state.selection.to > chapter.bodyFrom && state.selection.from < chapter.end
      if (overlaps && !state.selection.empty) {
        notice('当前选区包含本章正文。请先取消选区，再收起章节；选区和正文均未改变。')
        return false
      }
      if (overlaps) {
        tr.setSelection(TextSelection.near(tr.doc.resolve(chapter.bodyFrom - 1), -1))
      }
      next.add(id)
    }
    tr.setMeta(IMMERSIVE_CHAPTER_KEY, next).setMeta('addToHistory', false)
    return true
  }
}
/** 目录或源码定位先展开必要祖先；目标标题自身的折叠状态仍保留。 */
export function revealImmersivePosition(position: number): Command {
  return ({ state, tr }) => {
    const current = immersiveChapters(state)
    if (!current?.collapsed.size) {
      return false
    }
    const target = current.chapters.findLast((chapter) => chapter.position <= position)
    if (!target) {
      return false
    }
    const byId = new Map(current.chapters.map((chapter) => [chapter.id, chapter]))
    const ancestors = chapterAncestors(target.id, byId)
    if (position >= target.bodyFrom) {
      ancestors.push(target.id)
    }
    const next = new Set(current.collapsed)
    ancestors.forEach((id) => next.delete(id))
    if (next.size === current.collapsed.size) {
      return false
    }
    tr.setMeta(IMMERSIVE_CHAPTER_KEY, next).setMeta('addToHistory', false)
    return true
  }
}
export const ImmersiveChapters = Extension.create<{ notice: (message: string) => void }>({
  name: 'immersiveChapters',
  addOptions() {
    return { notice: () => {} }
  },
  addProseMirrorPlugins() {
    const scope = 'chapter-view-' + ++chapterViewId
    let identity = 0
    const createId = () => 'editor-chapter-' + ++identity
    const notice = this.options.notice
    function decorate(
      document: DocumentNode,
      chapters: ImmersiveChapter[],
      collapsed: Set<string>,
    ) {
      const decorations: Decoration[] = []
      for (const chapter of chapters) {
        if (!document.nodeAt(chapter.position)?.textContent.trim()) {
          continue
        }
        decorations.push(
          Decoration.widget(
            chapter.bodyFrom - 1,
            (view) => {
              const button = documentDOM(
                view.dom.ownerDocument,
                collapsed.has(chapter.id),
                chapter.text,
              )
              button.addEventListener('mousedown', (event) => event.preventDefault())
              button.addEventListener('click', (event) => {
                event.preventDefault()
                event.stopPropagation()
                const command = toggleImmersiveChapter(chapter.id, notice)
                const editor = thisEditor
                editor.commands.command(command)
              })
              return button
            },
            {
              key: 'chapter-' + chapter.id + '-' + collapsed.has(chapter.id) + '-' + chapter.text,
              chapterId: chapter.id,
              side: 1,
              marks: [],
              ignoreSelection: true,
              stopEvent: () => true,
            },
          ),
        )
      }
      return DecorationSet.create(document, decorations)
    }
    const thisEditor = this.editor
    return [
      new Plugin<ChapterState>({
        key: IMMERSIVE_CHAPTER_KEY,
        state: {
          init(_config, state) {
            const chapters = buildImmersiveChapters(
              state.doc,
              collectEditorHeadings(state.doc, [], undefined, createId),
            )
            return {
              chapters,
              collapsed: new Set(),
              decorations: decorate(state.doc, chapters, new Set()),
            }
          },
          apply(transaction, previous) {
            if (!transaction.docChanged && !transaction.getMeta(IMMERSIVE_CHAPTER_KEY)) {
              return previous
            }
            // 同段输入不改变标题层级；映射位置，并仅替换确实变化的标题按钮。
            if (
              transaction.docChanged &&
              !transaction.getMeta(IMMERSIVE_CHAPTER_KEY) &&
              transaction.getMeta('immersive-plain-text') &&
              transaction.steps.every((step) => step instanceof ReplaceStep)
            ) {
              const chapters = previous.chapters.map((chapter) => ({
                ...chapter,
                position: transaction.mapping.map(chapter.position),
                bodyFrom: transaction.mapping.map(chapter.bodyFrom),
                end: transaction.mapping.map(chapter.end),
              }))
              const collapsed = new Set(
                [...previous.collapsed].filter((id) =>
                  chapters.some(
                    (chapter) =>
                      chapter.id === id &&
                      transaction.doc.nodeAt(chapter.position)?.textContent.trim(),
                  ),
                ),
              )
              let decorations = previous.decorations.map(transaction.mapping, transaction.doc)
              if (
                transaction.steps.some(
                  (step, index) =>
                    step instanceof ReplaceStep &&
                    transaction.docs[index]!.resolve(step.from).parent.type.name === 'heading',
                )
              ) {
                const widgets = new Map(
                  decorations.find().map((item) => [item.spec.chapterId, item]),
                )
                const changed = chapters.filter((chapter, index) => {
                  chapter.text =
                    transaction.doc.nodeAt(chapter.position)!.textContent || '未命名标题'
                  return (
                    chapter.text !== previous.chapters[index]!.text ||
                    widgets.get(chapter.id)?.from !== chapter.bodyFrom - 1
                  )
                })
                const changedIds = new Set(changed.map((chapter) => chapter.id))
                decorations = decorations
                  .remove(decorations.find().filter((item) => changedIds.has(item.spec.chapterId)))
                  .add(transaction.doc, decorate(transaction.doc, changed, collapsed).find())
              }
              return {
                chapters,
                collapsed:
                  collapsed.size === previous.collapsed.size ? previous.collapsed : collapsed,
                decorations,
              }
            }
            const chapters = transaction.docChanged
              ? buildImmersiveChapters(
                  transaction.doc,
                  collectEditorHeadings(
                    transaction.doc,
                    previous.chapters,
                    transaction.mapping,
                    createId,
                  ),
                )
              : previous.chapters
            const valid = new Set(chapters.map((chapter) => chapter.id))
            const collapsed = new Set<string>(
              [...(transaction.getMeta(IMMERSIVE_CHAPTER_KEY) ?? previous.collapsed)].filter(
                (id: string) =>
                  valid.has(id) &&
                  Boolean(
                    transaction.doc
                      .nodeAt(chapters.find((chapter) => chapter.id === id)!.position)
                      ?.textContent.trim(),
                  ),
              ),
            )
            return {
              chapters,
              collapsed,
              decorations: decorate(transaction.doc, chapters, collapsed),
            }
          },
        },
        props: {
          decorations: (state) => immersiveChapters(state)?.decorations,
          attributes: { 'data-immersive-chapters': scope },
        },
        view(view) {
          // 样式留在编辑器 DOM 外；只数真实顶层节点，排除 PM 临时光标和 widget。
          // 不为每个正文块创建 Node Decoration，避免每次输入验证数百个块边界。
          const sheet = view.dom.ownerDocument.createElement('style')
          view.dom.ownerDocument.head.append(sheet)
          let previous: ChapterState | undefined
          const update = () => {
            const current = immersiveChapters(view.state)
            if (!current || previous === current) {
              return
            }
            previous = current
            const positions: number[] = []
            view.state.doc.forEach((_node, position) => positions.push(position))
            const rules = current.chapters
              .filter((chapter) => current.collapsed.has(chapter.id))
              .map((chapter) => {
                const from = positions.findIndex((position) => position >= chapter.bodyFrom)
                let to = positions.findIndex((position) => position >= chapter.end)
                if (to < 0) {
                  to = positions.length
                }
                if (from < 0 || from >= to) {
                  return ''
                }
                return (
                  '[data-immersive-chapters="' +
                  scope +
                  '"] > :nth-child(n+' +
                  (from + 1) +
                  ' of :not(.ProseMirror-widget, .ProseMirror-gapcursor)):nth-child(-n+' +
                  to +
                  ' of :not(.ProseMirror-widget, .ProseMirror-gapcursor)) { display: none !important; }'
                )
              })
              .join('\n')
            if (sheet.textContent !== rules) {
              sheet.textContent = rules
            }
          }
          update()
          return { update, destroy: () => sheet.remove() }
        },
        appendTransaction(transactions, _oldState, state) {
          if (
            !transactions.some((transaction) => transaction.selectionSet) ||
            transactions.some((transaction) => transaction.getMeta(IMMERSIVE_CHAPTER_KEY))
          ) {
            return null
          }
          const current = immersiveChapters(state)
          const next = new Set(current?.collapsed)
          current?.chapters.forEach((chapter) => {
            if (state.selection.from >= chapter.bodyFrom && state.selection.from < chapter.end) {
              next.delete(chapter.id)
            }
          })
          return next.size !== current?.collapsed.size
            ? state.tr.setMeta(IMMERSIVE_CHAPTER_KEY, next).setMeta('addToHistory', false)
            : null
        },
      }),
    ]
  },
})
function documentDOM(document: Document, collapsed: boolean, title: string) {
  const button = document.createElement('button')
  button.type = 'button'
  button.className = 'immersive-chapter-toggle'
  button.contentEditable = 'false'
  button.dataset.editorControls = ''
  button.setAttribute('aria-label', (collapsed ? '展开章节：' : '收起章节：') + title)
  button.setAttribute('aria-expanded', String(!collapsed))
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
  svg.setAttribute('viewBox', '0 0 24 24')
  svg.setAttribute('fill', 'none')
  svg.setAttribute('stroke', 'currentColor')
  svg.setAttribute('stroke-width', '2')
  svg.setAttribute('aria-hidden', 'true')
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path')
  path.setAttribute('d', collapsed ? 'm9 18 6-6-6-6' : 'm6 9 6 6 6-6')
  svg.appendChild(path)
  button.appendChild(svg)
  return button
}
