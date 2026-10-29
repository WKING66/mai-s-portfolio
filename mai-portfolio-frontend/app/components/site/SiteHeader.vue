<script setup lang="ts">
import { computed, onMounted } from 'vue'
import type { DropdownMenuItem } from '@nuxt/ui'
import { useAuthState } from '../../composables/useAuthState'
import { useSiteNavigation } from '../../composables/useSiteNavigation'
import { useSiteTheme } from '../../composables/useSiteTheme'
import { LOGIN_MESSAGES } from '../../constants/messages'
import { ACCOUNT_MENU_MESSAGES, SITE_NAVIGATION } from '../../constants/navigation'
import { useFeedback } from '../../composables/useFeedback'
const headerElement = useTemplateRef<HTMLElement>('header')
let headerObserver: ResizeObserver | undefined
// 1. 挂载后测量实际导航高度，供锚点和吸顶目录复用。
onMounted(() => {
  // 内容换行、字体缩放和浏览器缩放均使用实测高度；不按设备型号分支。
  headerObserver = new ResizeObserver((entries) => {
    const height = entries[0]?.borderBoxSize[0]?.blockSize
    if (height && headerElement.value?.isConnected) {
      document.documentElement.style.setProperty('--site-header-height', height + 'px')
    }
  })
  if (headerElement.value) {
    headerObserver.observe(headerElement.value)
  }
})
// 2. 卸载时释放尺寸观察器及布局变量，避免下次挂载沿用旧高度。
onBeforeUnmount(() => {
  headerObserver?.disconnect()
  document.documentElement.style.removeProperty('--site-header-height')
})

// 登录/注册页自行恢复会话，其余页面共用这一次查询；NuxtLink 跳转不重置权限状态。
const props = withDefaults(defineProps<{ restoreSession?: boolean }>(), { restoreSession: true })
const authState = useAuthState()
const feedback = useFeedback()
const { activeSection, mobileOpen, selectSection } = useSiteNavigation()
const { theme, toggleTheme } = useSiteTheme()
const mobileItems = computed<DropdownMenuItem[]>(() =>
  SITE_NAVIGATION.map((item) => ({
    label: item.label,
    to: item.href,
    active: activeSection.value === item.id,
    onSelect: () => selectSection(item.id),
  })),
)

// 3. 普通页面共用会话恢复；登录页自行初始化，避免重复请求。
onMounted(() => {
  if (props.restoreSession && !authState.ready.value) {
    void authState
      .ensureSession()
      .catch((cause) => feedback.error(cause, LOGIN_MESSAGES.sessionFailed))
  }
})
</script>

<template>
  <header ref="header" class="site-header sticky top-0 z-30 border-b border-line">
    <div
      class="site-container flex min-h-18 flex-wrap items-center justify-between gap-3 py-3 sm:gap-5"
    >
      <NuxtLink
        class="flex shrink-0 items-center gap-2.5 whitespace-nowrap font-extrabold tracking-tight no-underline"
        to="/#about"
        @click="selectSection('about')"
      >
        <span
          class="brand-mark grid size-8 place-items-center rounded-lg text-sm font-semibold"
          aria-hidden="true"
        >
          M
        </span>
        <span class="hidden sm:inline">阿霾 · 个人作品集</span><span class="sm:hidden">阿霾</span>
      </NuxtLink>
      <nav class="flex min-w-0 flex-wrap items-center gap-2 sm:gap-4" aria-label="主导航">
        <NuxtLink
          v-for="item in SITE_NAVIGATION"
          :key="item.id"
          :to="item.href"
          :aria-current="activeSection === item.id ? 'location' : undefined"
          :data-nav-section="item.id"
          class="site-nav-link hidden whitespace-nowrap text-sm no-underline transition-colors md:inline"
          :class="
            activeSection === item.id ? 'text-accent font-semibold' : 'text-muted hover:text-ink'
          "
          @click="selectSection(item.id)"
        >
          {{ item.label }}
        </NuxtLink>
        <AccountMenu />
        <UButton
          class="theme-toggle"
          color="neutral"
          variant="outline"
          size="lg"
          :icon="theme === 'dark' ? 'i-lucide-sun' : 'i-lucide-moon'"
          :aria-label="theme === 'dark' ? '切换为浅色主题' : '切换为深色主题'"
          @click="toggleTheme"
        />
        <!-- Reka/Nuxt UI 管理焦点、方向键、Escape 和点击外部关闭，不自行实现菜单键盘协议。 -->
        <UDropdownMenu
          v-model:open="mobileOpen"
          :modal="false"
          :items="mobileItems"
          :content="{ align: 'end', sideOffset: 12 }"
          :ui="{ content: 'w-48 rounded-lg p-1' }"
          class="md:hidden"
        >
          <UButton
            class="rounded-lg md:hidden"
            color="neutral"
            variant="outline"
            size="lg"
            icon="i-lucide-menu"
            :aria-label="ACCOUNT_MENU_MESSAGES.mobileNavigation"
          />
        </UDropdownMenu>
      </nav>
    </div>
  </header>
</template>

<style scoped>
.site-header {
  background: var(--site-bg);
}
.brand-mark {
  background: var(--site-accent);
  color: var(--site-inverted);
}
.site-nav-link {
  position: relative;
  padding-block: 8px;
}
.site-nav-link[aria-current='location']::after {
  content: '';
  position: absolute;
  height: 2px;
  inset-inline: 0;
  bottom: 0;
  border-radius: 2px;
  background: var(--site-accent);
}
.theme-toggle {
  background: var(--site-surface);
  cursor: pointer;
}
</style>
