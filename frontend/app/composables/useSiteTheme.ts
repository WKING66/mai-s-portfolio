import { useCookie, useState } from '#app'
import { isSiteTheme, THEME_PREFERENCE_KEY, type SiteTheme } from '../utils/siteTheme'

/** 主题与认证完全独立；所有路由共用一次初始化，不等接口返回后再切色。 */
export function useSiteTheme() {
  const preference = useCookie<SiteTheme | null>(THEME_PREFERENCE_KEY, {
    path: '/', sameSite: 'lax', maxAge: 365 * 24 * 60 * 60,
    // 与首屏脚本使用相同的明文枚举编码，不让默认 JSON 引号破坏首屏 Cookie 识别。
    encode: value => value || '', decode: value => isSiteTheme(value) ? value : null,
  })
  const theme = useState<SiteTheme | null>('site-theme', () => isSiteTheme(preference.value) ? preference.value : null)

  function apply(value: SiteTheme) {
    document.documentElement.dataset.theme = value
    document.documentElement.classList.toggle('dark', value === 'dark')
    document.documentElement.style.colorScheme = value
    document.documentElement.style.backgroundColor = value === 'dark' ? '#071018' : '#f5f7fa'
  }
  function initialize() {
    const painted = document.documentElement.dataset.theme
    theme.value = isSiteTheme(painted) ? painted : window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
    apply(theme.value)
  }
  function toggleTheme() {
    const next: SiteTheme = theme.value === 'dark' ? 'light' : 'dark'
    theme.value = next
    preference.value = next
    apply(next)
    try { localStorage.setItem(THEME_PREFERENCE_KEY, next) } catch { /* Cookie 与当前页面仍能保存选择。 */ }
  }
  return { theme, preference, initialize, toggleTheme }
}
