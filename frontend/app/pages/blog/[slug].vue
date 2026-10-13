<script setup lang="ts">
import { BLOG_PATH, useBlogApi, type Blog } from '../../api/blogs'
import type { ApiResponse } from '../../api/types'
import { useAssetUrlRefresh } from '../../composables/useAssetUrlRefresh'
import { assetUrlsFresh } from '../../utils/assetUrls'
import { BLOG_PUBLIC_CACHE_PREFIX } from '../../constants/blogs'
import BlogRenderer from '../../components/blog/BlogRenderer.vue'
const route = useRoute()
const slug = computed(() => String(route.params.slug))
// SSR 与浏览器共享公开投影；带签名地址的缓存必须仍新鲜，不能把旧 URL 永久持久化。
const { data, error, refresh } = await useFetch<ApiResponse<Blog>>(
  () => BLOG_PATH + '/by-slug/' + encodeURIComponent(slug.value),
  {
    key: () => BLOG_PUBLIC_CACHE_PREFIX + 'article:' + slug.value,
    getCachedData: (key, app) => {
      const cached = app.payload.data[key] ?? app.static.data[key]
      return cached && assetUrlsFresh(cached) ? cached : undefined
    },
  },
)
if (error.value?.statusCode === 404)
  throw createError({ statusCode: 404, statusMessage: '文章不存在或未公开', fatal: true })
const article = computed(() => data.value?.data)
const feedback = useFeedback()
const api = useBlogApi()
const includeResources = ref(false)
const downloading = ref(false)
useReadErrorFeedback(() => error.value, '文章读取失败，请重试。')
useAssetUrlRefresh(() => data.value, refresh)
usePortfolioSeo({
  title: () => article.value?.title ?? '博客',
  description: () => article.value?.summary ?? '',
  canonicalPath: () => '/blog/' + slug.value,
  available: () => Boolean(article.value) && !error.value,
})
async function download() {
  // 复用管理端同一下载入口，后端另行检查公开状态和访客下载许可。
  if (!article.value || downloading.value) return
  downloading.value = true
  try {
    await api.download(article.value.id, includeResources.value)
  } catch (e) {
    feedback.error(e)
  } finally {
    downloading.value = false
  }
}
</script>
<template>
  <main class="site-container site-page blog-reading-page">
    <NuxtLink to="/blog" class="text-accent">← 博客笔记</NuxtLink>
    <UButton v-if="error" class="mt-8" @click="refresh()">重新读取文章</UButton>
    <article v-else-if="article" class="blog-reading-article">
      <BlogRenderer :key="article.documentVersionId" :cache-key="'public:' + article.documentVersionId" :markdown="article.content" :resources="article.resources" show-toc class="mb-8">
      <template #header>
      <h1 class="site-page-title break-words">{{ article.title }}</h1>
      <p class="mt-3 font-mono text-sm text-muted">
        {{ article.publishedAt?.slice(0, 10) }}
      </p>
      <p v-if="article.summary" class="my-6 text-lg leading-8 text-muted">
        {{ article.summary }}
      </p>
      </template>
      </BlogRenderer>
      <div
        v-if="article.allowDownload"
        class="flex flex-wrap items-center gap-4 border-t border-line pt-6"
      >
        <UCheckbox
          v-if="article.resources.length"
          :id="'blog-export-resources-' + article.id"
          v-model="includeResources"
          label="包含图片、附件（ZIP）"
        />
        <UButton
          :loading="downloading"
          icon="i-lucide-download"
          @click="download"
          >{{
            includeResources && article.resources.length
              ? '下载正文与资源'
              : '下载 Markdown 正文'
          }}</UButton
        >
        <span class="text-xs text-muted"
          >需要登录。仅正文中的资源链接有有效期。</span
        >
      </div>
    </article>
  </main>
</template>
