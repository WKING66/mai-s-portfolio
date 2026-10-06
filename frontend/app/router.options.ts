import type { RouterConfig } from '@nuxt/schema'
import { useNuxtApp } from '#app'
import { navigationScroll } from './utils/scrollPosition'

export default <RouterConfig>{
  scrollBehavior(to, from, savedPosition) {
    const app = useNuxtApp()
    const position = navigationScroll(to, from, savedPosition,
      window.matchMedia('(prefers-reduced-motion: reduce)').matches)
    if (!position) return false
    const targetPosition = position
    function resolvePosition() {
      if ('el' in targetPosition) {
        let element: HTMLElement | null = null
        try { element = document.getElementById(decodeURIComponent(to.hash.slice(1))) } catch { return false }
        return element ? { ...targetPosition, el: element, top: parseFloat(getComputedStyle(element).scrollMarginTop) || 0 } : false
      }
      return targetPosition
    }
    if (to.path === from.path || savedPosition) return resolvePosition()
    // 目标 DOM 完成即可，不等待页头后台恢复会话。
    return new Promise(resolve => {
      app.hooks.hookOnce('page:loading:end', () => {
        requestAnimationFrame(() => resolve(app.$router.currentRoute.value.fullPath === to.fullPath ? resolvePosition() : false))
      })
    })
  },
}
