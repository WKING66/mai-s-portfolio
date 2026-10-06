import { SEO_NOINDEX, SEO_SITE_NAME } from './shared/utils/seo'
import { THEME_BOOTSTRAP_SCRIPT } from './app/utils/siteTheme'

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
      script: [{ key: 'site-theme-bootstrap', innerHTML: THEME_BOOTSTRAP_SCRIPT, tagPriority: 'critical' }],
    },
  },
})
