<script setup lang="ts">
import { AUTH_MESSAGES } from '../constants/auth'
import { useFeedback } from '../composables/useFeedback'
import { useAuthState } from '../composables/useAuthState'
const feedback = useFeedback()
const authState = useAuthState()
const isMounted = ref(false)
const isSessionConfirmed = computed(() => isMounted.value && authState.ready.value)
const isLoggedIn = computed(() => isSessionConfirmed.value && authState.state.value.loggedIn)
// 挂载并确认会话后才展示账号入口；提示拒绝访问，不赋予或推测任何新权限。
onMounted(() => {
  isMounted.value = true
  feedback.error(AUTH_MESSAGES.forbidden)
})
useSeoMeta({ title: '无访问权限', robots: 'noindex,nofollow' })
</script>
<template>
  <StatusPanel
    :status="403"
    title="此处需要相应的访问权限"
    :description="
      isLoggedIn
        ? '你已登录，但当前账号没有此页面所需的权限。内容仍受到保护；如需访问，请联系网站维护者确认授权。'
        : '此页面仅向获得授权的账号开放。你可以返回公开内容，或在确认账号后通过现有登录入口继续。'
    "
  >
    <template #actions>
      <UButton to="/" icon="i-lucide-house" size="lg">返回首页</UButton>
      <UButton
        v-if="isLoggedIn"
        to="/account"
        color="neutral"
        variant="outline"
        size="lg"
        icon="i-lucide-user-round"
        >查看当前账号</UButton
      >
      <UButton
        v-else-if="isSessionConfirmed"
        to="/login"
        color="neutral"
        variant="outline"
        size="lg"
        icon="i-lucide-log-in"
        >前往登录</UButton
      >
    </template>
    <p>
      {{
        isLoggedIn
          ? '更换账号或重新登录不会自动获得权限。'
          : '登录仅用于确认身份，实际访问仍由账号权限决定。'
      }}
    </p>
  </StatusPanel>
</template>
