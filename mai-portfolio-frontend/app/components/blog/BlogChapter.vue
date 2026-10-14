<script setup lang="ts">
import { BLOG_CHAPTER_STATE } from '../../utils/blogChapters'
import type { ElementNode } from 'comark'
const props = defineProps<{
  chapterId: string
  title: string
  level: number
  // Comark 使用固定的 __node 属性传递 AST，不能按命名规则改变组件契约。
  // eslint-disable-next-line vue/prop-name-casing
  __node: ElementNode
}>()
const chapterState = inject(BLOG_CHAPTER_STATE)!
const collapsed = computed(() => chapterState.collapsed.value.has(props.chapterId))
const contentReady = computed(
  () => chapterState.ready.value === null || chapterState.ready.value.has(props.chapterId),
)
// 延后水合的异步章节 useId 前缀/顺序可能改变；复用阅读器版本键和稳定标题 ID。
const bodyId = computed(
  () =>
    'blog-chapter-' +
    encodeURIComponent(chapterState.idPrefix) +
    '-' +
    encodeURIComponent(props.chapterId),
)
const chapterBodyRef = useTemplateRef<HTMLElement>('body')
// 仅叶章节采用渲染跳过；标题始终参与布局，父章节不会包住整篇估算高度。
const leaf = computed(() => {
  const chapterSlot = props.__node[3]
  return (
    !Array.isArray(chapterSlot) ||
    !chapterSlot.slice(2).some((node) => Array.isArray(node) && node[0] === 'blog-chapter')
  )
})
let stopMeasuring = () => {}
// 水合与 SSR 使用同一完整 DOM；CSR 初次挂载才采用局部条件渲染。
// 延迟水合开始时 Nuxt 全局 isHydrating 已可能结束；沿用阅读器的首屏来源。
const serverRendered = chapterState.serverRendered
// 1. 标记当前章节水合完成；只观察叶章节，避免父容器估算高度覆盖整篇正文。
onMounted(() => {
  chapterState.hydrated(props.chapterId)
  if (leaf.value && chapterBodyRef.value) {
    stopMeasuring = chapterState.observeBody(chapterBodyRef.value, (height) => {
      // 这两项 CSS 状态由此观察器持有，不驱动整个章节组件重新渲染。
      if (height === 0) {
        chapterBodyRef.value?.removeAttribute('data-reading-contained')
        return
      }
      chapterBodyRef.value?.style.setProperty('--reading-body-height', height + 'px')
      chapterBodyRef.value?.setAttribute('data-reading-contained', 'true')
    })
  }
})
onBeforeUnmount(() => stopMeasuring())
</script>

<template>
  <section class="blog-chapter" :data-chapter="chapterId" :data-collapsed="collapsed">
    <div
      v-if="contentReady || serverRendered"
      class="blog-chapter-heading"
      :data-heading-level="level"
    >
      <div v-memo="[__node]" class="blog-chapter-title"><slot name="heading" /></div>
      <UButton
        color="neutral"
        variant="ghost"
        size="xs"
        :icon="collapsed ? 'i-lucide-chevron-right' : 'i-lucide-chevron-down'"
        :aria-label="(collapsed ? '展开章节：' : '收起章节：') + title"
        :aria-expanded="!collapsed"
        :aria-controls="bodyId"
        @click="chapterState.toggle(chapterId)"
      />
    </div>
    <!-- content-visibility 保留渲染状态；折叠时高度为零并 inert，不产生高度动画。 -->
    <div
      :id="bodyId"
      ref="body"
      class="blog-chapter-body"
      :class="{ 'blog-chapter-leaf': leaf }"
      :data-reading-folded="collapsed"
      :inert="collapsed"
      :aria-hidden="collapsed || undefined"
      :data-reading-pending="!contentReady"
    >
      <!-- Comark 的动态槽会递归创建 VNode；只在 AST 节点改变时重新调用内容槽。
           可见性在 memo 外，子章节/代码仍可自行更新。 -->
      <div v-if="contentReady || serverRendered" v-memo="[__node]"><slot /></div>
    </div>
  </section>
</template>
