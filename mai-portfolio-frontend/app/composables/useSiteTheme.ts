import { useCookie, useState } from '#app'
import { isSiteTheme, THEME_PREFERENCE_KEY, type SiteTheme } from '../utils/site/siteTheme'

/** 主题与认证完全独立；所有路由共用一次初始化，不等接口返回后再切色。 */
export function useSiteTheme() {
  // 1. SSR 从现有 Cookie 恢复主题；沿用同一状态键，不新增主题状态源。
  const preference = useCookie<SiteTheme | null>(THEME_PREFERENCE_KEY, {
    path: '/',
    sameSite: 'lax',
    maxAge: 365 * 24 * 60 * 60,
    // 与首屏脚本使用相同的明文枚举编码，不让默认 JSON 引号破坏首屏 Cookie 识别。
    encode: (value) => value || '',
    decode: (value) => (isSiteTheme(value) ? value : null),
  })
  const theme = useState<SiteTheme | null>('site-theme', () =>
    isSiteTheme(preference.value) ? preference.value : null,
  )

  function apply(value: SiteTheme) {
    document.documentElement.dataset.theme = value
    document.documentElement.classList.toggle('dark', value === 'dark')
    document.documentElement.style.colorScheme = value
    document.documentElement.style.backgroundColor = getComputedStyle(document.documentElement)
      .getPropertyValue('--site-bg')
      .trim()
  }
  function initialize() {
    const paintedTheme = document.documentElement.dataset.theme
    // 2. 首屏脚本已绘制的主题优先，只有未恢复时才读取系统偏好。
    if (isSiteTheme(paintedTheme)) {
      theme.value = paintedTheme
    } else {
      theme.value = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
    }
    apply(theme.value)
  }
  function toggleTheme() {
    // 3. 用户主动切换时同步页面、Cookie 与本地偏好；禁用存储不影响当前页面。
    const nextTheme: SiteTheme = theme.value === 'dark' ? 'light' : 'dark'
    theme.value = nextTheme
    preference.value = nextTheme
    apply(nextTheme)
    try {
      localStorage.setItem(THEME_PREFERENCE_KEY, nextTheme)
    } catch {
      /* Cookie 与当前页面仍能保存选择。 */
    }
  }
  return { theme, preference, initialize, toggleTheme }
}
