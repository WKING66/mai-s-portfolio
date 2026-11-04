<script setup lang="ts">
import type { DocumentResource } from '../../api/blogs'
import BlogArticleIntro from './BlogArticleIntro.vue'
import BlogRenderer from './BlogRenderer.vue'
withDefaults(
  defineProps<{
    title: string
    summary?: string | null
    publishedAt?: string | null
    updatedAt?: string | null
    markdown: string
    resources?: DocumentResource[]
    cacheKey?: string
    scrollRoot?: HTMLElement | null
    shareable?: boolean
  }>(),
  {
    resources: () => [],
    summary: undefined,
    publishedAt: undefined,
    updatedAt: undefined,
    shareable: true,
    cacheKey: undefined,
    scrollRoot: undefined,
  },
)
defineEmits<{ share: [] }>()
</script>
<template>
  <article class="blog-reading-article blog-article">
    <BlogArticleIntro
      :title="title"
      :summary="summary"
      :published-at="publishedAt"
      :updated-at="updatedAt"
      :shareable="shareable"
      @share="$emit('share')"
    />
    <BlogRenderer
      :article-title="title"
      :cache-key="cacheKey"
      :markdown="markdown"
      :resources="resources"
      :scroll-root="scrollRoot"
      show-toc
      toc-side="right"
    />
    <slot />
  </article>
</template>
