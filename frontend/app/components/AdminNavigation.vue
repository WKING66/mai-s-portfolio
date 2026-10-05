<script setup lang="ts">
import { useAuthState } from '../composables/useAuthState'
import { PROJECT_MESSAGES } from '../constants/projects'
const auth = useAuthState()
const error = ref('')
async function signOut() {
  try { await auth.signOut(); await navigateTo('/login') }
  catch { error.value = PROJECT_MESSAGES.logoutFailed }
}
</script>
<template>
  <header v-if="auth.isOwner.value" class="mb-8 flex flex-wrap items-center gap-5 border-b border-line py-5">
    <NuxtLink to="/admin/projects" class="font-bold text-accent">项目管理</NuxtLink>
    <NuxtLink to="/">公开首页</NuxtLink>
    <NuxtLink to="/projects">项目栏目</NuxtLink>
    <span class="ml-auto">{{ auth.state.value.username }}</span>
    <button type="button" class="rounded-lg border border-line px-4 py-2" @click="signOut">注销</button>
    <p v-if="error" role="alert">{{ error }}</p>
  </header>
</template>
