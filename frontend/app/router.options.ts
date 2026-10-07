import type { RouterConfig } from '@nuxt/schema'
// 路由选项参与 Nuxt 启动，不能从 #app 聚合入口导入：它会反向加载布局/路由规则，
// 在 dev 原生 ESM 中形成初始化循环，阻断整个客户端水合（生产打包可能掩盖问题）。
// 与 Nuxt 自身的 router.options 一样，只导入需要的运行时入口。
import { useNuxtApp } from '#app/nuxt'
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
