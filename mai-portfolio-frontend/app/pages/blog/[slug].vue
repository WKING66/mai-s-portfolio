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
async function copyArticleLink() {
  try {
    await navigator.clipboard.writeText(
      new URL('/blog/' + encodeURIComponent(articleSlug.value), location.origin).href,
    )
    feedback.success('文章链接已复制。')
  } catch {
    feedback.error('链接复制失败，请从地址栏复制。')
  }
}
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
    <NuxtLink to="/blog" class="article-back text-accent">← 博客笔记</NuxtLink>
    <UButton v-if="blogError" class="mt-8" @click="refreshBlogPost()">重新读取文章</UButton>
    <article v-else-if="blogPost" class="blog-reading-article">
      <header class="article-intro" aria-labelledby="article-title">
        <div class="article-intro-copy">
          <p class="eyebrow">笔记 / JOURNAL</p>
          <h1 id="article-title">{{ blogPost.title }}</h1>
          <p v-if="blogPost.summary" class="article-summary">{{ blogPost.summary }}</p>
        </div>
        <div class="article-intro-footer">
          <dl class="article-metadata">
            <div v-if="blogPost.publishedAt">
              <dt>发布</dt>
              <dd>
                <time :datetime="blogPost.publishedAt">{{
                  blogPost.publishedAt.slice(0, 10)
                }}</time>
              </dd>
            </div>
            <div
              v-if="
                blogPost.updatedAt &&
                blogPost.updatedAt.slice(0, 10) !== blogPost.publishedAt?.slice(0, 10)
              "
            >
              <dt>更新</dt>
              <dd>
                <time :datetime="blogPost.updatedAt">{{ blogPost.updatedAt.slice(0, 10) }}</time>
              </dd>
            </div>
          </dl>
          <UButton
            color="neutral"
            variant="ghost"
            icon="i-lucide-link"
            aria-label="复制文章链接"
            @click="copyArticleLink"
          />
        </div>
      </header>
      <BlogRenderer
        :key="blogPost.contentId"
        :cache-key="'public:' + blogPost.contentId"
        :markdown="blogPost.content"
        :resources="blogPost.resources"
        show-toc
        class="mb-8"
      />
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

<style scoped>
.article-back {
  display: block;
  max-width: 72rem;
  margin-inline: auto;
}
/* 标题区在阅读器外独立铺开，正文目录从下一屏开始，保持原有正文与锚点节点。 */
.article-intro {
  display: flex;
  flex-direction: column;
  min-height: calc(
    100svh - var(--site-header-height) - 2 * var(--blog-reading-start-gap) - 1.75rem
  );
  max-width: 72rem;
  margin-inline: auto;
  padding-top: clamp(2.5rem, 6svh, 5rem);
  gap: clamp(2.5rem, 6svh, 5rem);
}
.article-intro-copy {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding-bottom: clamp(1rem, 3svh, 3rem);
}
.article-intro h1 {
  max-width: 100%;
  margin-top: 1.25rem;
  font-size: clamp(2.5rem, 5.8vw, 5.75rem);
  font-weight: 550;
  line-height: 1.22;
  letter-spacing: -0.045em;
  text-wrap: balance;
  overflow-wrap: anywhere;
}
.article-summary {
  max-width: 52rem;
  margin-top: clamp(1.5rem, 4svh, 3rem);
  font-size: clamp(1.0625rem, 1.65vw, 1.375rem);
  line-height: 1.95;
  color: var(--site-muted);
  overflow-wrap: anywhere;
}
.article-intro-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1.5rem;
  border-block: 1px solid var(--site-line);
  padding-block: 1.5rem;
}
.article-metadata {
  display: flex;
  flex-wrap: wrap;
  gap: 1.5rem clamp(2rem, 5vw, 5rem);
  font-size: 0.9375rem;
}
.article-metadata dt {
  margin-bottom: 0.5rem;
  font-size: 0.75rem;
  color: var(--site-muted);
}
.blog-reading-article > :deep(.blog-reader) {
  margin-top: clamp(2.5rem, 6vw, 5rem);
}
@media (max-width: 600px) {
  .article-intro {
    padding-top: 2rem;
    gap: 2rem;
  }
  .article-intro h1 {
    font-size: clamp(2.25rem, 8.5vw, 3.25rem);
    line-height: 1.3;
    letter-spacing: -0.035em;
  }
  .article-intro-footer {
    padding-block: 1.25rem;
    gap: 0.75rem;
  }
  .article-metadata {
    font-size: 0.875rem;
    gap: 1.25rem;
  }
}
</style>
