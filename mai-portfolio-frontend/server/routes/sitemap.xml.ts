import { createSitemapXml, getSiteOrigin, SEO_SITE_URL_UNCONFIGURED } from '#shared/utils/seo'

export default defineEventHandler((event) => {
  const siteOrigin = getSiteOrigin(useRuntimeConfig(event).public.siteUrl)
  const sitemap = createSitemapXml(siteOrigin)
  if (sitemap === null) {
    setResponseStatus(event, 503)
    setHeader(event, 'Content-Type', 'text/plain; charset=utf-8')
    return SEO_SITE_URL_UNCONFIGURED + '\n'
  }
  setHeader(event, 'Content-Type', 'application/xml; charset=utf-8')
  return sitemap
})
