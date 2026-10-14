<script setup lang="ts">
const props = defineProps<{ status: number; title: string; description: string }>()
const isAccessRestricted = computed(() => props.status === 403 || props.status === 401)
const statusIcon = computed(() => {
  if (isAccessRestricted.value) {
    return 'i-lucide-lock-keyhole'
  }
  return props.status === 404 ? 'i-lucide-route-off' : 'i-lucide-unplug'
})
const statusLabel = computed(() => {
  if (isAccessRestricted.value) {
    return '访问受限'
  }
  return props.status === 404 ? '路径未找到' : '暂时无法连接'
})
</script>

<template>
  <main class="site-container site-page status-page" aria-labelledby="status-title">
    <SurfaceCard class="status-panel grid overflow-hidden lg:grid-cols-[.9fr_1.1fr]">
      <div
        class="status-art relative grid min-h-64 place-items-center border-b border-line px-8 py-12 lg:border-r lg:border-b-0"
        aria-hidden="true"
      >
        <svg class="absolute inset-0 size-full" viewBox="0 0 440 360" fill="none">
          <circle cx="220" cy="180" r="140" stroke="currentColor" stroke-opacity=".12" />
          <circle
            cx="220"
            cy="180"
            r="104"
            stroke="currentColor"
            stroke-opacity=".18"
            stroke-dasharray="4 8"
          />
          <path
            d="M32 262H100L148 214M292 146L340 98H408"
            stroke="currentColor"
            stroke-opacity=".3"
          />
          <circle cx="100" cy="262" r="4" fill="currentColor" />
          <circle cx="340" cy="98" r="4" fill="currentColor" />
        </svg>
        <div class="relative grid justify-items-center gap-5">
          <UIcon :name="statusIcon" class="size-8" />
          <span class="font-mono text-8xl font-semibold tracking-tight sm:text-9xl">
            {{ status }}
          </span>
          <span class="text-xs tracking-[.2em]">阿霾 · 个人作品集</span>
        </div>
      </div>
      <div class="flex flex-col justify-center gap-6 p-7 sm:p-12">
        <div class="flex items-center gap-2 text-sm text-muted">
          <span class="size-1.5 rounded-full bg-accent" />{{ statusLabel }}
        </div>
        <div class="space-y-4">
          <h1 id="status-title" class="text-3xl font-bold tracking-tight sm:text-4xl">
            {{ title }}
          </h1>
          <p class="max-w-md leading-8 text-muted">{{ description }}</p>
        </div>
        <div class="flex flex-wrap gap-3"><slot name="actions" /></div>
        <div class="border-t border-line pt-5 text-sm leading-7 text-muted"><slot /></div>
      </div>
    </SurfaceCard>
    <nav
      class="mt-6 flex flex-wrap items-center justify-center gap-x-6 gap-y-3 text-sm text-muted"
      aria-label="继续浏览"
    >
      <span>继续探索</span>
      <NuxtLink class="hover:text-accent" to="/blog">技术文章 →</NuxtLink>
      <NuxtLink class="hover:text-accent" to="/#projects">项目作品 →</NuxtLink>
    </nav>
  </main>
</template>

<style scoped>
.status-page {
  display: grid;
  align-content: center;
  min-height: min(720px, calc(100svh - 9rem));
}
.status-art {
  color: var(--site-accent);
  background: var(--site-bg-muted);
}
.status-panel {
  box-shadow: var(--site-shadow);
}
</style>
