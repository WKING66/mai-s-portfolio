<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from '#app'
import { AUTH_ACCESS, AUTH_MESSAGES } from '../constants/auth'
import { ADMIN_NAVIGATION, ADMIN_PATHS } from '../constants/admin'

defineProps<{ title: string; description: string }>()
const route = useRoute()
const activePage = computed(() => {
  if (route.path === ADMIN_PATHS.profile) return ADMIN_PATHS.profile
  if (route.path === ADMIN_PATHS.projects || route.path.startsWith(ADMIN_PATHS.projects + '/')) return ADMIN_PATHS.projects
  return null
})
</script>

<template>
  <div>
    <SiteHeader />
    <main class="mx-auto w-full max-w-[1200px] px-4 pt-8 pb-16 sm:px-5 sm:pt-12">
      <Auth :access="AUTH_ACCESS.OWNER">
        <!-- 统一在框架内挂载业务组件，权限撤销时销毁管理快照和未保存表单。 -->
        <nav class="mb-8 flex flex-wrap gap-2 border-b border-line pb-5" aria-label="作品集管理导航">
          <UButton v-for="item in ADMIN_NAVIGATION" :key="item.to" :to="item.to" :icon="item.icon" :color="activePage === item.to ? 'primary' : 'neutral'" :variant="activePage === item.to ? 'soft' : 'ghost'" :aria-current="activePage === item.to ? 'page' : undefined">{{ item.label }}</UButton>
        </nav>
        <header class="mb-8 flex flex-wrap items-start justify-between gap-5">
          <div class="max-w-2xl">
            <p class="eyebrow mb-3">作品集管理</p>
            <h1 class="text-3xl font-bold tracking-tight sm:text-4xl">{{ title }}</h1>
            <p class="mt-3 text-sm leading-7 text-muted sm:text-base">{{ description }}</p>
          </div>
          <div v-if="$slots.actions" class="flex flex-wrap gap-3"><slot name="actions" /></div>
        </header>
        <slot />
        <template #pending>
          <UAlert role="status" color="neutral" variant="subtle" :title="AUTH_MESSAGES.verifying" icon="i-lucide-loader-circle" />
        </template>
      </Auth>
    </main>
  </div>
</template>
