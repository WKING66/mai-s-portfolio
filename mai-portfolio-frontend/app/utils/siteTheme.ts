export type SiteTheme = 'light' | 'dark'
export const THEME_PREFERENCE_KEY = 'portfolio-theme'
export function isSiteTheme(value: unknown): value is SiteTheme {
  return value === 'light' || value === 'dark'
}

/** 必须自包含：序列化为 head 内联脚本，在样式/水合/会话请求之前运行。 */
function bootstrapSiteTheme() {
  // 1. 读取旧版本的本地偏好，存储不可用时继续使用 Cookie 或系统主题。
  let storedPreference: string | null = null
  try {
    storedPreference = localStorage.getItem('portfolio-theme')
  } catch {
    /* 禁用存储时仍可使用 Cookie/系统主题。 */
  }
  // 2. Cookie 与 SSR 保持一致，优先于可能过期的本地存储。
  const cookiePreference = document.cookie.match(
    /(?:^|;\s*)portfolio-theme=(light|dark)(?:;|$)/,
  )?.[1]
  const preference =
    cookiePreference ||
    (storedPreference === 'light' || storedPreference === 'dark' ? storedPreference : null)
  const theme =
    preference || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
  // 3. 在水合前一次性设置主题、配色和底色，避免首屏闪白。
  document.documentElement.dataset.theme = theme
  document.documentElement.classList.toggle('dark', theme === 'dark')
  document.documentElement.style.colorScheme = theme
  document.documentElement.style.backgroundColor = theme === 'dark' ? '#10191C' : '#F5F7F6'
  // 将上一版 localStorage 中的明确选择迁移为非敏感 Cookie，后续 SSR 即可匹配。
  if (preference) {
    document.cookie =
      'portfolio-theme=' +
      theme +
      '; Path=/; Max-Age=31536000; SameSite=Lax' +
      (window.location.protocol === 'https:' ? '; Secure' : '')
  }
}

export const THEME_BOOTSTRAP_SCRIPT = '(' + bootstrapSiteTheme.toString() + ')()'
