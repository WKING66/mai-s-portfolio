import type { RouterConfig } from '@nuxt/schema'
// 路由选项参与 Nuxt 启动，不能从 #app 聚合入口导入：它会反向加载布局/路由规则，
// 在 dev 原生 ESM 中形成初始化循环，阻断整个客户端水合（生产打包可能掩盖问题）。
// 与 Nuxt 自身的 router.options 一样，只导入需要的运行时入口。
import { useNuxtApp } from '#app/nuxt'
import { navigationScroll } from './utils/site/scrollPosition'

export default <RouterConfig>{
  scrollBehavior(to, from, savedPosition) {
    const nuxtApp = useNuxtApp()
    // 1. 初次水合和历史返回遵循既有恢复策略，不重复覆盖阅读位置。
    const scrollPosition = navigationScroll(
      to,
      from,
      savedPosition,
      window.matchMedia('(prefers-reduced-motion: reduce)').matches,
    )
    if (!scrollPosition) {
      return false
    }
    const targetPosition = scrollPosition
    // 2. 正文目录先处理展开与定位，未接管时才使用路由滚动。
    function resolvePosition() {
      if (to.hash) {
        let headingId: string
        try {
          headingId = decodeURIComponent(to.hash.slice(1))
        } catch {
          return false
        }
        // 阅读目录负责必要的展开、即时定位及闪现；路由只保留历史，避免二次滚动。
        const navigationDetail = { id: headingId, handled: false }
        window.dispatchEvent(
          new CustomEvent('blog:heading-navigation', { detail: navigationDetail }),
        )
        if (navigationDetail.handled) {
          return false
        }
      }
      if ('el' in targetPosition) {
        let element: HTMLElement | null
        try {
          element = document.getElementById(decodeURIComponent(to.hash.slice(1)))
        } catch {
          return false
        }
        return element
          ? {
              ...targetPosition,
              el: element,
              top: parseFloat(getComputedStyle(element).scrollMarginTop) || 0,
            }
          : false
      }
      return targetPosition
    }
    if (to.path === from.path || savedPosition) {
      return resolvePosition()
    }
    // 目标 DOM 完成即可，不等待页头后台恢复会话。
    return new Promise((resolve) => {
      nuxtApp.hooks.hookOnce('page:loading:end', () => {
        requestAnimationFrame(() =>
          resolve(
            nuxtApp.$router.currentRoute.value.fullPath === to.fullPath ? resolvePosition() : false,
          ),
        )
      })
    })
  },
}
