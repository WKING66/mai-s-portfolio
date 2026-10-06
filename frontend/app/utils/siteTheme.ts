export type SiteTheme = 'light' | 'dark'
export const THEME_PREFERENCE_KEY = 'portfolio-theme'
export function isSiteTheme(value: unknown): value is SiteTheme {
  return value === 'light' || value === 'dark'
}

/** 必须自包含：序列化为 head 内联脚本，在样式/水合/会话请求之前运行。 */
function bootstrapSiteTheme() {
  let stored: string | null = null
  try { stored = localStorage.getItem('portfolio-theme') } catch { /* 禁用存储时仍可使用 Cookie/系统主题。 */ }
  const cookie = document.cookie.match(/(?:^|;\s*)portfolio-theme=(light|dark)(?:;|$)/)?.[1]
  const preference = cookie || (stored === 'light' || stored === 'dark' ? stored : null)
  const theme = preference || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
  document.documentElement.dataset.theme = theme
  document.documentElement.classList.toggle('dark', theme === 'dark')
  document.documentElement.style.colorScheme = theme
  document.documentElement.style.backgroundColor = theme === 'dark' ? '#071018' : '#f5f7fa'
  // 将上一版 localStorage 中的明确选择迁移为非敏感 Cookie，后续 SSR 即可匹配。
  if (preference) document.cookie = 'portfolio-theme=' + theme + '; Path=/; Max-Age=31536000; SameSite=Lax'
    + (window.location.protocol === 'https:' ? '; Secure' : '')
}

export const THEME_BOOTSTRAP_SCRIPT = '(' + bootstrapSiteTheme.toString() + ')()'
