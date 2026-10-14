<script setup lang="ts">
import { useSiteTheme } from './composables/useSiteTheme'

const route = useRoute()
const { theme, initialize } = useSiteTheme()
const authPage = computed(() => ['/login', '/register', '/admin/login'].includes(route.path))
// html 已预留 stable gutter 时不重复补偿；保持 Reka 的滚动锁、焦点陷阱与恢复逻辑。
const scrollBody = {
  padding: import.meta.client && getComputedStyle(document.documentElement).scrollbarGutter.includes('stable') ? 0 : true,
  margin: 0,
}
// 首次水合时保留 head 脚本已绘制的主题，不能用 SSR 的未知状态覆盖它。
useHead(() => {
  const painted = theme.value || (import.meta.client ? document.documentElement.dataset.theme : undefined)
  return { htmlAttrs: { 'data-theme': painted, class: painted === 'dark' ? 'dark' : undefined } }
})
onMounted(initialize)
</script>

<template>
  <UApp :toaster="{ position: 'top-center', ui: { viewport: 'top-22' } }" :scroll-body="scrollBody">
    <!-- 普通选择器统一不锁背景滚动，避免重复补偿滚动条造成整页抖动。 -->
    <UTheme :props="{ select: { content: { bodyLock: false, position: 'popper' } } }">
      <!-- 页头不随页面卸载，账户状态、主题与导航尺寸不会在路由切换时重置。 -->
      <SiteHeader :restore-session="!authPage" />
      <NuxtLoadingIndicator :height="2" color="var(--site-accent)" />
      <NuxtPage />
      <SiteFooter />
    </UTheme>
  </UApp>
</template>
