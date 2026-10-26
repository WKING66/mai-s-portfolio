import { SEO_NOINDEX, SEO_SITE_NAME } from './shared/utils/seo'
import { THEME_BOOTSTRAP_SCRIPT } from './app/utils/siteTheme'
import { SCROLL_RESTORATION_BOOTSTRAP_SCRIPT } from './app/utils/scrollPosition'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)

export default defineNuxtConfig({
  compatibilityDate: '2026-09-26',
  devtools: { enabled: false },
  modules: ['@comark/nuxt', '@nuxt/ui', '@pinia/nuxt', '@nuxt/eslint'],
  // 排版统一交给 Prettier；ESLint 保留业务质量与控制流程检查。
  eslint: { config: { stylistic: false } },
  // 复用已有主题和系统字体，不引入远程字体请求或第二套主题状态。
  ui: { fonts: false, colorMode: false, prose: true },
  icon: { serverBundle: { collections: ['lucide'] } },
  css: ['~/assets/css/main.css'],
  // 斜杠菜单与编辑器共享 ProseMirror 类，避免开发预构建和直接加载的 DecorationSet 混用。
  vite: {
    worker: { format: 'es' },
    resolve: {
      alias: {
        // 同一标准实体表用于页面和 Worker，避免 browser 条件入口访问 document。
        'decode-named-character-reference': require.resolve('decode-named-character-reference'),
      },
    },
    optimizeDeps: {
      include: ['@tiptap/suggestion', '@tiptap/pm/state', '@tiptap/pm/view'],
    },
  },
  runtimeConfig: {
    // 仅暴露客户端需要的公钥与站点地址，不包含后端凭据。
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
        // 1. 首屏先恢复用户保存的主题，避免客户端启动前闪白。
        { key: 'site-theme-bootstrap', innerHTML: THEME_BOOTSTRAP_SCRIPT, tagPriority: 'critical' },
        // 2. 在 Nuxt 客户端启动前恢复滚动位置，避免刷新先绘制顶部再跳回。
        {
          key: 'site-scroll-restoration',
          innerHTML: SCROLL_RESTORATION_BOOTSTRAP_SCRIPT,
          tagPriority: 'critical',
        },
      ],
    },
  },
})
