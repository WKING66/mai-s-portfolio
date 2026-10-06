<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { DropdownMenuItem } from '@nuxt/ui'
import { useAuthState } from '../composables/useAuthState'
import { useSiteNavigation } from '../composables/useSiteNavigation'
import { LOGIN_MESSAGES } from '../constants/messages'
import { ACCOUNT_MENU_MESSAGES, SITE_NAVIGATION } from '../constants/navigation'

// 登录/注册页自行恢复会话，其余页面共用这一次查询；NuxtLink 跳转不重置权限状态。
const props = withDefaults(defineProps<{ restoreSession?: boolean }>(), { restoreSession: true })
const auth = useAuthState()
const { activeSection, mobileOpen, selectSection } = useSiteNavigation()
const theme = ref<'light' | 'dark'>('light')
const sessionError = ref('')
const mobileItems = computed<DropdownMenuItem[]>(() => SITE_NAVIGATION.map(item => ({
  label: item.label,
  to: item.href,
  active: activeSection.value === item.id,
  onSelect: () => selectSection(item.id),
})))

function applyTheme() {
  document.documentElement.dataset.theme = theme.value
  // Nuxt UI/Tailwind 的 dark: 变体与原有 data-theme 令牌同步，不另启第二套主题插件。
  document.documentElement.classList.toggle('dark', theme.value === 'dark')
}

onMounted(() => {
  if (props.restoreSession && !auth.ready.value) {
    void auth.refresh().catch(() => { sessionError.value = LOGIN_MESSAGES.sessionFailed })
  }
  let saved: string | null = null
  try { saved = localStorage.getItem('portfolio-theme') } catch { /* 浏览器禁用存储时仍可切换主题。 */ }
  theme.value = saved === 'dark' || saved === 'light'
    ? saved : window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  applyTheme()
})

function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  applyTheme()
  try { localStorage.setItem('portfolio-theme', theme.value) } catch { /* 主题持久化失败不影响本次交互。 */ }
}
</script>

<template>
  <header class="site-header sticky top-0 z-30 border-b border-line backdrop-blur-xl">
    <div class="mx-auto flex min-h-[70px] w-full max-w-[1200px] items-center justify-between gap-3 px-4 sm:gap-5 sm:px-5">
      <NuxtLink class="flex shrink-0 items-center gap-2.5 whitespace-nowrap font-extrabold tracking-tight no-underline"
        to="/#about" @click="selectSection('about')">
        <span class="brand-mark grid size-8 place-items-center rounded-full text-sm font-black" aria-hidden="true">M</span>
        <span class="hidden sm:inline">阿霾 · 个人作品集</span><span class="sm:hidden">阿霾</span>
      </NuxtLink>
      <nav class="flex items-center gap-2 sm:gap-4" aria-label="主导航">
        <NuxtLink v-for="item in SITE_NAVIGATION" :key="item.id" :to="item.href"
          :aria-current="activeSection === item.id ? 'location' : undefined"
          :data-nav-section="item.id"
          class="site-nav-link hidden whitespace-nowrap text-sm no-underline transition-colors md:inline"
          :class="activeSection === item.id ? 'text-accent font-semibold' : 'text-muted hover:text-ink'"
          @click="selectSection(item.id)">
          {{ item.label }}
        </NuxtLink>
        <AccountMenu />
        <span v-if="sessionError" role="status" class="sr-only">{{ sessionError }}</span>
        <UButton class="theme-toggle rounded-full" color="neutral" variant="outline" size="lg"
          :icon="theme === 'dark' ? 'i-lucide-sun' : 'i-lucide-moon'"
          :aria-label="theme === 'dark' ? '切换为浅色主题' : '切换为深色主题'" @click="toggleTheme" />
        <!-- Reka/Nuxt UI 管理焦点、方向键、Escape 和点击外部关闭，不自行实现菜单键盘协议。 -->
        <UDropdownMenu v-model:open="mobileOpen" :items="mobileItems" :content="{ align: 'end', sideOffset: 12 }"
          :ui="{ content: 'w-48 rounded-2xl p-1' }" class="md:hidden">
          <UButton class="rounded-full md:hidden" color="neutral" variant="outline" size="lg"
            icon="i-lucide-menu" :aria-label="ACCOUNT_MENU_MESSAGES.mobileNavigation" />
        </UDropdownMenu>
      </nav>
    </div>
  </header>
</template>

<style scoped>
.site-header { background: color-mix(in srgb, var(--site-bg) 86%, transparent); }
.brand-mark { background: linear-gradient(135deg, var(--site-accent), var(--site-accent-secondary)); color: #071018; }
.site-nav-link { position: relative; padding-block: 8px; }
.site-nav-link[aria-current="location"]::after { content: ""; position: absolute; height: 2px; inset-inline: 0; bottom: 0; border-radius: 2px; background: var(--site-accent); }
.theme-toggle { background: var(--site-surface); cursor: pointer; transition: transform .2s ease; }
.theme-toggle:hover { transform: translateY(-2px); }
@media (prefers-reduced-motion: reduce) { .theme-toggle { transition: none; } .theme-toggle:hover { transform: none; } }
</style>
