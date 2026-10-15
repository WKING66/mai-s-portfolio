<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from '#app'
import { AUTH_ACCESS, AUTH_MESSAGES } from '../constants/auth'
import { ADMIN_NAVIGATION, ADMIN_PATHS } from '../constants/admin'

defineProps<{ title: string; description: string; wide?: boolean }>()
const route = useRoute()
const activePage = computed(() => {
  if (route.path.startsWith(ADMIN_PATHS.blogs)) {
    return ADMIN_PATHS.blogs
  }
  if (route.path === ADMIN_PATHS.profile) {
    return ADMIN_PATHS.profile
  }
  if (route.path === ADMIN_PATHS.projects || route.path.startsWith(ADMIN_PATHS.projects + '/')) {
    return ADMIN_PATHS.projects
  }
  return null
})
</script>

<template>
  <div>
    <main class="site-container site-page" :class="{ 'admin-wide': wide }">
      <Auth :access="AUTH_ACCESS.OWNER">
        <!-- 统一在框架内挂载业务组件，权限撤销时销毁管理快照和未保存表单。 -->
        <div class="admin-workspace grid items-start gap-8">
          <nav
            class="admin-navigation flex flex-wrap gap-2 border-b border-line pb-5"
            aria-label="作品集管理导航"
          >
            <UButton
              v-for="item in ADMIN_NAVIGATION"
              :key="item.to"
              :to="item.to"
              :icon="item.icon"
              :color="activePage === item.to ? 'primary' : 'neutral'"
              :variant="activePage === item.to ? 'soft' : 'ghost'"
              :aria-current="activePage === item.to ? 'page' : undefined"
            >
              {{ item.label }}
            </UButton>
          </nav>
          <div class="min-w-0">
            <header class="mb-8 flex flex-wrap items-start justify-between gap-5">
              <div class="max-w-2xl">
                <p class="eyebrow mb-3">作品集管理</p>
                <h1 class="site-page-title">{{ title }}</h1>
                <p class="mt-3 text-sm leading-7 text-muted sm:text-base">{{ description }}</p>
              </div>
              <div v-if="$slots.actions" class="flex flex-wrap gap-3"><slot name="actions" /></div>
            </header>
            <slot />
          </div>
        </div>
        <template #pending>
          <div role="status" :aria-label="AUTH_MESSAGES.verifying" class="grid min-h-[600px] gap-6">
            <USkeleton class="h-12 w-64" />
            <USkeleton class="h-[480px] w-full rounded-xl" />
          </div>
        </template>
      </Auth>
    </main>
  </div>
</template>

<style scoped>
@media (min-width: 1024px) {
  .admin-workspace {
    grid-template-columns: 11rem minmax(0, 1fr);
  }
  .admin-navigation {
    position: sticky;
    top: calc(var(--site-header-height) + 24px);
    flex-direction: column;
    border-bottom: 0;
    border-right: 1px solid var(--site-line);
    padding: 0 1.5rem 0 0;
  }
}
.admin-wide {
  max-width: 100rem;
  padding-top: 1.5rem;
}
.admin-wide .admin-workspace {
  gap: 1.25rem;
}
.admin-wide header {
  margin-bottom: 1.5rem;
}
.admin-wide header .eyebrow {
  display: none;
}
@media (min-width: 1024px) {
  .admin-wide .admin-workspace {
    grid-template-columns: minmax(0, 1fr);
  }
  .admin-wide .admin-navigation {
    position: static;
    flex-direction: row;
    border-right: 0;
    border-bottom: 1px solid var(--site-line);
    padding: 0 0 1rem;
  }
}
</style>
