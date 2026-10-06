<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { navigateTo, useRoute } from '#app'
import type { DropdownMenuItem } from '@nuxt/ui'
import { useAuthState } from '../composables/useAuthState'
import { useAccountProfile } from '../composables/useAccountProfile'
import { AUTH_ACCESS, AUTH_PATHS } from '../constants/auth'
import { ACCOUNT_MENU_MESSAGES } from '../constants/navigation'

const auth = useAuthState()
const profile = useAccountProfile()
const route = useRoute()
const open = ref(false)
const pending = ref(false)
const logoutError = ref('')
const error = computed(() => logoutError.value || profile.error.value)
const loggedIn = computed(() => auth.canAccess(AUTH_ACCESS.AUTHENTICATED))
const displayName = computed(() => profile.state.value?.nickname || auth.state.value.username || ACCOUNT_MENU_MESSAGES.account)
const avatarUrl = computed(() => profile.state.value?.avatarUrl
  ? profile.state.value.avatarUrl + (profile.state.value.avatarUrl.includes('?') ? '&' : '?')
    + 'rev=' + profile.revision.value : undefined)

// 菜单和个人中心共享同一份当前账号资料，加载失败只显示用户名，不能把资料失败误当退出。
watch(() => [auth.ready.value, auth.state.value.loggedIn, auth.state.value.username], () => {
  open.value = false
  logoutError.value = ''
  if (import.meta.client && loggedIn.value) {
    // load 已在共享状态记录当前世代错误，旧失败不能覆盖新账号菜单。
    void profile.load().catch(() => { /* 菜单通过 profile.error 展示失败，个人中心提供重试。 */ })
  }
}, { immediate: true })
watch(() => route.fullPath, () => { open.value = false })

async function signOut() {
  if (pending.value || !loggedIn.value) return
  pending.value = true
  logoutError.value = ''
  try {
    await auth.signOut()
    open.value = false
    await navigateTo('/')
  } catch (cause) {
    logoutError.value = cause instanceof Error ? cause.message : ACCOUNT_MENU_MESSAGES.logoutFailed
  } finally {
    pending.value = false
  }
}

const items = computed<DropdownMenuItem[][]>(() => {
  const actions: DropdownMenuItem[] = [
    { label: ACCOUNT_MENU_MESSAGES.personalCenter, icon: 'i-lucide-user-round', to: AUTH_PATHS.account,
      active: route.path === AUTH_PATHS.account },
  ]
  if (auth.canAccess(AUTH_ACCESS.OWNER)) {
    actions.unshift({ label: ACCOUNT_MENU_MESSAGES.manageProjects, icon: 'i-lucide-folder-kanban',
      to: AUTH_PATHS.manageProjects, active: route.path.startsWith(AUTH_PATHS.manageProjects) })
  }
  return [
    [{ label: displayName.value, description: auth.state.value.username ?? undefined, type: 'label',
      avatar: { src: avatarUrl.value, alt: displayName.value } }],
    actions,
    [{ label: pending.value ? ACCOUNT_MENU_MESSAGES.loggingOut : ACCOUNT_MENU_MESSAGES.logout,
      icon: 'i-lucide-log-out', color: 'error', disabled: pending.value,
      onSelect: () => { void signOut() } }],
  ]
})
</script>

<template>
  <div class="relative w-10 shrink-0 sm:w-36">
    <!-- 首次恢复时保持稳定的账户按钮，避免先闪现登录/管理入口后又替换。 -->
    <UButton v-if="!auth.ready.value" data-account-entry color="neutral" variant="outline"
      size="lg" class="account-entry h-10 w-full rounded-full" disabled icon="i-lucide-user-round"
      :aria-label="ACCOUNT_MENU_MESSAGES.verifying">
      <USkeleton class="hidden h-3 w-16 sm:inline-block" />
    </UButton>
    <UDropdownMenu v-else-if="loggedIn" v-model:open="open" :modal="false" :items="items" size="lg"
      :content="{ align: 'end', sideOffset: 12, collisionPadding: 12 }"
      :ui="{ content: 'z-50 w-60 rounded-2xl p-1 shadow-xl', item: 'rounded-lg', label: 'py-3' }">
      <UButton data-account-entry color="neutral" variant="outline" size="lg"
        class="account-entry h-10 w-full rounded-full" :disabled="pending" :loading="pending"
        :aria-label="displayName + '，' + ACCOUNT_MENU_MESSAGES.account" trailing-icon="i-lucide-chevron-down">
        <template #leading><UAvatar :src="avatarUrl" :alt="displayName" size="2xs" class="account-avatar" /></template>
        <span class="hidden max-w-20 truncate sm:inline">{{ displayName }}</span>
      </UButton>
      <template #content-bottom>
        <p v-if="error" role="alert" class="max-w-60 border-t border-line px-3 py-2 text-xs leading-5 text-red-500">{{ error }}</p>
      </template>
    </UDropdownMenu>
    <UButton v-else data-account-entry :to="AUTH_PATHS.login" color="neutral" variant="outline"
      icon="i-lucide-user-round" size="lg" class="account-entry h-10 w-full justify-center rounded-full" :aria-label="ACCOUNT_MENU_MESSAGES.login">
      <span class="hidden sm:inline">{{ ACCOUNT_MENU_MESSAGES.login }}</span>
    </UButton>
    <p v-if="error && !open" role="status" class="sr-only">{{ error }}</p>
  </div>
</template>

<style scoped>
.account-entry { background: color-mix(in srgb, var(--site-accent) 7%, var(--site-surface)); }
.account-entry:hover { background: color-mix(in srgb, var(--site-accent) 13%, var(--site-surface)); }
.account-avatar { background: linear-gradient(135deg, var(--site-accent), var(--site-accent-secondary)); color: var(--site-bg); }
</style>
