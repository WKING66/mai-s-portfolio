<script setup lang="ts">
import { BLOG_PATH, type BlogPage } from '../../api/blogs'
import type { ApiResponse } from '../../api/types'
import {
  BLOG_FIRST_PAGE,
  BLOG_MAX_PAGE_NUMBER,
  BLOG_PUBLIC_PAGE_SIZE,
  BLOG_PUBLIC_CACHE_PREFIX,
  BLOG_VIEW,
} from '../../constants/blogs'
const route = useRoute()
const router = useRouter()
// 1. URL 与分页控件共享页码；首页省略 page 参数，非法值回到既定第一页。
const currentPage = computed({
  get: () => {
    const pageNumber = Number(route.query.page ?? BLOG_FIRST_PAGE)
    return Number.isSafeInteger(pageNumber) &&
      pageNumber >= BLOG_FIRST_PAGE &&
      pageNumber <= BLOG_MAX_PAGE_NUMBER
      ? pageNumber
      : BLOG_FIRST_PAGE
  },
  set: (pageNumber) => {
    void router.replace({
      query: { page: pageNumber === BLOG_FIRST_PAGE ? undefined : String(pageNumber) },
    })
  },
})
// 2. 缓存键与请求参数使用同一页码，公开列表不复用管理草稿投影。
const {
  data: blogListResponse,
  error: blogListError,
  refresh: refreshBlogPosts,
} = await useFetch<ApiResponse<BlogPage>>(BLOG_PATH, {
  key: () => BLOG_PUBLIC_CACHE_PREFIX + 'list:' + currentPage.value,
  query: { view: BLOG_VIEW.PUBLIC, page: currentPage, size: BLOG_PUBLIC_PAGE_SIZE },
})
useReadErrorFeedback(() => blogListError.value, '文章读取失败，请重试。')
usePortfolioSeo({
  title: () => '博客笔记',
  description: () => '技术实践、项目复盘与学习记录',
  canonicalPath: () =>
    currentPage.value === BLOG_FIRST_PAGE ? '/blog' : '/blog?page=' + currentPage.value,
  available: () => !blogListError.value && Boolean(blogListResponse.value?.data?.items.length),
})
</script>
<template>
  <main class="site-container site-page">
    <p class="eyebrow mb-3">JOURNAL</p>
    <h1 class="site-page-title mb-3">博客笔记</h1>
    <p class="mb-8 text-muted">技术实践、项目复盘与学习记录。</p>
    <UButton v-if="blogListError" @click="refreshBlogPosts()">重新读取</UButton>
    <BlogCards
      v-else-if="blogListResponse?.data?.items.length"
      :items="blogListResponse.data.items"
    />
    <p v-else class="text-muted">文章尚未发布。</p>
    <UPagination
      v-if="blogListResponse?.data && blogListResponse.data.total > BLOG_PUBLIC_PAGE_SIZE"
      v-model:page="currentPage"
      :total="blogListResponse.data.total"
      :items-per-page="BLOG_PUBLIC_PAGE_SIZE"
      class="mt-8"
    />
  </main>
</template>
