<script setup lang="ts">
import { AUTH_ACCESS } from '../../constants/auth'
import { ADMIN_MESSAGES } from '../../constants/admin'

definePageMeta({ auth: AUTH_ACCESS.OWNER })
useSeoMeta({ title: ADMIN_MESSAGES.projectsTitle, robots: 'noindex,nofollow' })
</script>

<template>
  <AdminShell
    :title="ADMIN_MESSAGES.projectsTitle"
    :description="ADMIN_MESSAGES.projectsDescription"
  >
    <AdminProjectList />
    <NuxtPage />
  </AdminShell>
</template>
