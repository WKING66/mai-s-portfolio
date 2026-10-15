<script setup lang="ts">
import { AUTH_ACCESS } from '../../../constants/auth'
import { ADMIN_MESSAGES, ADMIN_PATHS } from '../../../constants/admin'

definePageMeta({ auth: AUTH_ACCESS.OWNER, key: (route) => route.path })
useSeoMeta({ title: ADMIN_MESSAGES.editProjectTitle, robots: 'noindex,nofollow' })
const route = useRoute()
// 切换项目沿用路由级稳定 key；非法标识在挂载编辑器前按原规则返回 404。
const projectId = Number(route.params.id)
if (!Number.isSafeInteger(projectId) || projectId < 1) {
  throw createError({ statusCode: 404 })
}
</script>

<template>
  <AdminShell
    :title="ADMIN_MESSAGES.editProjectTitle"
    :description="ADMIN_MESSAGES.projectEditorDescription"
  >
    <template #actions>
      <UButton
        :to="ADMIN_PATHS.projects"
        color="neutral"
        variant="outline"
        icon="i-lucide-arrow-left"
      >
        返回列表
      </UButton>
    </template>
    <ProjectEditor :project-id="projectId" />
  </AdminShell>
</template>
