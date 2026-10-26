<script setup lang="ts">
import { AUTH_ACCESS } from '../../../constants/auth'
definePageMeta({ auth: AUTH_ACCESS.OWNER, immersiveWorkbench: true, key: false })
useSeoMeta({ title: '编辑博客', robots: 'noindex,nofollow' })
const route = useRoute()
const blogPostId = computed(() => {
  if (route.params.id === 'new') {
    return null
  }
  const id = Number(route.params.id)
  if (!Number.isSafeInteger(id) || id < 1) {
    throw createError({ statusCode: 404 })
  }
  return id
})
</script>
<template>
  <AdminShell wide immersive title="编辑博客" description="编辑内容与资源，保存草稿后再发布。">
    <BlogEditor :id="blogPostId" />
  </AdminShell>
</template>
