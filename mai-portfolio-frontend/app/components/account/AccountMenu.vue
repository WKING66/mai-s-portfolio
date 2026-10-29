<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { navigateTo, useRoute } from '#app'
import type { DropdownMenuItem } from '@nuxt/ui'
import { useAuthState } from '../../composables/useAuthState'
import { useAccountProfile } from '../../composables/useAccountProfile'
import { AUTH_ACCESS, AUTH_PATHS } from '../../constants/auth'
import { ACCOUNT_MENU_MESSAGES } from '../../constants/navigation'
import { useFeedback } from '../../composables/useFeedback'
import { LOGIN_MESSAGES } from '../../constants/messages'

const authState = useAuthState()
const accountProfile = useAccountProfile()
const feedback = useFeedback()
const route = useRoute()
const isMenuOpen = ref(false)
const isSigningOut = ref(false)
const isMounted = ref(false)
onMounted(() => {
  isMounted.value = true
})
const isLoggedIn = computed(() => authState.canAccess(AUTH_ACCESS.AUTHENTICATED))
const displayName = computed(
  () =>
    accountProfile.state.value?.nickname ||
    authState.state.value.username ||
    ACCOUNT_MENU_MESSAGES.account,
)
const avatarUrl = computed(() => accountProfile.state.value?.avatarUrl ?? undefined)

// 菜单和个人中心共享同一份当前账号资料，加载失败只显示用户名，不能把资料失败误当退出。
// 1. 账号世代变化先关闭菜单，再通过共享资料状态加载当前账号信息。
watch(
  () => [authState.ready.value, authState.state.value.loggedIn, authState.state.value.username],
  () => {
    isMenuOpen.value = false
    if (import.meta.client && isLoggedIn.value) {
      // 共享资料能力只提示当前世代错误，菜单不再重复维护或渲染错误状态。
      void accountProfile.load().catch(() => {
        /* 共享资料能力已弹出错误；个人中心保留重试。 */
      })
    }
  },
  { immediate: true },
)
watch(
  () => route.fullPath,
  () => {
    isMenuOpen.value = false
  },
)

// 2. 注销操作互斥；成功清理会话后关闭菜单并返回首页，失败保留错误反馈。
async function signOut() {
  if (isSigningOut.value || !isLoggedIn.value) {
    return
  }
  isSigningOut.value = true
  try {
    await authState.signOut()
    isMenuOpen.value = false
    await navigateTo('/')
    feedback.success(LOGIN_MESSAGES.logoutSuccess)
  } catch (cause) {
    feedback.error(cause, ACCOUNT_MENU_MESSAGES.logoutFailed)
  } finally {
    isSigningOut.value = false
  }
}

// 3. 菜单展示统一读取账号能力，不在组件内解析角色或替代服务端授权。
const menuItems = computed<DropdownMenuItem[][]>(() => {
  const actions: DropdownMenuItem[] = [
    {
      label: ACCOUNT_MENU_MESSAGES.personalCenter,
      icon: 'i-lucide-user-round',
      to: AUTH_PATHS.account,
      active: route.path === AUTH_PATHS.account,
    },
  ]
  if (authState.canAccess(AUTH_ACCESS.OWNER)) {
    actions.unshift({
      label: ACCOUNT_MENU_MESSAGES.manageProjects,
      icon: 'i-lucide-folder-kanban',
      to: AUTH_PATHS.manageProjects,
      active: route.path.startsWith(AUTH_PATHS.manageProjects),
    })
  }
  return [
    [
      {
        label: displayName.value,
        description: authState.state.value.username ?? undefined,
        type: 'label',
        avatar: { src: avatarUrl.value, alt: displayName.value },
      },
    ],
    actions,
    [
      {
        label: isSigningOut.value ? ACCOUNT_MENU_MESSAGES.loggingOut : ACCOUNT_MENU_MESSAGES.logout,
        icon: 'i-lucide-log-out',
        color: 'error',
        disabled: isSigningOut.value,
        onSelect: () => {
          void signOut()
        },
      },
    ],
  ]
})
</script>

<template>
  <div class="relative w-10 shrink-0 sm:w-36">
    <!-- 首次恢复时保持稳定的账户按钮，避免先闪现登录/管理入口后又替换。 -->
    <UButton
      v-if="!isMounted || !authState.ready.value"
      data-account-entry
      color="neutral"
      variant="outline"
      size="lg"
      class="account-entry h-10 w-full rounded-lg"
      disabled
      icon="i-lucide-user-round"
      :aria-label="ACCOUNT_MENU_MESSAGES.verifying"
    >
      <USkeleton class="hidden h-3 w-16 sm:inline-block" />
    </UButton>
    <UDropdownMenu
      v-else-if="isLoggedIn"
      v-model:open="isMenuOpen"
      :modal="false"
      :items="menuItems"
      size="lg"
      :content="{ align: 'end', sideOffset: 12, collisionPadding: 12 }"
      :ui="{ content: 'z-50 w-60 rounded-lg p-1 shadow-sm', item: 'rounded-lg', label: 'py-3' }"
    >
      <UButton
        data-account-entry
        color="neutral"
        variant="outline"
        size="lg"
        class="account-entry h-10 w-full rounded-lg"
        :disabled="isSigningOut"
        :loading="isSigningOut"
        :ui="{ trailingIcon: 'hidden sm:block' }"
        :aria-label="displayName + '，' + ACCOUNT_MENU_MESSAGES.account"
        trailing-icon="i-lucide-chevron-down"
      >
        <template #leading
          ><UAvatar :src="avatarUrl" :alt="displayName" size="2xs" class="account-avatar"
        /></template>
        <span class="hidden max-w-20 truncate sm:inline">{{ displayName }}</span>
      </UButton>
    </UDropdownMenu>
    <UButton
      v-else
      data-account-entry
      :to="AUTH_PATHS.login"
      color="neutral"
      variant="outline"
      icon="i-lucide-user-round"
      size="lg"
      class="account-entry h-10 w-full justify-center rounded-lg"
      :aria-label="ACCOUNT_MENU_MESSAGES.login"
    >
      <span class="hidden sm:inline">{{ ACCOUNT_MENU_MESSAGES.login }}</span>
    </UButton>
  </div>
</template>

<style scoped>
.account-entry {
  background: color-mix(in srgb, var(--site-accent) 7%, var(--site-surface));
}
.account-entry:hover {
  background: color-mix(in srgb, var(--site-accent) 13%, var(--site-surface));
}
.account-avatar {
  background: var(--site-accent);
  color: var(--site-inverted);
}
</style>
