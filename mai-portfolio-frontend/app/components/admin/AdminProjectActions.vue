<script setup lang="ts">
import type { ProjectEditor } from '../../api/projects'
import { ADMIN_PATHS } from '../../constants/admin'
defineProps<{
  project: ProjectEditor
  pending: boolean
  transition: (project: ProjectEditor) => Promise<boolean>
}>()
</script>
<template>
  <div class="admin-row-actions">
    <UButton
      :to="`${ADMIN_PATHS.projects}/${project.id}`"
      color="neutral"
      variant="outline"
      size="sm"
      class="justify-center"
      :disabled="pending"
      >编辑</UButton
    >
    <UiConfirmAction
      :label="project.status === 'PUBLISHED' ? '下架' : '发布'"
      :title="project.status === 'PUBLISHED' ? '下架项目？' : '发布项目？'"
      :description="
        project.status === 'PUBLISHED'
          ? '关闭项目的公开展示，保留项目内容。'
          : '发布已保存项目，向访客展示项目内容。'
      "
      :pending="pending"
      :action="() => transition(project)"
    />
  </div>
</template>
