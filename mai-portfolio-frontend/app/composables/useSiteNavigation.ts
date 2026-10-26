import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from '#app'
import { SITE_NAVIGATION, type SiteSection } from '../constants/navigation'
import { navigationSection, visibleSection } from '../utils/siteNavigation'

/** 栏目高亮仅是导航展示状态，不参与任何认证或服务端授权。 */
export function useSiteNavigation() {
  const route = useRoute()
  const visibleSectionId = ref<SiteSection | null>(null)
  const mobileOpen = ref(false)
  let pendingFrameId = 0

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
    // 2. 按阅读线更新高亮，页面底部允许最后栏目成为当前项。
    visibleSectionId.value =
      visibleSection(sectionPositions, 140, isAtPageBottom) ??
      navigationSection(route.path, route.hash)
  }

  // 滚动期间每帧最多测量一次，卸载时移除监听，避免跨路由重复监听和残留高亮。
  function scheduleRead() {
    if (!pendingFrameId) {
      pendingFrameId = requestAnimationFrame(readVisibleSection)
    }
  }

  // 3. 路由变化先关闭移动菜单，再等新页面 DOM 就绪测量。
  watch(
    () => [route.path, route.hash],
    async () => {
      mobileOpen.value = false
      visibleSectionId.value = navigationSection(route.path, route.hash)
      await nextTick()
      if (import.meta.client) {
        scheduleRead()
      }
    },
  )

  onMounted(() => {
    visibleSectionId.value = navigationSection(route.path, route.hash)
    window.addEventListener('scroll', scheduleRead, { passive: true })
    window.addEventListener('resize', scheduleRead, { passive: true })
    scheduleRead()
  })
  onBeforeUnmount(() => {
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
    visibleSectionId.value = id
  }

  return { activeSection, mobileOpen, selectSection }
}
