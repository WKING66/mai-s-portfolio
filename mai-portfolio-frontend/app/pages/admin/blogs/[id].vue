<script setup lang="ts">
import { AUTH_ACCESS } from '../../../constants/auth'
definePageMeta({ auth: AUTH_ACCESS.OWNER })
useSeoMeta({ title: '编辑博客', robots: 'noindex,nofollow' })
const route = useRoute()
const id = Number(route.params.id)
if (!Number.isSafeInteger(id) || id < 1) throw createError({ statusCode: 404 })
</script>
<template>
  <AdminShell title="编辑博客" description="编辑内容与资源，保存草稿后再发布。"
    ><BlogEditor :id="id"
  /></AdminShell>
</template>
