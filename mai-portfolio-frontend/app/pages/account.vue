<script setup lang="ts">
import { AUTH_ACCESS } from '../constants/auth'

definePageMeta({ auth: AUTH_ACCESS.AUTHENTICATED })
useSeoMeta({ title: '个人中心 · 阿霾作品集', robots: 'noindex,nofollow' })
</script>

<template>
  <main class="site-container site-page">
    <Auth>
      <AccountSettings />
      <template #pending><div class="min-h-[600px]" role="status" aria-label="正在确认账号状态"><USkeleton class="mb-8 h-20 w-64" /><USkeleton class="h-[480px] w-full max-w-3xl rounded-xl" /></div></template>
    </Auth>
  </main>
</template>
