<script setup lang="ts">
import { BLOG_PATH, useBlogApi, type Blog } from '../../api/blogs'
import type { ApiResponse } from '../../api/types'
import { useAssetUrlRefresh } from '../../composables/useAssetUrlRefresh'
import { assetUrlsFresh } from '../../utils/assets/urls'
import { BLOG_PUBLIC_CACHE_PREFIX } from '../../constants/blogs'
import BlogRenderer from '../../components/blog/BlogRenderer.vue'
const route = useRoute()
const articleSlug = computed(() => String(route.params.slug))
// 1. SSR 与浏览器共享公开投影；签名地址过期则重新读取，正文引用不改写。
const {
  data: blogResponse,
  error: blogError,
  refresh: refreshBlogPost,
} = await useFetch<ApiResponse<Blog>>(
  () => BLOG_PATH + '/by-slug/' + encodeURIComponent(articleSlug.value),
  {
    key: () => BLOG_PUBLIC_CACHE_PREFIX + 'article:' + articleSlug.value,
    getCachedData: (cacheKey, nuxtApp) => {
      const cachedBlogResponse = nuxtApp.payload.data[cacheKey] ?? nuxtApp.static.data[cacheKey]
      return cachedBlogResponse && assetUrlsFresh(cachedBlogResponse)
        ? cachedBlogResponse
        : undefined
    },
  },
)
if (blogError.value?.statusCode === 404) {
  throw createError({ statusCode: 404, statusMessage: '文章不存在或未公开', fatal: true })
}
const blogPost = computed(() => blogResponse.value?.data)
const feedback = useFeedback()
const blogApi = useBlogApi()
const includeResources = ref(false)
const isDownloading = ref(false)
useReadErrorFeedback(() => blogError.value, '文章读取失败，请重试。')
// 2. 复用资源地址续签调度和统一阅读器，不在页面重新解析 Markdown。
useAssetUrlRefresh(() => blogResponse.value, refreshBlogPost)
usePortfolioSeo({
  title: () => blogPost.value?.title ?? '博客',
  description: () => blogPost.value?.summary ?? '',
  canonicalPath: () => '/blog/' + articleSlug.value,
  available: () => Boolean(blogPost.value) && !blogError.value,
})
// 3. 下载操作互斥；是否允许正式导出仍由后端独立校验。
async function downloadBlogPost() {
  // 复用管理端同一下载入口，后端另行检查公开状态和访客下载许可。
  if (!blogPost.value || isDownloading.value) {
    return
  }
  isDownloading.value = true
  try {
    await blogApi.download(blogPost.value.id, includeResources.value)
  } catch (cause) {
    feedback.error(cause)
  } finally {
    isDownloading.value = false
  }
}
</script>
<template>
  <main class="site-container site-page blog-reading-page">
    <NuxtLink to="/blog" class="text-accent">← 博客笔记</NuxtLink>
    <UButton v-if="blogError" class="mt-8" @click="refreshBlogPost()">重新读取文章</UButton>
    <article v-else-if="blogPost" class="blog-reading-article">
      <BlogRenderer
        :key="blogPost.contentId"
        :cache-key="'public:' + blogPost.contentId"
        :markdown="blogPost.content"
        :resources="blogPost.resources"
        show-toc
        class="mb-8"
      >
        <template #header>
          <h1 class="site-page-title break-words">{{ blogPost.title }}</h1>
          <p class="mt-3 font-mono text-sm text-muted">
            {{ blogPost.publishedAt?.slice(0, 10) }}
          </p>
          <p v-if="blogPost.summary" class="my-6 text-lg leading-8 text-muted">
            {{ blogPost.summary }}
          </p>
        </template>
      </BlogRenderer>
      <div
        v-if="blogPost.allowDownload"
        class="flex flex-wrap items-center gap-4 border-t border-line pt-6"
      >
        <UCheckbox
          v-if="blogPost.resources.length"
          :id="'blog-export-resources-' + blogPost.id"
          v-model="includeResources"
          label="包含图片、附件（ZIP）"
        />
        <UButton :loading="isDownloading" icon="i-lucide-download" @click="downloadBlogPost">{{
          includeResources && blogPost.resources.length ? '下载正文与资源' : '下载 Markdown 正文'
        }}</UButton>
        <span class="text-xs text-muted">需要登录。仅正文中的资源链接有有效期。</span>
      </div>
    </article>
  </main>
</template>
