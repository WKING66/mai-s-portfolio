<script setup lang="ts">
import { ADMIN_PATHS } from '../../constants/admin'
defineProps<{ projectId?: number }>()
const editor = useTemplateRef('editor')
async function close() {
  if (editor.value?.pending) {
    return
  }
  // 表单的路由守卫统一处理未保存确认；不要预先确认再导航而重复询问。
  await navigateTo(ADMIN_PATHS.projects)
}
</script>
<template>
  <UModal
    :open="true"
    :title="projectId ? '编辑项目' : '新建项目'"
    description="新建为草稿；已发布项目保存后更新公开内容。"
    :dismissible="!editor?.pending"
    :close="!editor?.pending"
    :ui="{
      content: 'sm:max-w-3xl h-[min(52rem,calc(100dvh-2rem))]',
      header: 'shrink-0',
      body: 'flex min-h-0 flex-1 flex-col p-0 sm:p-0',
    }"
    @update:open="!$event && close()"
  >
    <template #body
      ><ProjectEditor ref="editor" :project-id="projectId" @cancel="close" @saved="close"
    /></template>
  </UModal>
</template>
