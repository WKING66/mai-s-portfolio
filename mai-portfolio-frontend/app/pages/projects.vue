<script setup lang="ts">
import { usePublicProjects } from '../api/projects'
import { PROJECT_MESSAGES, PROJECT_PAGE_SIZE } from '../constants/projects'
import { createProjectStructuredData, getProjectCanonicalPath } from '#shared/utils/seo'
import { useReadErrorFeedback } from '../composables/useFeedback'
const route = useRoute()
// 1. 从路由解析合法页码，仍按实际 API 响应展示分页数据。
const currentPage = computed(() => {
  const pageNumber = Number(route.query.page || 1)
  return Number.isSafeInteger(pageNumber) && pageNumber > 0 ? pageNumber : 1
})
// 2. 读取当前公开分页并复用 SSR 数据；失败保留独立重试入口。
const {
  data: projectListResponse,
  error: projectsError,
  refresh: refreshProjects,
} = await usePublicProjects(currentPage, PROJECT_PAGE_SIZE)
useReadErrorFeedback(() => projectsError.value, PROJECT_MESSAGES.loadFailed)
const projectPage = computed(() => projectListResponse.value?.data)
// 3. 分页使用各自规范地址，越界空页不参与索引。
const { siteOrigin } = usePortfolioSeo({
  title: '项目作品 · 阿霾',
  description: 'Java 后端与 AI 智能体开发项目，展示本人贡献、技术实践与成果。',
  canonicalPath: () => getProjectCanonicalPath(currentPage.value),
  // 空的第一页仍是公开栏目；越界空分页不应形成无限可索引地址。
  available: () =>
    !projectsError.value &&
    Boolean(projectPage.value && (currentPage.value === 1 || projectPage.value.items.length > 0)),
})
useHead(() => {
  const structuredData = createProjectStructuredData(
    siteOrigin,
    projectsError.value ? null : (projectPage.value ?? null),
  )
  return {
    script: structuredData
      ? [
          {
            key: 'portfolio-projects',
            type: 'application/ld+json',
            textContent: JSON.stringify(structuredData),
          },
        ]
      : [],
  }
})
</script>
<template>
  <main class="site-container site-page">
    <UiSectionHeading
      id="all-projects"
      eyebrow="PROJECTS"
      title="项目作品"
      description="从项目贡献与成果，了解我的技术实践。"
    />
    <UButton
      v-if="projectsError"
      color="neutral"
      variant="outline"
      icon="i-lucide-refresh-cw"
      @click="refreshProjects()"
      >重新读取项目</UButton
    >
    <p v-else-if="!projectPage?.items.length" role="status">{{ PROJECT_MESSAGES.empty }}</p>
    <div v-else class="grid gap-6 md:grid-cols-2 lg:grid-cols-3">
      <ProjectCard v-for="project in projectPage.items" :key="project.id" :project="project" />
    </div>
    <nav
      v-if="projectPage && (currentPage > 1 || projectPage.total > PROJECT_PAGE_SIZE)"
      class="mt-10 flex items-center justify-center gap-6"
      aria-label="项目分页"
    >
      <NuxtLink v-if="currentPage > 1" :to="{ path: '/projects', query: { page: currentPage - 1 } }"
        >上一页</NuxtLink
      >
      <span>第 {{ currentPage }} 页 · 共 {{ projectPage.total }} 个项目</span>
      <NuxtLink
        v-if="currentPage * PROJECT_PAGE_SIZE < projectPage.total"
        :to="{ path: '/projects', query: { page: currentPage + 1 } }"
        >下一页</NuxtLink
      >
    </nav>
  </main>
</template>
