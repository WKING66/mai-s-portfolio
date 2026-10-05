<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useAuthState } from '../composables/useAuthState'
// 登录页自行恢复会话，避免加入公共导航后重复发送会话查询。
const props = withDefaults(defineProps<{ restoreSession?: boolean }>(), { restoreSession: true })
const auth = useAuthState()

const navigation = [
  { href: '/#about', label: '关于我' },
  { href: '/#stack', label: '技术栈' },
  { href: '/#projects', label: '项目' },
  { href: '/#blog', label: '博客' },
  { href: '/#contact', label: '联系' },
]

const theme = ref<'light' | 'dark'>('light')

onMounted(() => {
  if (props.restoreSession) void auth.refresh().catch(() => {})
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
        <span class="hidden sm:inline">阿霾 · 个人作品集</span><span class="sm:hidden">阿霾</span>
      </a>
      <nav class="flex items-center gap-2 sm:gap-5" aria-label="主导航">
        <a v-for="item in navigation" :key="item.href" :href="item.href"
          class="hidden whitespace-nowrap text-sm text-muted no-underline transition-colors hover:text-ink md:inline">
          {{ item.label }}
        </a>
        <NuxtLink v-if="auth.isOwner.value" to="/admin/projects" class="hidden whitespace-nowrap text-sm text-accent md:inline">管理项目</NuxtLink>
        <NuxtLink to="/login" data-account-entry class="account-entry inline-flex items-center gap-2 rounded-full border border-line px-3 py-2 text-sm font-semibold text-accent no-underline sm:px-4">
          <svg class="size-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><circle cx="12" cy="8" r="3.5" /><path d="M5 21v-2a7 7 0 0 1 14 0v2" /></svg>
          {{ auth.ready.value && auth.state.value.loggedIn ? '账户' : '登录' }}
        </NuxtLink>
        <button class="theme-toggle grid size-10 shrink-0 place-items-center rounded-full border border-line"
          type="button" :aria-label="theme === 'dark' ? '切换为浅色主题' : '切换为深色主题'" @click="toggleTheme">
          <span aria-hidden="true">◐</span>
        </button>
        <details class="mobile-menu relative md:hidden">
          <summary class="cursor-pointer list-none rounded-full border border-line px-3 py-2 text-sm">菜单</summary>
          <div class="menu-panel absolute top-[calc(100%+12px)] right-0 grid min-w-[190px] rounded-2xl border border-line p-2 shadow-xl">
            <a v-for="item in navigation" :key="item.href" :href="item.href" class="rounded-lg px-3 py-2 text-sm no-underline hover:text-accent">{{ item.label }}</a>
            <NuxtLink v-if="auth.isOwner.value" to="/admin/projects" class="rounded-lg px-3 py-2 text-sm text-accent">管理项目</NuxtLink>
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
.account-entry { background: color-mix(in srgb, var(--site-accent) 7%, transparent); }
.account-entry:hover { border-color: var(--site-accent); background: color-mix(in srgb, var(--site-accent) 13%, transparent); }
.menu-panel { background: var(--site-surface-strong); }
.mobile-menu summary::-webkit-details-marker { display: none; }
@media (prefers-reduced-motion: reduce) { .theme-toggle { transition: none; } .theme-toggle:hover { transform: none; } }
</style>
