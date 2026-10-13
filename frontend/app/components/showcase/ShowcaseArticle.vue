<script setup lang="ts">
import { SHOWCASE_MARKDOWN } from '../../constants/designShowcase'
import BlogRenderer from '../blog/BlogRenderer.vue'
import type { TocLink } from 'comark/plugins/toc'
const emit = defineEmits<{ toc: [items: TocLink[]] }>()
</script>

<template>
  <article class="showcase-article" aria-label="Markdown 阅读样板">
    <p class="showcase-kicker">ARTICLE / 阅读样板</p>
    <p class="my-5 font-mono text-xs text-muted">2026.10.10 · 约 5 分钟阅读 · Design notes</p>
    <BlogRenderer :markdown="SHOWCASE_MARKDOWN" cache-key="showcase:article" @toc="emit('toc', $event)" />
  </article>
</template>
