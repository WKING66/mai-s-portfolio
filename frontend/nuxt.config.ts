import { SEO_NOINDEX, SEO_SITE_NAME } from './shared/utils/seo'
import { THEME_BOOTSTRAP_SCRIPT } from './app/utils/siteTheme'
import { SCROLL_RESTORATION_BOOTSTRAP_SCRIPT } from './app/utils/scrollPosition'

export default defineNuxtConfig({
  compatibilityDate: '2026-09-26',
  devtools: { enabled: false },
  modules: ['@nuxt/ui', '@pinia/nuxt'],
  // 复用已有主题和系统字体，不引入远程字体请求或第二套主题状态。
  ui: { fonts: false, colorMode: false },
  icon: { serverBundle: { collections: ['lucide'] } },
  css: ['~/assets/css/main.css'],
  runtimeConfig: {
    public: { authRsaPublicKey: '', siteUrl: '' },
  },
  routeRules: {
    '/api/v1/**': {
      proxy: `${process.env.NUXT_BACKEND_URL || 'http://127.0.0.1:9333'}/api/v1/**`,
    },
    '/admin/**': { headers: { 'X-Robots-Tag': SEO_NOINDEX } },
    '/account': { headers: { 'X-Robots-Tag': SEO_NOINDEX } },
    '/login': { headers: { 'X-Robots-Tag': SEO_NOINDEX } },
    '/register': { headers: { 'X-Robots-Tag': SEO_NOINDEX } },
    '/forbidden': { headers: { 'X-Robots-Tag': SEO_NOINDEX } },
  },
  app: {
    head: {
      htmlAttrs: { lang: 'zh-CN' },
      title: SEO_SITE_NAME,
      script: [
        { key: 'site-theme-bootstrap', innerHTML: THEME_BOOTSTRAP_SCRIPT, tagPriority: 'critical' },
        // 必须先于 Nuxt 客户端启动执行，否则首次刷新已绘制顶部，路由兜底只能晚些跳回。
        { key: 'site-scroll-restoration', innerHTML: SCROLL_RESTORATION_BOOTSTRAP_SCRIPT, tagPriority: 'critical' },
      ],
    },
  },
})
