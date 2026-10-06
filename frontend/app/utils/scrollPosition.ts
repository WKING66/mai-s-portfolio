/** 整页刷新不干预原生锚点与恢复；只有站内导航才主动滚动。 */
export function navigationScroll(to: { path: string; hash: string }, from: { path: string; hash: string; matched: unknown[] }, saved: { left: number; top: number } | null, reducedMotion: boolean) {
  if (!from.matched.length) return false
  if (saved) return { ...saved, behavior: 'instant' as const }
  if (to.hash) return { el: to.hash, behavior: reducedMotion ? 'instant' as const : 'smooth' as const }
  if (to.path === from.path && !from.hash) return false
  return { left: 0, top: 0, behavior: 'instant' as const }
}
