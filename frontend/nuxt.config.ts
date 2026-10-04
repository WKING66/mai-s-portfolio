import tailwindcss from '@tailwindcss/vite'

export default defineNuxtConfig({
  compatibilityDate: '2026-09-26',
  devtools: { enabled: false },
  css: ['~/assets/css/main.css'],
  vite: { plugins: [tailwindcss()] },
  runtimeConfig: {
    public: { authRsaPublicKey: '' },
  },
  routeRules: {
    '/api/v1/**': {
      proxy: `${process.env.NUXT_BACKEND_URL || 'http://127.0.0.1:9333'}/api/v1/**`,
    },
  },
  app: {
    head: {
      htmlAttrs: { lang: 'zh-CN' },
      title: '阿霾 · 个人作品集',
    },
  },
})
