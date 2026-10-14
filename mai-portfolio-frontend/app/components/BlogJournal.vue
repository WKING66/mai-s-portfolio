<script setup lang="ts">
import { BLOG_PATH, type BlogPage } from '../api/blogs'
import type { ApiResponse } from '../api/types'
import {
  BLOG_HOME_ARTICLE_COUNT,
  BLOG_PUBLIC_CACHE_PREFIX,
  BLOG_VIEW,
} from '../constants/blogs'
// 首页只读公开摘要，保持 SSR 展示；管理权限和最新草稿不能混入该缓存。
const response = await useFetch<ApiResponse<BlogPage>>(BLOG_PATH, {
  key: BLOG_PUBLIC_CACHE_PREFIX + 'home',
  query: { view: BLOG_VIEW.PUBLIC, size: BLOG_HOME_ARTICLE_COUNT },
})
const items = computed(() => response.data.value?.data?.items ?? [])
useReadErrorFeedback(() => response.error.value, '文章读取失败，请重试。')
</script>
<template>
  <BlogCards v-if="items.length" :items="items" />
  <UButton
    v-else-if="response.error.value"
    color="neutral"
    variant="outline"
    @click="response.refresh()"
    >重新读取文章</UButton
  >
  <p v-else class="border-y border-line py-7 text-muted">文章尚未发布。</p>
  <NuxtLink
    v-if="(response.data.value?.data?.total ?? 0) > BLOG_HOME_ARTICLE_COUNT"
    to="/blog"
    class="mt-5 inline-block text-accent"
    >更多文章 ↗</NuxtLink
  >
</template>
