<script setup lang="ts">
import type { NuxtError } from '#app'
import { useSiteTheme } from './composables/useSiteTheme'
import { safeHistoryPath } from './utils/historyPath'

const props = defineProps<{ error: NuxtError }>()
const { theme, initialize } = useSiteTheme()
const previous = ref<string | null>(null)
const status = computed(() => props.error.statusCode || 500)
const title = computed(() => status.value === 404 ? '这条路径，暂时没有内容' : status.value === 403 ? '你没有访问此内容的权限' : status.value === 401 ? '请先登录后再继续' : '页面暂时无法打开')
const description = computed(() => status.value === 404 ? '页面可能已移动，或链接地址有误。你可以返回上一页，或者从首页重新开始。' : status.value === 403 ? '当前访问不符合该内容的权限要求。登录其他账号并不一定能获得权限，请联系网站维护者确认。' : status.value === 401 ? '此内容需要确认账号身份。请通过网站现有登录入口继续访问。' : '服务暂时未能完成这次请求。你可以返回网站首页，稍后再试。')
useSeoMeta({ title: () => `${status.value} · ${title.value}`, robots: 'noindex,nofollow' })
useHead(() => {
  const painted = theme.value || (import.meta.client ? document.documentElement.dataset.theme : undefined)
  return { htmlAttrs: { 'data-theme': painted, class: painted === 'dark' ? 'dark' : undefined } }
})
onMounted(() => {
  initialize()
  previous.value = safeHistoryPath(window.history.state?.back, window.location.pathname)
})
const leave = (path: string) => clearError({ redirect: path })
</script>

<template>
  <UApp :toaster="{ position: 'top-center', ui: { viewport: 'top-22' } }">
    <SiteHeader />
    <StatusPanel :status="status" :title="title" :description="description">
      <template #actions>
        <UButton icon="i-lucide-house" size="lg" @click="leave('/')">返回首页</UButton>
        <UButton v-if="previous" color="neutral" variant="outline" size="lg" icon="i-lucide-arrow-left" @click="leave(previous)">返回上一页</UButton>
        <UButton v-if="status === 401" to="/login" color="neutral" variant="outline" size="lg">前往登录</UButton>
      </template>
      <p>状态 {{ status }} · 如果问题持续发生，请联系网站维护者。</p>
    </StatusPanel>
    <SiteFooter />
  </UApp>
</template>
