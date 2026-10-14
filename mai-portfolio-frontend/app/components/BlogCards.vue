<script setup lang="ts">
import type { BlogCard } from '../api/blogs'
defineProps<{ items: BlogCard[] }>()
</script>
<template>
  <div class="grid gap-5 md:grid-cols-2">
    <NuxtLink
      v-for="article in items"
      :key="article.id"
      :to="'/blog/' + article.slug"
      class="min-w-0 rounded-xl border border-line bg-default p-5 transition-colors hover:border-accent sm:p-6"
    >
      <p class="font-mono text-xs text-muted">{{ article.publishedAt?.slice(0, 10) }}</p>
      <h3 class="mt-2 break-words text-xl font-semibold">{{ article.title }}</h3>
      <p class="mt-3 break-words leading-7 text-muted">{{ article.summary }}</p>
    </NuxtLink>
  </div>
</template>
