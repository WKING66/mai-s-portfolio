/** 刷新立即恢复历史位置/锚点，不等待会话；仅站内锚点切换使用平滑滚动。 */
export function navigationScroll(to: { path: string; hash: string }, from: { path: string; hash: string; matched: unknown[] }, saved: { left: number; top: number } | null, reducedMotion: boolean) {
  // Nuxt 在站内导航后切为 manual 恢复。初次加载一律 false 会丢失刷新锚点，
  // 所以优先还原框架传入的位置；无历史位置时立即定位片段，不重置到页面顶部。
  if (!from.matched.length) {
    if (saved) return { ...saved, behavior: 'instant' as const }
    return to.hash ? { el: to.hash, behavior: 'instant' as const } : false
  }
  if (saved) return { ...saved, behavior: 'instant' as const }
  if (to.hash) return { el: to.hash, behavior: reducedMotion ? 'instant' as const : 'smooth' as const }
  if (to.path === from.path && !from.hash) return false
  return { left: 0, top: 0, behavior: 'instant' as const }
}
