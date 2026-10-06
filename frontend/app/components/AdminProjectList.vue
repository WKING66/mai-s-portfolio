<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useAdminProjectList } from '../composables/useAdminProjectList'
import { ADMIN_MESSAGES, ADMIN_PATHS, ADMIN_PROJECT_FILTERS, ADMIN_PROJECT_STATUS } from '../constants/admin'
import { PROJECT_MESSAGES } from '../constants/projects'

const { result, filter, pending, changingProjectId, error, notice, canPrevious, canNext,
  load, reload, transition, changePage, changeFilter } = useAdminProjectList()
// UI 的 items 类型为可变数组；复制常量，组件内部不能改写共享选项。
const filters = ADMIN_PROJECT_FILTERS.map(item => ({ ...item }))
const emptyMessage = computed(() => result.value && result.value.page > 1
  ? ADMIN_MESSAGES.pageEmpty : filter.value === 'ALL' ? PROJECT_MESSAGES.manageEmpty : ADMIN_MESSAGES.filteredEmpty)
// 此组件只在 AdminShell 的 OWNER 插槽内挂载，不在页面鉴权完成前读取管理列表。
onMounted(() => load())
</script>

<template>
  <div class="grid gap-6" :aria-busy="pending">
    <UCard>
      <div class="flex flex-wrap items-end justify-between gap-4">
        <UFormField label="状态筛选" name="project-status-filter">
          <USelect id="project-status-filter" :model-value="filter" :items="filters" :disabled="pending" class="w-44" @update:model-value="changeFilter" />
        </UFormField>
        <UButton type="button" color="neutral" variant="outline" icon="i-lucide-refresh-cw" :disabled="pending" :loading="pending && changingProjectId === null" @click="reload()">重新读取</UButton>
      </div>
    </UCard>
    <UAlert v-if="error" role="alert" color="error" variant="subtle" :title="error" icon="i-lucide-circle-alert" />
    <UAlert v-if="notice" role="status" color="success" variant="subtle" :title="notice" icon="i-lucide-circle-check" />
    <div v-if="pending && !result" role="status" :aria-label="ADMIN_MESSAGES.loadingProjects" class="grid min-h-[480px] gap-4">
      <UCard v-for="index in 3" :key="index"><USkeleton class="mb-5 h-6 w-56" /><USkeleton class="mb-3 h-4 w-3/4" /><USkeleton class="h-3 w-40" /></UCard>
    </div>
    <UCard v-else-if="result && !result.items.length">
      <div class="grid justify-items-center gap-4 py-8 text-center">
        <UIcon name="i-lucide-folder-open" class="size-9 text-muted" aria-hidden="true" />
        <p role="status" class="text-muted">{{ emptyMessage }}</p>
        <UButton v-if="result.page === 1" :to="ADMIN_PATHS.newProject" icon="i-lucide-plus">新增项目</UButton>
      </div>
    </UCard>
    <div v-else-if="result" class="grid gap-4">
      <UCard v-for="project in result.items" :key="project.id" as="article">
        <div class="flex flex-wrap items-start justify-between gap-5">
          <div class="min-w-0 flex-1">
            <div class="flex flex-wrap items-center gap-3">
              <h2 class="break-words text-lg font-semibold sm:text-xl">{{ project.title || ADMIN_MESSAGES.unnamedDraft }}</h2>
              <UBadge :color="project.status === ADMIN_PROJECT_STATUS.PUBLISHED ? 'success' : 'neutral'" variant="subtle">{{ project.status === ADMIN_PROJECT_STATUS.PUBLISHED ? '已发布' : '草稿' }}</UBadge>
              <UBadge v-if="project.featured" color="primary" variant="subtle">首页重点</UBadge>
            </div>
            <p v-if="project.summary" class="mt-3 line-clamp-2 break-words text-sm leading-6 text-muted">{{ project.summary }}</p>
            <p class="mt-3 text-xs text-muted">版本 {{ project.version }} · 排序 {{ project.sortOrder }} · {{ project.tags.length }} 个技术标签</p>
          </div>
          <div class="flex shrink-0 flex-wrap gap-2">
            <UButton :to="ADMIN_PATHS.projects + '/' + project.id" color="neutral" variant="outline" icon="i-lucide-pencil" :disabled="pending">编辑项目</UButton>
            <UButton type="button" :color="project.status === ADMIN_PROJECT_STATUS.PUBLISHED ? 'neutral' : 'primary'" variant="soft" :disabled="pending" :loading="changingProjectId === project.id" @click="transition(project)">{{ project.status === ADMIN_PROJECT_STATUS.PUBLISHED ? '下架' : '发布' }}</UButton>
          </div>
        </div>
      </UCard>
    </div>
    <nav v-if="result" class="flex flex-wrap items-center justify-between gap-4" aria-label="管理列表分页">
      <p class="text-sm text-muted">第 {{ result.page }} 页 · 共 {{ result.total }} 个项目</p>
      <div class="flex gap-2">
        <UButton type="button" color="neutral" variant="outline" icon="i-lucide-chevron-left" :disabled="pending || !canPrevious" @click="changePage(result.page - 1)">上一页</UButton>
        <UButton type="button" color="neutral" variant="outline" trailing-icon="i-lucide-chevron-right" :disabled="pending || !canNext" @click="changePage(result.page + 1)">下一页</UButton>
      </div>
    </nav>
  </div>
</template>
