<script setup lang="ts">
import { AUTH_ACCESS } from '../constants/auth'

definePageMeta({ auth: AUTH_ACCESS.AUTHENTICATED })
useSeoMeta({ title: '个人中心 · 阿霾作品集', robots: 'noindex,nofollow' })
</script>

<template>
  <SiteHeader />
  <main class="mx-auto min-h-[calc(100svh-71px)] max-w-5xl px-5 py-8 sm:px-8 sm:py-12">
    <Auth>
      <AccountSettings />
      <template #pending><p role="status" class="text-muted">正在确认账号状态…</p></template>
    </Auth>
  </main>
</template>
