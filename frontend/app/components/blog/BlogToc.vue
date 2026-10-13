<script setup lang="ts">
import type { TocLink } from 'comark/plugins/toc'
import { visibleToc } from '../../utils/blogChapters'
import { useMediaQuery } from '@vueuse/core'
import BlogTocTree from './BlogTocTree.vue'
const props = defineProps<{ items: TocLink[]; root?: HTMLElement | null; reveal?: (id: string) => Promise<void>; contentCollapsedIds?: Set<string>; contentReady?: boolean }>()
function flatten(items: TocLink[]): TocLink[] { return items.flatMap(item => [item, ...flatten(item.children || [])]) }
const links = computed(() => flatten(props.items))
// 正文折叠的后代不参与定位；目录自身收起不影响正文的章节位置。
const contentLinks = computed(() => visibleToc(props.items, props.contentCollapsedIds ?? new Set(), new Set()))
const tocCollapsedIds = ref(new Set<string>())
const touchQuery = useMediaQuery('(hover: none), (pointer: coarse)')
const compactQuery = useMediaQuery('(max-width: 1199px)')
// 延后水合时媒体查询可能已经就绪；第一次 VNode 必须仍与 SSR 的形状一致。
const mounted = ref(false)
const touchDirectory = computed(() => mounted.value && touchQuery.value)
const compactDirectory = computed(() => mounted.value && compactQuery.value)
// 小屏弹窗没有目录折叠按钮，不能沿用宽屏手动收起的分支而令内容不可达。
const visibleLinks = computed(() => visibleToc(props.items, props.contentCollapsedIds ?? new Set(), touchDirectory.value || compactDirectory.value ? new Set() : tocCollapsedIds.value))
const visibleIds = computed(() => new Set(visibleLinks.value.map(link => link.id)))
const selected = computed(() => {
  if (visibleIds.value.has(active.value)) return active.value
  function ancestor(items: TocLink[], parent = ''): string | undefined {
    for (const item of items) {
      const visible = visibleIds.value.has(item.id) ? item.id : parent
      if (item.id === active.value) return visible
      const found = ancestor(item.children || [], visible)
      if (found !== undefined) return found
    }
  }
  return ancestor(props.items) || ''
})
function toggleToc(id: string) {
  const next = new Set(tocCollapsedIds.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  tocCollapsedIds.value = next
}
const minimumDepth = computed(() => Math.min(...links.value.map(item => item.depth)))
const active = ref('')
const mobileOpen = ref(false)
const mobileTrigger = useTemplateRef<{ $el: HTMLElement }>('mobileTrigger')
let mobileNavigationTarget: HTMLElement | undefined
function restoreFocus(event: Event) {
  event.preventDefault()
  const target = mobileNavigationTarget ?? mobileTrigger.value?.$el
  if (target) { if (mobileNavigationTarget) target.tabIndex = -1; target.focus({ preventScroll: true }) }
  mobileNavigationTarget = undefined
}
const router = useRouter()
let observer: ResizeObserver | undefined, frame = 0, revision = 0, pinned = false, disposed = false
let positionsDirty = true, topologyDirty = true, geometryRevision = 0, headerHeight = 72
let positions: { id: string; element: HTMLElement; top: number; revision: number }[] = []
let flash: Animation | undefined, flashed: HTMLElement | undefined, flashTimer: ReturnType<typeof setTimeout> | undefined
function heading(id: string) { return props.root ? props.root.querySelector<HTMLElement>('#' + CSS.escape(id)) : document.getElementById(id) }
function clearFlash() { flash?.cancel(); flashed?.removeAttribute('data-heading-flash'); clearTimeout(flashTimer); flash = undefined; flashed = undefined }
function highlight(element: HTMLElement) {
  clearFlash(); flashed = element; element.setAttribute('data-heading-flash', '')
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) flashTimer = setTimeout(clearFlash, 300)
  else {
    const animation = element.animate([{ opacity: 1 }, { opacity: 1, offset: .27 }, { opacity: 0 }], { duration: 1100, pseudoElement: '::before' })
    flash = animation
    animation.onfinish = () => { if (flash === animation) clearFlash() }
  }
}
async function navigate(id: string) {
  const current = ++revision
  clearFlash(); active.value = id; pinned = true
  await props.reveal?.(id)
  await nextTick()
  if (current !== revision) return
  const element = heading(id)
  if (!element) return
  if (mobileOpen.value) { mobileNavigationTarget = element; mobileOpen.value = false }
  // instant 显式覆盖全局或主题的 scroll-behavior，目录不播放长距离滚动。
  // 原生定位按 scroll-margin 留出 Header 空间，仅执行一次。
  element.scrollIntoView({ block: 'start', inline: 'nearest', behavior: 'instant' })
  highlight(element)
}
function request(event: Event) {
  const detail = (event as CustomEvent<{ id: string; handled: boolean }>).detail
  if (!links.value.some(link => link.id === detail.id)) return
  detail.handled = true; void navigate(detail.id)
}
function click(id: string) {
  const hash = '#' + id
  if (decodeURIComponent(router.currentRoute.value.hash) === hash) void navigate(id)
  else void router.push({ hash })
}
function update() {
  frame = 0
  if (props.contentReady === false) return
  if (pinned) {
    // 跳过的正文首次参与布局可能改变文档高度。只在尺寸失效时稳定明确点击的锚点，
    // 不在每次 scroll 上调整位置；任何手动阅读操作立即解除锁定。
    if (positionsDirty) {
      positionsDirty = false
      const element = heading(active.value), scrollRoot = document.scrollingElement
      if (element && scrollRoot && !element.closest('[data-reading-folded="true"]')) {
        const margin = parseFloat(getComputedStyle(element).scrollMarginTop) || 0
        const top = Math.max(0, Math.min(scrollY + element.getBoundingClientRect().top - margin, scrollRoot.scrollHeight - scrollRoot.clientHeight))
        if (Math.abs(top - scrollY) > 1) window.scrollTo({ top, behavior: 'instant' })
      }
    }
    return
  }
  if (positionsDirty) {
    // 尺寸变化只使缓存过期；跳过内容/图片会在滚动中多次改变正文高度，
    // 不能因此每次重新测量所有标题。二分定位仅测量本次需要的节点。
    headerHeight = document.querySelector('.showcase-header, .site-header')?.getBoundingClientRect().height || 72
    geometryRevision++
    positionsDirty = false
  }
  if (topologyDirty) {
    positions = contentLinks.value.flatMap(item => {
      const element = heading(item.id)
      return element ? [{ id: item.id, element, top: 0, revision: -1 }] : []
    })
    topologyDirty = false
  }
  function top(index: number) {
    const position = positions[index]!
    if (position.revision !== geometryRevision) {
      position.top = position.element.getBoundingClientRect().top + scrollY
      position.revision = geometryRevision
    }
    return position.top
  }
  const boundary = scrollY + headerHeight + 36
  let low = 0, high = positions.length
  while (low < high) { const middle = (low + high) >>> 1; if (top(middle) <= boundary) low = middle + 1; else high = middle }
  const id = positions[Math.max(0, low - 1)]?.id || ''
  if (active.value !== id) active.value = id
}
function schedule() { if (!frame) frame = requestAnimationFrame(update) }
function invalidatePositions() { positionsDirty = true; schedule() }
function manual(event: Event) {
  if (pinned) positionsDirty = true
  revision++; pinned = false; schedule()
}
watch([links, () => props.contentReady, () => props.root], async () => {
  if (!import.meta.client) return
  revision++; clearFlash()
  await nextTick()
  if (disposed) return
  observer?.disconnect(); topologyDirty = true
  // 渐进准备时查询隐藏后代会强迫浏览器提前布局整篇正文。
  if (props.contentReady === false) return
  observer = new ResizeObserver(invalidatePositions)
  if (props.root) observer.observe(props.root)
  const header = document.querySelector('.showcase-header, .site-header')
  if (header) observer.observe(header)
  pinned = false; invalidatePositions()
}, { immediate: true })
watch(() => props.items, () => { tocCollapsedIds.value = new Set(); active.value = ''; clearFlash() })
watch(() => props.contentCollapsedIds, () => { topologyDirty = true; invalidatePositions() })
onMounted(() => {
  mounted.value = true
  window.addEventListener('blog:heading-navigation', request)
  window.addEventListener('scroll', schedule, { passive: true })
  for (const name of ['wheel', 'touchstart', 'pointerdown', 'keydown']) window.addEventListener(name, manual, { passive: true })
})
onBeforeUnmount(() => {
  disposed = true; revision++; clearFlash(); observer?.disconnect(); cancelAnimationFrame(frame)
  window.removeEventListener('blog:heading-navigation', request); window.removeEventListener('scroll', schedule)
  for (const name of ['wheel', 'touchstart', 'pointerdown', 'keydown']) window.removeEventListener(name, manual)
})
</script>

