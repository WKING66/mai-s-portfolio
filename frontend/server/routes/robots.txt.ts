import { createRobotsText, getSiteOrigin } from '#shared/utils/seo'

export default defineEventHandler((event) => {
  const siteOrigin = getSiteOrigin(useRuntimeConfig(event).public.siteUrl)
  setHeader(event, 'Content-Type', 'text/plain; charset=utf-8')
  return createRobotsText(siteOrigin)
})
