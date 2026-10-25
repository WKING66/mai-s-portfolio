<script setup lang="ts">
import type { TocLink } from 'comark/plugins/toc'
const props = defineProps<{
  links: TocLink[]
  visibleIds?: Set<string>
  selected: string
  minimumDepth: number
  contentCollapsedIds?: Set<string>
  tocCollapsedIds: Set<string>
  touchDirectory: boolean
}>()
const emit = defineEmits<{ navigate: [id: string]; toggle: [id: string] }>()
const viewport = useTemplateRef<HTMLElement>('viewport')
let followFrameId = 0,
  viewportObserver: ResizeObserver | undefined
let previousHeading: HTMLElement | undefined, clickedHeadingId: string | undefined
function navigate(id: string) {
  clickedHeadingId = id
  emit('navigate', id)
}
// 高亮发生有效变化后只测量当前项与旧项；不扫描整个目录，不新增正文监听。
function followSelected() {
  followFrameId = 0
  const tocViewport = viewport.value
  if (!tocViewport?.clientHeight) {
    return
  }
  const selectedHeading = tocViewport.querySelector<HTMLElement>('[aria-current="location"]')
  if (!selectedHeading) {
    return
  }
  const top = tocViewport.getBoundingClientRect().top + tocViewport.clientTop,
    bottom = top + tocViewport.clientHeight
  const headingRect = selectedHeading.getBoundingClientRect()
  const isMovingDownward =
    previousHeading !== selectedHeading &&
    !!previousHeading &&
    !!(previousHeading.compareDocumentPosition(selectedHeading) & Node.DOCUMENT_POSITION_FOLLOWING)
  // 测量本次新高亮项；触及底部立即移到顶部，点击导航及向上阅读仅补足可见空间。
  const shouldResetToTop =
    clickedHeadingId !== props.selected &&
    isMovingDownward &&
    headingRect.height > 0 &&
    headingRect.bottom >= bottom - 1
  let scrollOffset = 0
  if (
    shouldResetToTop ||
    headingRect.top < top - 1 ||
    headingRect.height > tocViewport.clientHeight
  ) {
    scrollOffset = headingRect.top - top
  } else if (headingRect.bottom > bottom + 1) {
    scrollOffset = headingRect.bottom - bottom
  }
  // 只滚动目录自身，使用即时位置校正，避免堆积动画或连带移动正文。
  if (Math.abs(scrollOffset) > 1) {
    tocViewport.scrollTop = Math.max(
      0,
      Math.min(
        tocViewport.scrollHeight - tocViewport.clientHeight,
        tocViewport.scrollTop + scrollOffset,
      ),
    )
  }
  previousHeading = selectedHeading
  clickedHeadingId = undefined
}
function scheduleFollow() {
  if (!followFrameId) {
    followFrameId = requestAnimationFrame(followSelected)
  }
}
watch(() => props.selected, scheduleFollow, { flush: 'post' })
onMounted(() => {
  viewportObserver = new ResizeObserver(scheduleFollow)
  if (viewport.value) {
    viewportObserver.observe(viewport.value)
  }
  scheduleFollow()
})
onBeforeUnmount(() => {
  viewportObserver?.disconnect()
  cancelAnimationFrame(followFrameId)
})
</script>

<template>
  <div ref="viewport" class="blog-toc-scroll" tabindex="0" aria-label="目录章节列表">
    <div
      v-for="link in links"
      v-show="!visibleIds || visibleIds.has(link.id)"
      :key="link.id"
      v-memo="[
        link,
        visibleIds?.has(link.id),
        selected === link.id,
        contentCollapsedIds?.has(link.id),
        tocCollapsedIds.has(link.id),
        touchDirectory,
        minimumDepth,
      ]"
      class="blog-toc-row"
      :style="{ paddingLeft: Math.max(0, link.depth - minimumDepth) * 0.5 + 'rem' }"
    >
      <UButton
        v-if="link.children?.length && !touchDirectory"
        class="blog-toc-branch"
        color="neutral"
        variant="ghost"
        size="xs"
        :disabled="contentCollapsedIds?.has(link.id)"
        :icon="
          tocCollapsedIds.has(link.id) || contentCollapsedIds?.has(link.id)
            ? 'i-lucide-chevron-right'
            : 'i-lucide-chevron-down'
        "
        :aria-expanded="!tocCollapsedIds.has(link.id) && !contentCollapsedIds?.has(link.id)"
        :aria-label="(tocCollapsedIds.has(link.id) ? '展开目录：' : '收起目录：') + link.text"
        @click="$emit('toggle', link.id)"
      />
      <span v-else class="blog-toc-leaf" />
      <a
        :href="'#' + encodeURIComponent(link.id)"
        :title="link.text"
        :aria-current="selected === link.id ? 'location' : undefined"
        class="blog-toc-link"
        @click.prevent="navigate(link.id)"
        >{{ link.text }}</a
      >
    </div>
  </div>
</template>
