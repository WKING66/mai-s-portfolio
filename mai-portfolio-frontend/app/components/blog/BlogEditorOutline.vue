<script setup lang="ts">
import type { Editor } from '@tiptap/core'
import type { Transaction } from '@tiptap/pm/state'
import { useMediaQuery } from '@vueuse/core'
import BlogTocTree from './BlogTocTree.vue'
import { visibleToc } from '../../utils/blogChapters'
import {
  collectEditorHeadings,
  editorHeadingTree,
  type EditorHeading,
} from '../../utils/editor/editorOutline'

const props = defineProps<{ editor: Editor; compact: boolean; scrollRoot?: HTMLElement | null }>()
const open = defineModel<boolean>('open', { default: false })
const headings = shallowRef<EditorHeading[]>([])
const activeHeading = ref('')
const collapsed = shallowRef(new Set<string>())
const touch = useMediaQuery('(hover: none), (pointer: coarse)')
const tree = computed(() => editorHeadingTree(headings.value))
const links = computed(() => {
  const flatten = (items: typeof tree.value): typeof tree.value =>
    items.flatMap((item) => [item, ...flatten(item.children ?? [])])
  return flatten(tree.value)
})
const visibleIds = computed(
  () =>
    new Set(
      visibleToc(
        tree.value,
        new Set(),
        props.compact || touch.value ? new Set() : collapsed.value,
      ).map((item) => item.id),
    ),
)
const selectedHeading = computed(() => {
  if (visibleIds.value.has(activeHeading.value)) {
    return activeHeading.value
  }
  const index = links.value.findIndex((link) => link.id === activeHeading.value)
  const depth = links.value[index]?.depth ?? 0
  for (let cursor = index - 1; cursor >= 0; cursor--) {
    const ancestor = links.value[cursor]!
    if (ancestor.depth < depth && visibleIds.value.has(ancestor.id)) {
      return ancestor.id
    }
  }
  return ''
})
const minimumDepth = computed(() => Math.min(6, ...headings.value.map((heading) => heading.depth)))
let positions: EditorHeading[] = []
let identity = 0
let frame = 0
let pendingNavigation: string | undefined

// 1. 每个文档事务只映射标题位置；文字及层级未变化时不更新目录组件树。
function updateHeadings({ transaction }: { transaction?: Transaction } = {}) {
  if (transaction && !transaction.docChanged) {
    return
  }
  const next = collectEditorHeadings(
    props.editor.state.doc,
    positions,
    transaction?.mapping,
    () => 'editor-heading-' + ++identity,
  )
  positions = next
  if (
    next.length !== headings.value.length ||
    next.some((heading, index) => {
      const previous = headings.value[index]
      return (
        heading.id !== previous?.id ||
        heading.text !== previous.text ||
        heading.depth !== previous.depth
      )
    })
  ) {
    headings.value = next
  }
}
function selectHeading() {
  const cursor = props.editor.state.selection.from
  activeHeading.value = positions.findLast((heading) => heading.position <= cursor)?.id ?? ''
}
// 2. 只监听正文自己的滚动容器，按二分查找读取必要标题；不改变正文滚动位置。
function updateFromScroll() {
  frame = 0
  const root = props.scrollRoot
  if (!root?.clientHeight || !positions.length) {
    return
  }
  const top = root.getBoundingClientRect().top + 36
  let low = 0
  let high = positions.length - 1
  let current = positions[0]!.id
  while (low <= high) {
    const middle = (low + high) >>> 1
    const heading = positions[middle]!
    const element = props.editor.view.nodeDOM(heading.position)
    if (!(element instanceof HTMLElement)) {
      break
    }
    if (element.getBoundingClientRect().top <= top) {
      current = heading.id
      low = middle + 1
    } else {
      high = middle - 1
    }
  }
  activeHeading.value = current
}
function scheduleScroll() {
  if (!frame) {
    frame = requestAnimationFrame(updateFromScroll)
  }
}
function focusHeading(id: string) {
  const heading = positions.find((entry) => entry.id === id)
  if (heading) {
    props.editor.commands.focus(heading.position + 1)
    activeHeading.value = id
  }
}
function navigate(id: string) {
  if (props.compact && open.value) {
    pendingNavigation = id
    open.value = false
  } else {
    focusHeading(id)
  }
}
function restoreFocus() {
  if (pendingNavigation) {
    focusHeading(pendingNavigation)
    pendingNavigation = undefined
  }
}
function toggle(id: string) {
  const next = new Set(collapsed.value)
  if (!next.delete(id)) {
    next.add(id)
  }
  collapsed.value = next
}
watch(
  () => props.editor,
  (editor, _, cleanup) => {
    positions = []
    updateHeadings()
    selectHeading()
    editor.on('transaction', updateHeadings)
    editor.on('selectionUpdate', selectHeading)
    cleanup(() => {
      editor.off('transaction', updateHeadings)
      editor.off('selectionUpdate', selectHeading)
    })
  },
  { immediate: true },
)
watch(
  () => props.scrollRoot,
  (root, _, cleanup) => {
    root?.addEventListener('scroll', scheduleScroll, { passive: true })
    cleanup(() => root?.removeEventListener('scroll', scheduleScroll))
  },
  { immediate: true },
)
onBeforeUnmount(() => cancelAnimationFrame(frame))
</script>

<template>
  <aside v-if="!compact" class="editor-outline" aria-label="编辑目录">
    <h2 class="mb-4 text-sm font-semibold">本篇目录</h2>
    <BlogTocTree
      v-if="links.length"
      :links="links"
      :visible-ids="visibleIds"
      :selected="selectedHeading"
      :minimum-depth="minimumDepth"
      :toc-collapsed-ids="collapsed"
      :touch-directory="touch"
      @navigate="navigate"
      @toggle="toggle"
    />
    <p v-else class="text-sm text-muted">添加标题后，目录会自动出现在这里。</p>
  </aside>
  <USlideover
    v-else
    v-model:open="open"
    side="left"
    title="文章目录"
    description="选择标题定位编辑光标"
    :ui="{ content: 'w-[min(22rem,90vw)]', body: 'flex min-h-0 flex-1 flex-col' }"
    @after:leave="restoreFocus"
  >
    <template #body>
      <BlogTocTree
        v-if="links.length"
        :links="links"
        :selected="selectedHeading"
        :minimum-depth="minimumDepth"
        :toc-collapsed-ids="new Set()"
        touch-directory
        @navigate="navigate"
        @toggle="toggle"
      />
      <p v-else class="text-sm text-muted">添加标题后，目录会自动出现在这里。</p>
    </template>
  </USlideover>
</template>

<style scoped>
.editor-outline {
  min-height: 0;
  height: 100%;
  padding: 2rem 1rem 1rem 1.25rem;
  border-right: 1px solid var(--ui-border);
}
.editor-outline :deep(.blog-toc-scroll) {
  height: calc(100% - 2.5rem);
  max-height: none;
}
</style>