<template>
  <div class="blog-toc showcase-toc">
    <nav aria-label="文章目录" class="blog-toc-desktop">
      <p class="blog-toc-title">本篇目录</p>
      <BlogTocTree :links="links" :visible-ids="visibleIds" :selected="selected" :minimum-depth="minimumDepth" :content-collapsed-ids="contentCollapsedIds" :toc-collapsed-ids="tocCollapsedIds" :touch-directory="touchDirectory" @navigate="click" @toggle="toggleToc" />
    </nav>
    <USlideover v-model:open="mobileOpen" side="left" title="本篇目录" description="选择章节直接定位正文" :content="{ onCloseAutoFocus: restoreFocus }" :ui="{ overlay: 'top-[var(--site-overlay-top)] bg-black/10 backdrop-blur-none', content: 'blog-toc-panel', body: 'flex flex-col p-4 min-h-0 overflow-hidden' }">
      <UButton ref="mobileTrigger" class="blog-toc-mobile-toggle" color="neutral" variant="outline" icon="i-lucide-list-tree" aria-label="打开文章目录" />
      <template #body>
        <nav aria-label="文章目录" class="blog-toc-panel-tree">
          <BlogTocTree :links="links" :visible-ids="visibleIds" :selected="selected" :minimum-depth="minimumDepth" :content-collapsed-ids="contentCollapsedIds" :toc-collapsed-ids="tocCollapsedIds" :touch-directory="true" @navigate="click" @toggle="toggleToc" />
        </nav>
      </template>
    </USlideover>
  </div>
</template>
