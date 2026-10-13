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
const page = computed({
  get: () => {
    const value = Number(route.query.page ?? BLOG_FIRST_PAGE)
    return Number.isSafeInteger(value) &&
      value >= BLOG_FIRST_PAGE &&
      value <= BLOG_MAX_PAGE_NUMBER
      ? value
      : BLOG_FIRST_PAGE
  },
  set: (value) => {
    void router.replace({
      query: { page: value === BLOG_FIRST_PAGE ? undefined : String(value) },
    })
  },
})
const { data, error, refresh } = await useFetch<ApiResponse<BlogPage>>(
  BLOG_PATH,
  {
    key: () => BLOG_PUBLIC_CACHE_PREFIX + 'list:' + page.value,
    query: { view: BLOG_VIEW.PUBLIC, page, size: BLOG_PUBLIC_PAGE_SIZE },
  },
)
useReadErrorFeedback(() => error.value, '文章读取失败，请重试。')
usePortfolioSeo({
  title: () => '博客笔记',
  description: () => '技术实践、项目复盘与学习记录',
  canonicalPath: () =>
    page.value === BLOG_FIRST_PAGE ? '/blog' : '/blog?page=' + page.value,
  available: () => !error.value && Boolean(data.value?.data?.items.length),
})
</script>
<template>
  <main class="site-container site-page">
    <p class="eyebrow mb-3">JOURNAL</p>
    <h1 class="site-page-title mb-3">博客笔记</h1>
    <p class="mb-8 text-muted">技术实践、项目复盘与学习记录。</p>
    <UButton v-if="error" @click="refresh()">重新读取</UButton>
    <BlogCards v-else-if="data?.data?.items.length" :items="data.data.items" />
    <p v-else class="text-muted">文章尚未发布。</p>
    <UPagination
      v-if="data?.data && data.data.total > BLOG_PUBLIC_PAGE_SIZE"
      v-model:page="page"
      :total="data.data.total"
      :items-per-page="BLOG_PUBLIC_PAGE_SIZE"
      class="mt-8"
    />
  </main>
</template>
