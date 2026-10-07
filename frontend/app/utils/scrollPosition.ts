/**
 * SPA 导航把当前历史条目的恢复模式改成 manual；该模式会带到下一次刷新。
 * 在 SSR head 中同步恢复 auto，让浏览器在首帧布局时恢复位置，而非等客户端水合。
 * 仅在新文档加载时执行；不覆盖后续 Vue Router 的 manual 模式，不另存滚动坐标。
 */
export const SCROLL_RESTORATION_BOOTSTRAP_SCRIPT = "if ('scrollRestoration' in window.history) window.history.scrollRestoration = 'auto';"

/** 初始文档不二次定位；仅处理站内导航，保留历史返回与减少动效支持。 */
export function navigationScroll(to: { path: string; hash: string }, from: { path: string; hash: string; matched: unknown[] }, saved: { left: number; top: number } | null, reducedMotion: boolean) {
  // head 已在首帧前启用原生恢复。水合时再按 hash 定位会覆盖用户的实际阅读位置，
  // 按框架旧快照定位也可能倒退；此处必须配合 head 脚本，而非单独返回 false。
  if (!from.matched.length) return false
  if (saved) return { ...saved, behavior: 'instant' as const }
  if (to.hash) return { el: to.hash, behavior: reducedMotion ? 'instant' as const : 'smooth' as const }
  if (to.path === from.path && !from.hash) return false
  return { left: 0, top: 0, behavior: 'instant' as const }
}
