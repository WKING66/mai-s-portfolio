<script setup lang="ts">
import { AUTH_ACCESS } from '../../../constants/auth'
definePageMeta({ auth: AUTH_ACCESS.OWNER })
useSeoMeta({ title: '编辑博客', robots: 'noindex,nofollow' })
const route = useRoute()
// 路由标识先校验再挂载编辑器，避免为非法地址发起管理读取。
const blogPostId = Number(route.params.id)
if (!Number.isSafeInteger(blogPostId) || blogPostId < 1) {
  throw createError({ statusCode: 404 })
}
</script>
<template>
  <AdminShell wide title="编辑博客" description="编辑内容与资源，保存草稿后再发布。">
    <BlogEditor :id="blogPostId" />
  </AdminShell>
</template>
