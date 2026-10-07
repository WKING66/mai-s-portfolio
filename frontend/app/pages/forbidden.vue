<script setup lang="ts">
import { AUTH_MESSAGES } from '../constants/auth'
useSeoMeta({ title: '无访问权限', robots: 'noindex,nofollow' })
</script>
<template>
  <main class="mx-auto grid min-h-screen max-w-xl content-center gap-6 px-6">
    <h1 class="text-3xl font-bold">无访问权限</h1>
    <UAlert role="alert" color="error" variant="soft" :title="AUTH_MESSAGES.forbidden" icon="i-lucide-shield-alert" />
    <NuxtLink to="/">返回首页</NuxtLink>
    <NuxtLink to="/login">切换账号</NuxtLink>
  </main>
</template>
