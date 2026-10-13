<script setup lang="ts">
import type { TocLink } from 'comark/plugins/toc'
const props = defineProps<{ links: TocLink[]; visibleIds?: Set<string>; selected: string; minimumDepth: number; contentCollapsedIds?: Set<string>; tocCollapsedIds: Set<string>; touchDirectory: boolean }>()
defineEmits<{ navigate: [id: string]; toggle: [id: string] }>()
const viewport = useTemplateRef<HTMLElement>('viewport')
let frame = 0, observer: ResizeObserver | undefined
function followSelected() {
  frame = 0
  const list = viewport.value
  if (!list?.clientHeight) return
  const selected = list.querySelector<HTMLElement>('[aria-current="location"]')
  if (!selected) return
  const container = list.getBoundingClientRect(), item = selected.getBoundingClientRect()
  // 只在选中项越界时滚动目录自身；不能用 scrollIntoView 连带移动正文。
  if (item.height > container.height || item.top < container.top) list.scrollTop += item.top - container.top
  else if (item.bottom > container.bottom) list.scrollTop += item.bottom - container.bottom
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
      <a :href="'#' + encodeURIComponent(link.id)" :title="link.text" :aria-current="selected === link.id ? 'location' : undefined" class="blog-toc-link" @click.prevent="$emit('navigate', link.id)">{{ link.text }}</a>
    </div>
  </div>
</template>
