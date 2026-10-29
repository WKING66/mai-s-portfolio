/** 定位反馈只属于目标元素的显示状态，不创建编辑器事务或改动受 PM 管理的 DOM 属性。 */
export function highlightImmersivePosition(element: HTMLElement): Animation | undefined {
  if (!['P', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6'].includes(element.tagName)) {
    return
  }
  const color = 'color-mix(in srgb, var(--site-accent) 16%, transparent)'
  const reduced = element.ownerDocument.defaultView?.matchMedia(
    '(prefers-reduced-motion: reduce)',
  ).matches
  return element.animate(
    [
      { backgroundColor: color, borderRadius: '0.25rem' },
      { backgroundColor: reduced ? color : 'transparent', borderRadius: '0.25rem' },
    ],
    { duration: reduced ? 300 : 1100, easing: 'ease-out' },
  )
}
