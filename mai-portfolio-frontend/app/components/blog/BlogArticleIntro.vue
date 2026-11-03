<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    title: string
    summary?: string | null
    publishedAt?: string | null
    updatedAt?: string | null
    shareable?: boolean
    scrollRoot?: HTMLElement | null
    active?: boolean
  }>(),
  {
    summary: undefined,
    publishedAt: undefined,
    updatedAt: undefined,
    shareable: true,
    scrollRoot: undefined,
    active: true,
  },
)
defineEmits<{ share: [] }>()
const intro = useTemplateRef<HTMLElement>('intro')
let viewportObserver: ResizeObserver | undefined
// 只在容器/窗口尺寸或视图切换时测量，不在滚动和正文输入中重新计算首屏。
function fitViewport() {
  const element = intro.value
  if (!props.active || !element?.getClientRects().length) {
    return
  }
  const root = props.scrollRoot
  const offset =
    element.getBoundingClientRect().top -
    (root?.getBoundingClientRect().top ?? 0) +
    (root?.scrollTop ?? window.scrollY)
  const height = Math.max(0, (root?.clientHeight ?? window.innerHeight) - offset)
  const value = height + 'px'
  if (element.style.getPropertyValue('--article-intro-height') !== value) {
    element.style.setProperty('--article-intro-height', value)
  }
}
function observeViewport() {
  if (!viewportObserver) {
    return
  }
  viewportObserver.disconnect()
  const target = props.scrollRoot ?? document.querySelector('.site-header')
  if (target) {
    viewportObserver.observe(target)
  }
  fitViewport()
}
onMounted(() => {
  viewportObserver = new ResizeObserver(fitViewport)
  observeViewport()
  window.addEventListener('resize', fitViewport, { passive: true })
})
watch([() => props.scrollRoot, () => props.active], observeViewport, { flush: 'post' })
onBeforeUnmount(() => {
  viewportObserver?.disconnect()
  window.removeEventListener('resize', fitViewport)
})
const titleId = useId()
// 优先在中文语意停顿处换行，避免两行标题从‘应该’这样的词中间断开。
const titleParts = computed(() => props.title.split(/(?<=[，。！？：；])/u))
const dateFormatter = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'long', timeZone: 'UTC' })
function displayDate(value: string) {
  // 保留接口日期部分，避免带时区的时间戳转换后跨日。
  const date = new Date(value.slice(0, 10) + 'T00:00:00Z')
  return Number.isNaN(date.getTime()) ? value.slice(0, 10) : dateFormatter.format(date)
}
</script>

<template>
  <header ref="intro" class="article-intro" :aria-labelledby="titleId">
    <div class="article-opening">
      <h1 :id="titleId">
        <span v-for="(part, index) in titleParts" :key="index">{{ part }}</span>
      </h1>
      <p v-if="summary" class="article-summary">{{ summary }}</p>
    </div>
    <div v-if="publishedAt || updatedAt || shareable" class="article-intro-footer">
      <dl class="article-metadata">
        <div v-if="publishedAt">
          <dt>发布</dt>
          <dd>
            <time :datetime="publishedAt">{{ displayDate(publishedAt) }}</time>
          </dd>
        </div>
        <div v-if="updatedAt && updatedAt.slice(0, 10) !== publishedAt?.slice(0, 10)">
          <dt>更新</dt>
          <dd>
            <time :datetime="updatedAt">{{ displayDate(updatedAt) }}</time>
          </dd>
        </div>
      </dl>
      <UButton
        v-if="shareable"
        color="neutral"
        variant="ghost"
        icon="i-lucide-share-2"
        aria-label="复制文章链接"
        @click="$emit('share')"
      />
    </div>
  </header>
</template>

<style scoped>
/* 首屏至少填满可视区；长标题/摘要仍按自然高度撑开，不能裁切。 */
.article-intro {
  display: flex;
  flex-direction: column;
  min-height: var(--article-intro-height, calc(100svh - var(--site-header-height) - 6rem));
  padding-top: 2rem;
}
.article-opening {
  flex: 1;
}
.article-intro h1 {
  font-family: var(--site-editorial-font);
  font-size: clamp(2.5rem, 5.75vw, 5.5rem);
  font-weight: 300;
  line-height: 1.35;
  letter-spacing: normal;
  text-wrap: balance;
  overflow-wrap: anywhere;
}
.article-intro h1 span {
  display: inline-block;
  max-width: 100%;
}
.article-summary {
  max-width: 50rem;
  margin-top: 1.75rem;
  font-size: 1.25rem;
  line-height: 1.65;
  color: var(--site-muted);
  overflow-wrap: anywhere;
}
.article-intro-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1.5rem;
  margin-top: 3rem;
  border-block: 1px solid var(--site-line);
  padding-block: 1.25rem;
}
.article-metadata {
  display: flex;
  flex-wrap: wrap;
  gap: 1.5rem 2.5rem;
  font-size: 0.9375rem;
}
.article-metadata dt {
  margin-bottom: 0.5rem;
  font-size: 0.75rem;
  color: var(--site-muted);
}
@media (max-width: 600px) {
  .article-intro {
    padding-top: 1rem;
  }
  .article-intro h1 {
    font-size: clamp(2rem, 9.66vw, 2.5rem);
    line-height: 1.35;
  }
  .article-summary {
    font-size: 1rem;
    line-height: 1.75;
  }
  .article-intro-footer {
    padding-block: 1.25rem;
    gap: 0.75rem;
  }
  .article-metadata {
    font-size: 0.875rem;
    gap: 1.25rem;
  }
}
</style>
