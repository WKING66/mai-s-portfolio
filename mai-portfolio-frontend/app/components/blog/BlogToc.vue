<script setup lang="ts">
import type { TocLink } from 'comark/plugins/toc'
import { visibleToc } from '../../utils/blog/chapters'
import { useMediaQuery } from '@vueuse/core'
import BlogTocTree from './BlogTocTree.vue'
const props = defineProps<{
  items: TocLink[]
  root?: HTMLElement | null
  reveal?: (id: string) => Promise<void>
  contentCollapsedIds?: Set<string>
  contentReady?: boolean
}>()
function flatten(items: TocLink[]): TocLink[] {
  return items.flatMap((item) => [item, ...flatten(item.children || [])])
}
// 1. 正文折叠和目录折叠各自过滤可见节点，目录不会反向改写正文状态。
const links = computed(() => flatten(props.items))
// 正文折叠的后代不参与定位；目录自身收起不影响正文的章节位置。
const contentLinks = computed(() =>
  visibleToc(props.items, props.contentCollapsedIds ?? new Set(), new Set()),
)
const tocCollapsedIds = ref(new Set<string>())
const touchQuery = useMediaQuery('(hover: none), (pointer: coarse)')
const compactQuery = useMediaQuery('(max-width: 1199px)')
// 延后水合时媒体查询可能已经就绪；第一次 VNode 必须仍与 SSR 的形状一致。
const isMounted = ref(false)
const touchDirectory = computed(() => isMounted.value && touchQuery.value)
const compactDirectory = computed(() => isMounted.value && compactQuery.value)
// 小屏弹窗没有目录折叠按钮，不能沿用宽屏手动收起的分支而令内容不可达。
const visibleLinks = computed(() =>
  visibleToc(
    props.items,
    props.contentCollapsedIds ?? new Set(),
    touchDirectory.value || compactDirectory.value ? new Set() : tocCollapsedIds.value,
  ),
)
const visibleIds = computed(() => new Set(visibleLinks.value.map((link) => link.id)))
const selected = computed(() => {
  if (visibleIds.value.has(activeHeadingId.value)) {
    return activeHeadingId.value
  }
  function ancestor(items: TocLink[], parent = ''): string | undefined {
    for (const item of items) {
      const visible = visibleIds.value.has(item.id) ? item.id : parent
      if (item.id === activeHeadingId.value) {
        return visible
      }
      const found = ancestor(item.children || [], visible)
      if (found !== undefined) {
        return found
      }
    }
  }
  return ancestor(props.items) || ''
})
function toggleToc(id: string) {
  const next = new Set(tocCollapsedIds.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  tocCollapsedIds.value = next
}
const minimumDepth = computed(() => Math.min(...links.value.map((item) => item.depth)))
const activeHeadingId = ref('')
const mobileOpen = ref(false)
const mobileTrigger = useTemplateRef<{ $el: HTMLElement }>('mobileTrigger')
let mobileNavigationTarget: HTMLElement | undefined
function restoreFocus(event: Event) {
  event.preventDefault()
  const target = mobileNavigationTarget ?? mobileTrigger.value?.$el
  if (target) {
    if (mobileNavigationTarget) {
      target.tabIndex = -1
    }
    target.focus({ preventScroll: true })
  }
  mobileNavigationTarget = undefined
}
const router = useRouter()
let layoutObserver: ResizeObserver | undefined,
  updateFrameId = 0,
  navigationRevision = 0,
  isNavigationPinned = false,
  isDisposed = false
let arePositionsDirty = true,
  isTopologyDirty = true,
  geometryRevision = 0,
  headerHeight = 72
let headingPositions: { id: string; element: HTMLElement; top: number; revision: number }[] = []
let headingAnimation: Animation | undefined,
  flashedHeading: HTMLElement | undefined,
  flashTimerId: ReturnType<typeof setTimeout> | undefined
function heading(id: string) {
  return props.root
    ? props.root.querySelector<HTMLElement>('#' + CSS.escape(id))
    : document.getElementById(id)
}
function clearFlash() {
  headingAnimation?.cancel()
  flashedHeading?.removeAttribute('data-heading-flash')
  clearTimeout(flashTimerId)
  headingAnimation = undefined
  flashedHeading = undefined
}
function highlight(element: HTMLElement) {
  clearFlash()
  flashedHeading = element
  element.setAttribute('data-heading-flash', '')
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) {
    flashTimerId = setTimeout(clearFlash, 300)
  } else {
    const animation = element.animate(
      [{ opacity: 1 }, { opacity: 1, offset: 0.27 }, { opacity: 0 }],
      { duration: 1100, pseudoElement: '::before' },
    )
    headingAnimation = animation
    animation.onfinish = () => {
      if (headingAnimation === animation) {
        clearFlash()
      }
    }
  }
}
// 2. 点击定位使旧操作失效，等待正文展开后瞬时滚动，并在关闭移动目录后恢复焦点。
async function navigate(id: string) {
  const current = ++navigationRevision
  clearFlash()
  activeHeadingId.value = id
  isNavigationPinned = true
  await props.reveal?.(id)
  await nextTick()
  if (current !== navigationRevision) {
    return
  }
  const element = heading(id)
  if (!element) {
    return
  }
  if (mobileOpen.value) {
    mobileNavigationTarget = element
    mobileOpen.value = false
  }
  // instant 显式覆盖全局或主题的 scroll-behavior，目录不播放长距离滚动。
  // 原生定位按 scroll-margin 留出 Header 空间，仅执行一次。
  element.scrollIntoView({ block: 'start', inline: 'nearest', behavior: 'instant' })
  highlight(element)
}
function request(event: Event) {
  const detail = (event as CustomEvent<{ id: string; handled: boolean }>).detail
  if (!links.value.some((link) => link.id === detail.id)) {
    return
  }
  detail.handled = true
  void navigate(detail.id)
}
function click(id: string) {
  const hash = '#' + id
  if (decodeURIComponent(router.currentRoute.value.hash) === hash) {
    void navigate(id)
  } else {
    void router.push({ hash })
  }
}
// 3. 每帧至多更新一次高亮；几何缓存失效时只测量二分查找所需标题。
function update() {
  updateFrameId = 0
  if (props.contentReady === false) {
    return
  }
  if (isNavigationPinned) {
    // 跳过的正文首次参与布局可能改变文档高度。只在尺寸失效时稳定明确点击的锚点，
    // 不在每次 scroll 上调整位置；任何手动阅读操作立即解除锁定。
    if (arePositionsDirty) {
      arePositionsDirty = false
      const element = heading(activeHeadingId.value),
        scrollRoot = document.scrollingElement
      if (element && scrollRoot && !element.closest('[data-reading-folded="true"]')) {
        const margin = parseFloat(getComputedStyle(element).scrollMarginTop) || 0
        const top = Math.max(
          0,
          Math.min(
            scrollY + element.getBoundingClientRect().top - margin,
            scrollRoot.scrollHeight - scrollRoot.clientHeight,
          ),
        )
        if (Math.abs(top - scrollY) > 1) {
          window.scrollTo({ top, behavior: 'instant' })
        }
      }
    }
    return
  }
  if (arePositionsDirty) {
    // 尺寸变化只使缓存过期；跳过内容/图片会在滚动中多次改变正文高度，
    // 不能因此每次重新测量所有标题。二分定位仅测量本次需要的节点。
    headerHeight = document.querySelector('.site-header')?.getBoundingClientRect().height || 72
    geometryRevision++
    arePositionsDirty = false
  }
  if (isTopologyDirty) {
    headingPositions = contentLinks.value.flatMap((item) => {
      const element = heading(item.id)
      return element ? [{ id: item.id, element, top: 0, revision: -1 }] : []
    })
    isTopologyDirty = false
  }
  function top(index: number) {
    const position = headingPositions[index]!
    if (position.revision !== geometryRevision) {
      position.top = position.element.getBoundingClientRect().top + scrollY
      position.revision = geometryRevision
    }
    return position.top
  }
  const boundary = scrollY + headerHeight + 36
  let low = 0,
    high = headingPositions.length
  while (low < high) {
    const middle = (low + high) >>> 1
    if (top(middle) <= boundary) {
      low = middle + 1
    } else {
      high = middle
    }
  }
  const id = headingPositions[Math.max(0, low - 1)]?.id || ''
  if (activeHeadingId.value !== id) {
    activeHeadingId.value = id
  }
}
function schedule() {
  if (!updateFrameId) {
    updateFrameId = requestAnimationFrame(update)
  }
}
function invalidatePositions() {
  arePositionsDirty = true
  schedule()
}
function manual() {
  if (isNavigationPinned) {
    arePositionsDirty = true
  }
  navigationRevision++
  isNavigationPinned = false
  schedule()
}
// 4. 内容或挂载根变化才更新观察目标，渐进准备期间不查询隐藏正文。
watch(
  [links, () => props.contentReady, () => props.root],
  async () => {
    if (!import.meta.client) {
      return
    }
    navigationRevision++
    clearFlash()
    await nextTick()
    if (isDisposed) {
      return
    }
    layoutObserver?.disconnect()
    isTopologyDirty = true
    // 渐进准备时查询隐藏后代会强迫浏览器提前布局整篇正文。
    if (props.contentReady === false) {
      return
    }
    layoutObserver = new ResizeObserver(invalidatePositions)
    if (props.root) {
      layoutObserver.observe(props.root)
    }
    const header = document.querySelector('.site-header')
    if (header) {
      layoutObserver.observe(header)
    }
    isNavigationPinned = false
    invalidatePositions()
  },
  { immediate: true },
)
watch(
  () => props.items,
  () => {
    tocCollapsedIds.value = new Set()
    activeHeadingId.value = ''
    clearFlash()
  },
)
watch(
  () => props.contentCollapsedIds,
  () => {
    isTopologyDirty = true
    invalidatePositions()
  },
)
onMounted(() => {
  isMounted.value = true
  window.addEventListener('blog:heading-navigation', request)
  window.addEventListener('scroll', schedule, { passive: true })
  for (const name of ['wheel', 'touchstart', 'pointerdown', 'keydown']) {
    window.addEventListener(name, manual, { passive: true })
  }
})
// 5. 离开页面取消定位和闪现，解除全部阅读监听，避免跨路由残留更新。
onBeforeUnmount(() => {
  isDisposed = true
  navigationRevision++
  clearFlash()
  layoutObserver?.disconnect()
  cancelAnimationFrame(updateFrameId)
  window.removeEventListener('blog:heading-navigation', request)
  window.removeEventListener('scroll', schedule)
  for (const name of ['wheel', 'touchstart', 'pointerdown', 'keydown']) {
    window.removeEventListener(name, manual)
  }
})
</script>

