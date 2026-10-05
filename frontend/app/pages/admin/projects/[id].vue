<script setup lang="ts">
import { AUTH_ACCESS, AUTH_MESSAGES } from '../../../constants/auth'
definePageMeta({ auth: AUTH_ACCESS.OWNER, key: route => route.path })
useSeoMeta({ title: '编辑项目', robots: 'noindex,nofollow' })
const route = useRoute()
const projectId = Number(route.params.id)
if (!Number.isSafeInteger(projectId) || projectId < 1) throw createError({ statusCode: 404 })
</script>
<template>
  <Auth :access="AUTH_ACCESS.OWNER">
    <!-- 未确认权限时不挂载编辑器，SSR 和初始化均不会读取管理快照。 -->
    <ProjectEditor :project-id="projectId" />
    <template #pending><p role="status">{{ AUTH_MESSAGES.verifying }}</p></template>
  </Auth>
</template>
