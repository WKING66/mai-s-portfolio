import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useNuxtApp, useRoute } from '#app'
import { SITE_NAVIGATION, type SiteSection } from '../constants/navigation'
import { navigationSection, visibleSection } from '../utils/site/siteNavigation'

/** 栏目高亮仅是导航展示状态，不参与任何认证或服务端授权。 */
export function useSiteNavigation() {
  const route = useRoute()
  const nuxtApp = useNuxtApp()
  let layoutObserver: ResizeObserver | undefined
  const visibleSectionId = ref<SiteSection | null>(null)
  const mobileOpen = ref(false)
  let pendingFrameId = 0
  let navigationTarget: SiteSection | null = null
  let settledNavigationY: number | null = null

  function readVisibleSection() {
    pendingFrameId = 0
    if (route.path !== '/') {
      return
    }
    // 1. 只测量首页栏目；每帧合并滚动与尺寸变化请求。
    const sectionPositions = SITE_NAVIGATION.flatMap((item) => {
      const element = document.getElementById(item.id)
      return element ? [{ id: item.id, top: element.getBoundingClientRect().top }] : []
    })
    const isAtPageBottom =
      window.scrollY > 0 &&
      window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 4
    // 滚动条拖动也会产生位置变化；定位完成后离开该位置即恢复自动高亮。
    if (settledNavigationY !== null && Math.abs(window.scrollY - settledNavigationY) > 4) {
      navigationTarget = null
      settledNavigationY = null
    }
    // 点击定位时，底部短栏目可能无法贴齐阅读线；在用户主动滚动前保留明确目标。
    if (navigationTarget && sectionPositions.some((section) => section.id === navigationTarget)) {
      const target = document.getElementById(navigationTarget)!
      const margin = parseFloat(getComputedStyle(target).scrollMarginTop) || 0
      const maxScroll = Math.max(0, document.documentElement.scrollHeight - window.innerHeight)
      const desiredY = Math.min(
        maxScroll,
        Math.max(0, target.getBoundingClientRect().top + window.scrollY - margin),
      )
      if (Math.abs(window.scrollY - desiredY) <= 4) {
        settledNavigationY = window.scrollY
      }
      visibleSectionId.value = navigationTarget
      return
    }
    // 2. 按阅读线更新高亮，页面底部允许最后栏目成为当前项。
    visibleSectionId.value =
      visibleSection(
        sectionPositions,
        (document.querySelector('.site-header')?.getBoundingClientRect().bottom ?? 0) + 24,
        isAtPageBottom,
      ) ?? navigationSection(route.path, route.hash)
  }

  // 滚动期间每帧最多测量一次，卸载时移除监听，避免跨路由重复监听和残留高亮。
  function scheduleRead() {
    if (!pendingFrameId) {
      pendingFrameId = requestAnimationFrame(readVisibleSection)
    }
  }

  function releaseNavigationTarget(event: Event) {
    const element = event.target instanceof Element ? event.target : null
    if (element?.closest('dialog, input, textarea, select, [contenteditable="true"]')) {
      return
    }
    if (
      event instanceof KeyboardEvent &&
      !['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '].includes(event.key)
    ) {
      return
    }
    navigationTarget = null
    settledNavigationY = null
    scheduleRead()
  }

  // 3. 路由变化先关闭移动菜单，再等新页面 DOM 就绪测量。
  watch(
    () => [route.path, route.hash],
    async () => {
      mobileOpen.value = false
      settledNavigationY = null
      navigationTarget =
        route.path === '/'
          ? (SITE_NAVIGATION.find((item) => '#' + item.id === route.hash)?.id ?? null)
          : null
      visibleSectionId.value = navigationSection(route.path, route.hash)
      await nextTick()
      if (import.meta.client) {
        scheduleRead()
      }
    },
  )

  // 页头常驻，但首页在异步资料完成后才出现；页面装载完成后再测量一次。
  const removePageHook = nuxtApp.hook('page:finish', scheduleRead)
  onMounted(() => {
    navigationTarget =
      route.path === '/'
        ? (SITE_NAVIGATION.find((item) => '#' + item.id === route.hash)?.id ?? null)
        : null
    window.addEventListener('wheel', releaseNavigationTarget, { passive: true })
    window.addEventListener('touchmove', releaseNavigationTarget, { passive: true })
    window.addEventListener('keydown', releaseNavigationTarget)
    visibleSectionId.value = navigationSection(route.path, route.hash)
    layoutObserver = new ResizeObserver(scheduleRead)
    layoutObserver.observe(document.body)
    window.addEventListener('scroll', scheduleRead, { passive: true })
    window.addEventListener('resize', scheduleRead, { passive: true })
    scheduleRead()
  })
  onBeforeUnmount(() => {
    window.removeEventListener('wheel', releaseNavigationTarget)
    window.removeEventListener('touchmove', releaseNavigationTarget)
    window.removeEventListener('keydown', releaseNavigationTarget)
    removePageHook()
    layoutObserver?.disconnect()
    window.removeEventListener('scroll', scheduleRead)
    window.removeEventListener('resize', scheduleRead)
    if (pendingFrameId) {
      cancelAnimationFrame(pendingFrameId)
    }
  })

  const activeSection = computed(() =>
    route.path === '/' ? visibleSectionId.value : navigationSection(route.path, route.hash),
  )
  function selectSection(id: SiteSection) {
    mobileOpen.value = false
    settledNavigationY = null
    navigationTarget = id
    visibleSectionId.value = id
  }

  return { activeSection, mobileOpen, selectSection }
}
