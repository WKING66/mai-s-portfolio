<script setup lang="ts">
import { AUTH_ACCESS } from '../../../constants/auth'
import { ADMIN_MESSAGES, ADMIN_PATHS } from '../../../constants/admin'

definePageMeta({ auth: AUTH_ACCESS.OWNER })
useSeoMeta({ title: ADMIN_MESSAGES.newProjectTitle, robots: 'noindex,nofollow' })
</script>

<template>
  <AdminShell :title="ADMIN_MESSAGES.newProjectTitle" :description="ADMIN_MESSAGES.projectEditorDescription">
    <template #actions><UButton :to="ADMIN_PATHS.projects" color="neutral" variant="outline" icon="i-lucide-arrow-left">返回列表</UButton></template>
    <ProjectEditor />
  </AdminShell>
</template>
