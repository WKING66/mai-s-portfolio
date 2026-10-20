<script setup lang="ts">
// 原型只在开发环境开放，不读取或提交任何真实文章。
if (!import.meta.dev) {
  throw createError({ statusCode: 404, statusMessage: 'Not Found' })
}
definePageMeta({ immersiveWorkbench: true })
useHead({ title: '沉浸编辑 · 交互原型', meta: [{ name: 'robots', content: 'noindex, nofollow' }] })
</script>

<template>
  <ClientOnly>
    <LazyShowcaseImmersiveEditor />
    <template #fallback><p class="site-container py-12 text-muted">正在准备编辑原型…</p></template>
  </ClientOnly>
</template>
