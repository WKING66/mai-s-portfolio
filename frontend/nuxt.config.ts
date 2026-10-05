import tailwindcss from '@tailwindcss/vite'
import { SEO_NOINDEX, SEO_SITE_NAME } from './shared/utils/seo'

export default defineNuxtConfig({
  compatibilityDate: '2026-09-26',
  devtools: { enabled: false },
  css: ['~/assets/css/main.css'],
  vite: { plugins: [tailwindcss()] },
  runtimeConfig: {
    public: { authRsaPublicKey: '', siteUrl: '' },
  },
  routeRules: {
    '/api/v1/**': {
      proxy: `${process.env.NUXT_BACKEND_URL || 'http://127.0.0.1:9333'}/api/v1/**`,
    },
    '/admin/**': { headers: { 'X-Robots-Tag': SEO_NOINDEX } },
    '/login': { headers: { 'X-Robots-Tag': SEO_NOINDEX } },
    '/register': { headers: { 'X-Robots-Tag': SEO_NOINDEX } },
    '/forbidden': { headers: { 'X-Robots-Tag': SEO_NOINDEX } },
  },
  app: {
    head: {
      htmlAttrs: { lang: 'zh-CN' },
      title: SEO_SITE_NAME,
    },
  },
})
