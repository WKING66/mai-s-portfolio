<script setup lang="ts">
import { AUTH_ACCESS } from '../../../constants/auth'
definePageMeta({ auth: AUTH_ACCESS.OWNER })
useSeoMeta({ title: '博客管理', robots: 'noindex,nofollow' })
</script>
<template>
  <AdminShell contained title="博客管理" description="编辑草稿、导入文档，管理文章的发布与导出。">
    <AdminBlogList />
  </AdminShell>
</template>
