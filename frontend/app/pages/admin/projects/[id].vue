<script setup lang="ts">
definePageMeta({ middleware: 'owner', key: route => route.path })
useSeoMeta({ title: '编辑项目', robots: 'noindex,nofollow' })
const route = useRoute()
const projectId = Number(route.params.id)
if (!Number.isSafeInteger(projectId) || projectId < 1) throw createError({ statusCode: 404 })
</script>
<template><ProjectEditor :project-id="projectId" /></template>
