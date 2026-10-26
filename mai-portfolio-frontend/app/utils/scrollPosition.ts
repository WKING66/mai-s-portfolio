export const SCROLL_SNAPSHOT_KEY = 'portfolio:scroll:v1'

/** 新锚点的浏览器历史记录未必已有位置。卸载时保存当前地址与位置，在 SSR DOM 首次布局前恢复。 */
export const SCROLL_RESTORATION_BOOTSTRAP_SCRIPT = `;(() => {
  const key = ${JSON.stringify(SCROLL_SNAPSHOT_KEY)}
  const path = window.location.pathname + window.location.search + window.location.hash
  if ('scrollRestoration' in window.history) {
    window.history.scrollRestoration = 'auto'
  }
  // 1. 仅采用当前地址且坐标有效的快照，不使用其他路由的阅读位置。
  let saved = null
  try {
    const value = JSON.parse(window.sessionStorage.getItem(key) || 'null')
    if (
      value &&
      value.path === path &&
      Number.isFinite(value.top) &&
      value.top >= 0 &&
      Number.isFinite(value.left) &&
      value.left >= 0
    ) {
      saved = value
    }
  } catch {
    /* 存储受限或快照损坏时继续使用锚点恢复。 */
  }
  // 2. 卸载时保存实际地址，兼容 SPA 导航后地址与初始地址不同。
  window.addEventListener('pagehide', () => {
    try {
      window.sessionStorage.setItem(
        key,
        JSON.stringify({
          path: window.location.pathname + window.location.search + window.location.hash,
          top: window.scrollY,
          left: window.scrollX,
        }),
      )
    } catch {
      /* 存储受限时不保存快照，仍保留浏览器自身的历史恢复。 */
    }
  })
  if (!saved && !window.location.hash) {
    return
  }
  let observer
  let frame = 0
  let stopped = false
  const interactions = ['wheel', 'touchstart', 'pointerdown', 'keydown']
  // 3. 用户开始交互或定位完成后停止恢复，避免抢占用户滚动。
  function stop() {
    stopped = true
    if (observer) {
      observer.disconnect()
    }
    if (frame) {
      window.cancelAnimationFrame(frame)
    }
    document.removeEventListener('DOMContentLoaded', restore)
    interactions.forEach((event) => window.removeEventListener(event, stop))
  }
  // 4. 等待正文可定位后，恢复快照或带滚动边距的锚点位置。
  function restore() {
    if (stopped || !document.body) {
      return
    }
    // 长文渐进显示完成前不读取隐藏标题的几何位置，也不保存一个过早的滚动结果。
    if (document.querySelector('.blog-renderer[data-reading-ready="false"]')) {
      return
    }
    let top
    let left = 0
    if (saved) {
      top = saved.top
      left = saved.left
    } else {
      let element
      try {
        element = document.getElementById(decodeURIComponent(window.location.hash.slice(1)))
      } catch {
        stop()
        return
      }
      if (!element) {
        if (document.readyState !== 'loading') {
          stop()
        }
        return
      }
      top = Math.max(
        0,
        element.getBoundingClientRect().top +
          window.scrollY -
          (parseFloat(window.getComputedStyle(element).scrollMarginTop) || 0),
      )
    }
    if (
      document.readyState === 'loading' &&
      document.documentElement.scrollHeight - window.innerHeight < top
    ) {
      return
    }
    window.scrollTo({ top, left, behavior: 'instant' })
    stop()
  }
  observer = new MutationObserver(restore)
  observer.observe(document, { childList: true, subtree: true })
  interactions.forEach((event) =>
    window.addEventListener(event, stop, { passive: true, once: true }),
  )
  document.addEventListener('DOMContentLoaded', restore, { once: true })
  function beforePaint() {
    frame = 0
    restore()
    if (!stopped) {
      frame = window.requestAnimationFrame(beforePaint)
    }
  }
  frame = window.requestAnimationFrame(beforePaint)
  restore()
})()`

/** 初始文档不二次定位；仅处理站内导航，保留历史返回与减少动效支持。 */
export function navigationScroll(
  to: { path: string; hash: string },
  from: { path: string; hash: string; matched: unknown[] },
  savedPosition: { left: number; top: number } | null,
  prefersReducedMotion: boolean,
) {
  // head 已在首帧前恢复当前位置。水合时再按 hash 定位会覆盖用户的实际阅读位置，
  // 按框架旧快照定位也可能倒退；此处必须配合 head 脚本，而非单独返回 false。
  if (!from.matched.length) {
    return false
  }
  if (savedPosition) {
    return { ...savedPosition, behavior: 'instant' as const }
  }
  if (to.hash) {
    return {
      el: to.hash,
      behavior: prefersReducedMotion ? ('instant' as const) : ('smooth' as const),
    }
  }
  if (to.path === from.path && !from.hash) {
    return false
  }
  return { left: 0, top: 0, behavior: 'instant' as const }
}
