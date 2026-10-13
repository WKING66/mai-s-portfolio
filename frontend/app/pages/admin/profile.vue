<script setup lang="ts">
import { AUTH_ACCESS } from '../../constants/auth'
import { ADMIN_MESSAGES } from '../../constants/admin'

definePageMeta({ auth: AUTH_ACCESS.OWNER })
useSeoMeta({ title: ADMIN_MESSAGES.publicProfileTitle, robots: 'noindex,nofollow' })
</script>

<template>
  <AdminShell :title="ADMIN_MESSAGES.publicProfileTitle" :description="ADMIN_MESSAGES.publicProfileDescription">
    <template #actions>
      <UButton to="/" color="neutral" variant="outline" icon="i-lucide-external-link">查看公开首页</UButton>
    </template>
    <ProfileEditor />
  </AdminShell>
</template>
