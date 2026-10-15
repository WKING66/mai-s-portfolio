<script setup lang="ts">
import { AUTH_ACCESS } from '../../../constants/auth'
definePageMeta({ auth: AUTH_ACCESS.OWNER })
useSeoMeta({ title: '新建博客', robots: 'noindex,nofollow' })
</script>
<template>
  <AdminShell wide title="新建博客" description="编写正文、添加资源，保存草稿后再发布。">
    <BlogEditor :id="null" />
  </AdminShell>
</template>
