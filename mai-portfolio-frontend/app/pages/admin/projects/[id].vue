<script setup lang="ts">
import { AUTH_ACCESS } from '../../../constants/auth'
definePageMeta({ auth: AUTH_ACCESS.OWNER, key: (route) => route.path })
useSeoMeta({ title: '编辑项目', robots: 'noindex,nofollow' })
const route = useRoute()
const projectId = Number(route.params.id)
if (!Number.isSafeInteger(projectId) || projectId < 1) {
  throw createError({ statusCode: 404 })
}
</script>
<template><ProjectEditorModal :project-id="projectId" /></template>
