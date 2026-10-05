<script setup lang="ts">
import { usePublicProjects } from '../api/projects'
import { PROJECT_MESSAGES, PROJECT_PAGE_SIZE } from '../constants/projects'
const route = useRoute()
const page = computed(() => {
  const value = Number(route.query.page || 1)
  return Number.isSafeInteger(value) && value > 0 ? value : 1
})
const { data, error } = await usePublicProjects(page, PROJECT_PAGE_SIZE)
const projects = computed(() => data.value?.data)
useSeoMeta({ title: '项目作品 · 阿霾', description: 'Java 后端与 AI 智能体开发项目，展示本人贡献、技术实践与成果。' })
</script>
<template>
  <SiteHeader />
  <main class="mx-auto min-h-[70vh] max-w-[1200px] px-5 py-16">
    <SectionHeading id="all-projects" eyebrow="PROJECTS" title="项目作品" description="从项目贡献与成果，了解我的技术实践。" />
    <p v-if="error" role="alert">{{ PROJECT_MESSAGES.loadFailed }}</p>
    <p v-else-if="!projects?.items.length" role="status">{{ PROJECT_MESSAGES.empty }}</p>
    <div v-else class="grid gap-6 md:grid-cols-2 lg:grid-cols-3">
      <ProjectCard v-for="project in projects.items" :key="project.id" :project="project" />
    </div>
    <nav v-if="projects && (page > 1 || projects.total > PROJECT_PAGE_SIZE)" class="mt-10 flex items-center justify-center gap-6" aria-label="项目分页">
      <NuxtLink v-if="page > 1" :to="{ path: '/projects', query: { page: page - 1 } }">上一页</NuxtLink>
      <span>第 {{ page }} 页 · 共 {{ projects.total }} 个项目</span>
      <NuxtLink v-if="page * PROJECT_PAGE_SIZE < projects.total" :to="{ path: '/projects', query: { page: page + 1 } }">下一页</NuxtLink>
    </nav>
  </main>
</template>
