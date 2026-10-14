<script setup lang="ts">
import { AUTH_ACCESS } from '../../../constants/auth'
import { ADMIN_MESSAGES, ADMIN_PATHS } from '../../../constants/admin'

definePageMeta({ auth: AUTH_ACCESS.OWNER })
useSeoMeta({ title: ADMIN_MESSAGES.projectsTitle, robots: 'noindex,nofollow' })
</script>

<template>
  <AdminShell :title="ADMIN_MESSAGES.projectsTitle" :description="ADMIN_MESSAGES.projectsDescription">
    <template #actions>
      <UButton :to="ADMIN_PATHS.newProject" icon="i-lucide-plus">新增项目</UButton>
    </template>
    <AdminProjectList />
  </AdminShell>
</template>
