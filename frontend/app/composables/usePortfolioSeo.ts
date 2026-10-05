import { useHead, useRuntimeConfig, useSeoMeta } from '#app'
import { computed, toValue, type MaybeRefOrGetter } from 'vue'
import { getCanonicalUrl, getSiteOrigin, SEO_INDEX, SEO_NOINDEX, SEO_SITE_NAME } from '#shared/utils/seo'

interface PortfolioSeoOptions {
  title: MaybeRefOrGetter<string>
  description: MaybeRefOrGetter<string>
  canonicalPath: MaybeRefOrGetter<string>
  available: MaybeRefOrGetter<boolean>
}

/** 首页与项目页共享完整元数据策略，页面仍拥有自己的公开内容。 */
export function usePortfolioSeo(options: PortfolioSeoOptions) {
  const siteOrigin = getSiteOrigin(useRuntimeConfig().public.siteUrl)
  const canonicalUrl = computed(() => getCanonicalUrl(siteOrigin, toValue(options.canonicalPath)))
  useSeoMeta({
    title: () => toValue(options.title),
    description: () => toValue(options.description) || undefined,
    robots: () => siteOrigin && toValue(options.available) ? SEO_INDEX : SEO_NOINDEX,
    ogTitle: () => toValue(options.title),
    ogDescription: () => toValue(options.description) || undefined,
    ogUrl: () => canonicalUrl.value,
    ogType: 'website',
    ogSiteName: SEO_SITE_NAME,
    ogLocale: 'zh_CN',
  })
  useHead(() => ({ link: canonicalUrl.value ? [{ rel: 'canonical', href: canonicalUrl.value }] : [] }))
  return { siteOrigin }
}
