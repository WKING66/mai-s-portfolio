/** 公式保留完整 HTML/MathML；测得真实尺寸后才允许浏览器跳过屏外布局。 */
export function createMathLayoutObserver() {
  let observer: ResizeObserver | undefined
  const targets = new Set<HTMLElement>()
  function clear(element: HTMLElement) {
    observer?.unobserve(element)
    targets.delete(element)
    element.style.removeProperty('content-visibility')
    element.style.removeProperty('contain-intrinsic-size')
  }
  return {
    observe(element: HTMLElement) {
      if (typeof ResizeObserver === 'undefined') {
        return () => {}
      }
      observer ??= new ResizeObserver((entries) => {
        for (const entry of entries) {
          const target = entry.target as HTMLElement
          if (
            !targets.has(target) ||
            entry.contentRect.width <= 0 ||
            entry.contentRect.height <= 0
          ) {
            continue
          }
          const size = `auto ${entry.contentRect.width}px auto ${entry.contentRect.height}px`
          if (target.style.containIntrinsicSize !== size) {
            target.style.containIntrinsicSize = size
          }
          target.style.contentVisibility = 'auto'
        }
      })
      targets.add(element)
      observer.observe(element)
      return () => clear(element)
    },
    dispose() {
      for (const element of targets) {
        clear(element)
      }
      observer?.disconnect()
      observer = undefined
    },
  }
}
