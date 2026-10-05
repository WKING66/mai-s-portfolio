<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useAuthState } from '../composables/useAuthState'
const auth = useAuthState()

const navigation = [
  { href: '/#about', label: '关于我' },
  { href: '/#stack', label: '技术栈' },
  { href: '/projects', label: '项目' },
  { href: '/#blog', label: '博客' },
  { href: '/#contact', label: '联系' },
]

const theme = ref<'light' | 'dark'>('light')

onMounted(() => {
  void auth.refresh().catch(() => {})
  const saved = localStorage.getItem('portfolio-theme')
  theme.value = saved === 'dark' || saved === 'light'
    ? saved
    : window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  document.documentElement.dataset.theme = theme.value
})

function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  document.documentElement.dataset.theme = theme.value
  localStorage.setItem('portfolio-theme', theme.value)
}
</script>

<template>
  <header class="site-header sticky top-0 z-30 border-b border-line backdrop-blur-xl">
    <div class="mx-auto flex min-h-[70px] w-full max-w-[1200px] items-center justify-between gap-5 px-4 sm:px-5">
      <a class="flex shrink-0 items-center gap-2.5 whitespace-nowrap font-extrabold tracking-tight no-underline" href="/#about">
        <span class="brand-mark grid size-8 place-items-center rounded-full text-sm font-black" aria-hidden="true">M</span>
        阿霾 · 个人作品集
      </a>
      <nav class="flex items-center gap-2 sm:gap-5" aria-label="主导航">
        <a v-for="item in navigation" :key="item.href" :href="item.href"
          class="hidden whitespace-nowrap text-sm text-muted no-underline transition-colors hover:text-ink md:inline">
          {{ item.label }}
        </a>
        <NuxtLink v-if="auth.isOwner.value" to="/admin/projects" class="text-sm text-accent">管理项目</NuxtLink>
        <button class="theme-toggle grid size-10 shrink-0 place-items-center rounded-full border border-line"
          type="button" :aria-label="theme === 'dark' ? '切换为浅色主题' : '切换为深色主题'" @click="toggleTheme">
          <span aria-hidden="true">◐</span>
        </button>
        <details class="mobile-menu relative md:hidden">
          <summary class="cursor-pointer list-none rounded-full border border-line px-3 py-2 text-sm">菜单</summary>
          <div class="menu-panel absolute top-[calc(100%+12px)] right-0 grid min-w-[190px] rounded-2xl border border-line p-2 shadow-xl">
            <a v-for="item in navigation" :key="item.href" :href="item.href" class="rounded-lg px-3 py-2 text-sm no-underline hover:text-accent">{{ item.label }}</a>
          </div>
        </details>
      </nav>
    </div>
  </header>
</template>

<style scoped>
.site-header { background: color-mix(in srgb, var(--site-bg) 78%, transparent); }
.brand-mark { background: linear-gradient(135deg, var(--site-accent), var(--site-accent-secondary)); color: #071018; }
.theme-toggle { background: var(--site-surface); cursor: pointer; transition: transform .2s ease; }
.theme-toggle:hover { transform: translateY(-2px); }
.menu-panel { background: var(--site-surface-strong); }
.mobile-menu summary::-webkit-details-marker { display: none; }
@media (prefers-reduced-motion: reduce) { .theme-toggle { transition: none; } .theme-toggle:hover { transform: none; } }
</style>
