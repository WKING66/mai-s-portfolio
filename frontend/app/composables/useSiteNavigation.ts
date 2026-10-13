import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from '#app'
import { SITE_NAVIGATION, type SiteSection } from '../constants/navigation'
import { navigationSection, visibleSection } from '../utils/siteNavigation'

/** 栏目高亮仅是导航展示状态，不参与任何认证或服务端授权。 */
export function useSiteNavigation() {
  const route = useRoute()
  const currentSection = ref<SiteSection | null>(null)
  const mobileOpen = ref(false)
  let frame = 0

  function readVisibleSection() {
    frame = 0
    if (route.path !== '/') return
    const positions = SITE_NAVIGATION.flatMap(item => {
      const element = document.getElementById(item.id)
      return element ? [{ id: item.id, top: element.getBoundingClientRect().top }] : []
    })
    const atBottom = window.scrollY > 0
      && window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 4
    currentSection.value = visibleSection(positions, 140, atBottom)
      ?? navigationSection(route.path, route.hash)
  }

  // 滚动期间每帧最多测量一次，卸载时移除监听，避免跨路由重复监听和残留高亮。
  function scheduleRead() {
    if (!frame) frame = requestAnimationFrame(readVisibleSection)
  }

  watch(() => [route.path, route.hash], async () => {
    mobileOpen.value = false
    currentSection.value = navigationSection(route.path, route.hash)
    await nextTick()
    if (import.meta.client) scheduleRead()
  })

  onMounted(() => {
    currentSection.value = navigationSection(route.path, route.hash)
    window.addEventListener('scroll', scheduleRead, { passive: true })
    window.addEventListener('resize', scheduleRead, { passive: true })
    scheduleRead()
  })
  onBeforeUnmount(() => {
    window.removeEventListener('scroll', scheduleRead)
    window.removeEventListener('resize', scheduleRead)
    if (frame) cancelAnimationFrame(frame)
  })

  const activeSection = computed(() => route.path === '/'
    ? currentSection.value : navigationSection(route.path, route.hash))
  function selectSection(id: SiteSection) {
    mobileOpen.value = false
    currentSection.value = id
  }

  return { activeSection, mobileOpen, selectSection }
}
