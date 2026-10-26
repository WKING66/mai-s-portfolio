import { createRobotsText, getSiteOrigin } from '#shared/utils/seo'

export default defineEventHandler((event) => {
  // 公开域名来自部署配置，不从请求 Host 推断；未配置时沿用禁止抓取策略。
  const siteOrigin = getSiteOrigin(useRuntimeConfig(event).public.siteUrl)
  setHeader(event, 'Content-Type', 'text/plain; charset=utf-8')
  return createRobotsText(siteOrigin)
})
