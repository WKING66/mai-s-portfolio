<script setup lang="ts">
import { useAuthState } from '../composables/useAuthState'
import { PROJECT_MESSAGES } from '../constants/projects'
import { AUTH_ACCESS } from '../constants/auth'
import { PROFILE_PATHS } from '../constants/profile'
const auth = useAuthState()
const error = ref('')
const pending = ref(false)
async function signOut() {
  if (pending.value) return
  pending.value = true
  error.value = ''
  try {
    await auth.signOut();
    await navigateTo('/login')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : PROJECT_MESSAGES.logoutFailed
  } finally {
    pending.value = false
  }
}
</script>
<template>
  <Auth :access="AUTH_ACCESS.OWNER">
    <header class="mb-8 flex flex-wrap items-center gap-5 border-b border-line py-5">
      <NuxtLink to="/admin/projects" class="font-bold text-accent">项目管理</NuxtLink>
      <NuxtLink :to="PROFILE_PATHS.page" class="text-accent">个人资料</NuxtLink>
      <NuxtLink to="/">公开首页</NuxtLink>
      <NuxtLink to="/projects">项目栏目</NuxtLink>
      <span class="ml-auto">{{ auth.state.value.username }}</span>
      <button type="button" :disabled="pending" class="rounded-lg border border-line px-4 py-2 disabled:opacity-50" @click="signOut">{{ pending ? '正在注销…' : '注销' }}</button>
      <p v-if="error" role="alert">{{ error }}</p>
    </header>
  </Auth>
</template>