<template>
  <div class="blog-toc">
    <nav aria-label="文章目录" class="blog-toc-desktop">
      <p class="blog-toc-title">本篇目录</p>
      <BlogTocTree
        :links="links"
        :visible-ids="visibleIds"
        :selected="selected"
        :minimum-depth="minimumDepth"
        :content-collapsed-ids="contentCollapsedIds"
        :toc-collapsed-ids="tocCollapsedIds"
        :touch-directory="touchDirectory"
        @navigate="click"
        @toggle="toggleToc"
      />
    </nav>
    <USlideover
      v-model:open="mobileOpen"
      side="left"
      title="本篇目录"
      description="选择章节直接定位正文"
      :content="{ onCloseAutoFocus: restoreFocus }"
      :ui="{
        overlay: 'top-[var(--site-overlay-top)] bg-black/10 backdrop-blur-none',
        content: 'blog-toc-panel',
        body: 'flex flex-col p-4 min-h-0 overflow-hidden',
      }"
    >
      <UButton
        ref="mobileTrigger"
        class="blog-toc-mobile-toggle"
        color="neutral"
        variant="outline"
        icon="i-lucide-list-tree"
        aria-label="打开文章目录"
      />
      <template #body>
        <nav aria-label="文章目录" class="blog-toc-panel-tree">
          <BlogTocTree
            :links="links"
            :visible-ids="visibleIds"
            :selected="selected"
            :minimum-depth="minimumDepth"
            :content-collapsed-ids="contentCollapsedIds"
            :toc-collapsed-ids="tocCollapsedIds"
            :touch-directory="true"
            @navigate="click"
            @toggle="toggleToc"
          />
        </nav>
      </template>
    </USlideover>
  </div>
</template>
