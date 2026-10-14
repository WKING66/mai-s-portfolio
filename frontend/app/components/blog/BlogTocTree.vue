<script setup lang="ts">
import type { TocLink } from 'comark/plugins/toc'
const props = defineProps<{ links: TocLink[]; visibleIds?: Set<string>; selected: string; minimumDepth: number; contentCollapsedIds?: Set<string>; tocCollapsedIds: Set<string>; touchDirectory: boolean }>()
const emit = defineEmits<{ navigate: [id: string]; toggle: [id: string] }>()
const viewport = useTemplateRef<HTMLElement>('viewport')
let frame = 0, observer: ResizeObserver | undefined
let previous: HTMLElement | undefined, clickedId: string | undefined
function navigate(id: string) { clickedId = id; emit('navigate', id) }
function followSelected() {
  frame = 0
  const list = viewport.value
  if (!list?.clientHeight) return
  const selected = list.querySelector<HTMLElement>('[aria-current="location"]')
  if (!selected) return
  const top = list.getBoundingClientRect().top + list.clientTop, bottom = top + list.clientHeight
  const item = selected.getBoundingClientRect(), old = previous && list.contains(previous) ? previous.getBoundingClientRect() : undefined
  const downward = previous !== selected && !!previous && !!(previous.compareDocumentPosition(selected) & Node.DOCUMENT_POSITION_FOLLOWING)
  // 新标题第一次到达底部仍保持可见；只有旧高亮已触底后的向下切换才翻到顶部。
  const reset = clickedId !== props.selected && downward && old && old.height > 0 && old.top < bottom && old.bottom >= bottom - 1
  let offset = 0
  if (reset || item.top < top - 1 || item.height > list.clientHeight) offset = item.top - top
  else if (item.bottom > bottom + 1) offset = item.bottom - bottom
  // 只滚动目录自身，使用即时位置校正，避免堆积动画或连带移动正文。
  if (Math.abs(offset) > 1) list.scrollTop = Math.max(0, Math.min(list.scrollHeight - list.clientHeight, list.scrollTop + offset))
  previous = selected; clickedId = undefined
}
function scheduleFollow() { if (!frame) frame = requestAnimationFrame(followSelected) }
watch(() => props.selected, scheduleFollow, { flush: 'post' })
onMounted(() => { observer = new ResizeObserver(scheduleFollow); if (viewport.value) observer.observe(viewport.value); scheduleFollow() })
onBeforeUnmount(() => { observer?.disconnect(); cancelAnimationFrame(frame) })
</script>

<template>
  <div ref="viewport" class="blog-toc-scroll" tabindex="0" aria-label="目录章节列表">
    <div v-for="link in links" :key="link.id" v-show="!visibleIds || visibleIds.has(link.id)" v-memo="[link, visibleIds?.has(link.id), selected === link.id, contentCollapsedIds?.has(link.id), tocCollapsedIds.has(link.id), touchDirectory, minimumDepth]" class="blog-toc-row" :style="{ paddingLeft: Math.max(0, link.depth - minimumDepth) * .5 + 'rem' }">
      <UButton v-if="link.children?.length && !touchDirectory" class="blog-toc-branch" color="neutral" variant="ghost" size="xs" :disabled="contentCollapsedIds?.has(link.id)" :icon="tocCollapsedIds.has(link.id) || contentCollapsedIds?.has(link.id) ? 'i-lucide-chevron-right' : 'i-lucide-chevron-down'" :aria-expanded="!tocCollapsedIds.has(link.id) && !contentCollapsedIds?.has(link.id)" :aria-label="(tocCollapsedIds.has(link.id) ? '展开目录：' : '收起目录：') + link.text" @click="$emit('toggle', link.id)" />
      <span v-else class="blog-toc-leaf" />
      <a :href="'#' + encodeURIComponent(link.id)" :title="link.text" :aria-current="selected === link.id ? 'location' : undefined" class="blog-toc-link" @click.prevent="navigate(link.id)">{{ link.text }}</a>
    </div>
  </div>
</template>
