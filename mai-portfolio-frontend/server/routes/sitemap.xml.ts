import { createSitemapXml, getSiteOrigin, SEO_SITE_URL_UNCONFIGURED } from '#shared/utils/seo'

export default defineEventHandler((event) => {
  // 1. 复用统一域名校验与站点映射生成规则，不在路由中重复定义公开路径。
  const siteOrigin = getSiteOrigin(useRuntimeConfig(event).public.siteUrl)
  const sitemapXml = createSitemapXml(siteOrigin)
  // 2. 缺少部署域名时保留 503 与纯文本提示，不返回伪造的正式站点地址。
  if (sitemapXml === null) {
    setResponseStatus(event, 503)
    setHeader(event, 'Content-Type', 'text/plain; charset=utf-8')
    return SEO_SITE_URL_UNCONFIGURED + '\n'
  }
  setHeader(event, 'Content-Type', 'application/xml; charset=utf-8')
  return sitemapXml
})
