<script setup lang="ts">
import type { NuxtError } from '#app'
import { useSiteTheme } from './composables/useSiteTheme'
import { safeHistoryPath } from './utils/site/historyPath'

const props = defineProps<{ error: NuxtError }>()
const { theme, initialize } = useSiteTheme()
const previousPath = ref<string | null>(null)
const statusCode = computed(() => props.error.statusCode || 500)
// 状态文案只解释错误，不重新决定鉴权或修改原始 Nuxt 错误。
const statusTitle = computed(() => {
  if (statusCode.value === 404) {
    return '这条路径，暂时没有内容'
  }
  if (statusCode.value === 403) {
    return '你没有访问此内容的权限'
  }
  if (statusCode.value === 401) {
    return '请先登录后再继续'
  }
  return '页面暂时无法打开'
})
const statusDescription = computed(() => {
  if (statusCode.value === 404) {
    return '页面可能已移动，或链接地址有误。你可以返回上一页，或者从首页重新开始。'
  }
  if (statusCode.value === 403) {
    return '当前访问不符合该内容的权限要求。登录其他账号并不一定能获得权限，请联系网站维护者确认。'
  }
  if (statusCode.value === 401) {
    return '此内容需要确认账号身份。请通过网站现有登录入口继续访问。'
  }
  return '服务暂时未能完成这次请求。你可以返回网站首页，稍后再试。'
})
useSeoMeta({
  title: () => `${statusCode.value} · ${statusTitle.value}`,
  robots: 'noindex,nofollow',
})
// 1. 错误页继续沿用首屏已绘制主题，不在水合时覆盖为未知状态。
useHead(() => {
  const paintedTheme =
    theme.value || (import.meta.client ? document.documentElement.dataset.theme : undefined)
  return {
    htmlAttrs: { 'data-theme': paintedTheme, class: paintedTheme === 'dark' ? 'dark' : undefined },
  }
})
// 2. 挂载后恢复主题，并只允许返回经过校验的站内历史路径。
onMounted(() => {
  initialize()
  previousPath.value = safeHistoryPath(window.history.state?.back, window.location.pathname)
})
const leaveErrorPage = (path: string) => clearError({ redirect: path })
</script>

<template>
  <UApp :toaster="{ position: 'top-center', ui: { viewport: 'top-22' } }">
    <SiteHeader />
    <UiStatusPanel :status="statusCode" :title="statusTitle" :description="statusDescription">
      <template #actions>
        <UButton icon="i-lucide-house" size="lg" @click="leaveErrorPage('/')">返回首页</UButton>
        <UButton
          v-if="previousPath"
          color="neutral"
          variant="outline"
          size="lg"
          icon="i-lucide-arrow-left"
          @click="leaveErrorPage(previousPath)"
          >返回上一页</UButton
        >
        <UButton v-if="statusCode === 401" to="/login" color="neutral" variant="outline" size="lg"
          >前往登录</UButton
        >
      </template>
      <p>状态 {{ statusCode }} · 如果问题持续发生，请联系网站维护者。</p>
    </UiStatusPanel>
    <SiteFooter />
  </UApp>
</template>
