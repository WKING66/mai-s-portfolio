<script setup lang="ts">
import { AUTH_ACCESS } from '../../../constants/auth'
import { ADMIN_PATHS } from '../../../constants/admin'
definePageMeta({ auth: AUTH_ACCESS.OWNER })
useSeoMeta({ title: '博客管理', robots: 'noindex,nofollow' })
</script>
<template>
  <AdminShell
    title="博客管理"
    description="编辑草稿、导入文档，管理文章的发布与导出。"
    ><template #actions
      ><UButton :to="`${ADMIN_PATHS.blogs}/new`" icon="i-lucide-plus"
        >新建文章</UButton
      ></template
    ><AdminBlogList
  /></AdminShell>
</template>
