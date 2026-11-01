<script setup lang="ts">
import type { TableColumn } from '@nuxt/ui'
import type { ProjectEditor } from '../../api/projects'
import { ADMIN_PATHS, ADMIN_PROJECT_FILTERS } from '../../constants/admin'
const { result, filter, pending, error, load, reload, transition, changePage, changeFilter } =
  useAdminProjectList()
const route = useRoute()
const columns: TableColumn<ProjectEditor>[] = [
  { accessorKey: 'title', header: '项目' },
  { accessorKey: 'status', header: '状态' },
  { accessorKey: 'featured', header: '首页重点' },
  { accessorKey: 'sortOrder', header: '排序' },
  { id: 'actions', header: '操作', meta: { class: { th: 'text-right', td: 'text-right' } } },
]
watch(
  () => route.path,
  (path, previous) => {
    if (path === ADMIN_PATHS.projects && previous !== path) {
      void reload()
    }
  },
)
onMounted(() => load())
</script>
<template>
  <div class="grid min-w-0 gap-5">
    <UCard :ui="{ body: 'flex flex-wrap items-end justify-between gap-4 p-4 sm:p-5' }">
      <UFormField label="项目状态" name="project-status-filter"
        ><USelect
          :model-value="filter"
          :items="ADMIN_PROJECT_FILTERS.map((item) => ({ ...item }))"
          :disabled="pending"
          class="w-40"
          @update:model-value="changeFilter"
      /></UFormField>
      <div class="flex flex-wrap gap-2">
        <UButton
          color="neutral"
          variant="outline"
          icon="i-lucide-refresh-cw"
          :loading="pending"
          :disabled="pending"
          @click="reload()"
          >刷新</UButton
        ><UButton :to="ADMIN_PATHS.newProject" icon="i-lucide-plus">新建项目</UButton>
      </div>
    </UCard>
    <UCard v-if="!result || !result.items.length" :ui="{ body: 'p-0 sm:p-0' }"
      ><AdminListState :loading="pending" :error="error" @retry="reload()"
    /></UCard>
    <template v-else>
      <UCard class="admin-desktop-list admin-list-card" :ui="{ body: 'p-0 sm:p-0' }">
        <UTable
          :data="result.items"
          :columns="columns"
          :ui="{
            th: 'px-5 py-4 text-xs text-muted',
            td: 'px-5 py-5',
            tr: 'border-b border-default last:border-b-0',
            thead: 'bg-elevated/50',
          }"
        >
          <template #title-cell="{ row }"
            ><div class="min-w-40 max-w-lg">
              <NuxtLink
                :to="`${ADMIN_PATHS.projects}/${row.original.id}`"
                class="font-semibold text-highlighted hover:text-primary"
                >{{ row.original.title || '未命名草稿' }}</NuxtLink
              >
              <p class="mt-1 line-clamp-2 text-xs leading-5 text-muted">
                {{ row.original.summary }}
              </p>
              <p class="mt-2 text-xs text-muted">
                v{{ row.original.version }} · {{ row.original.tags.length }} 个技术标签
              </p>
            </div></template
          >
          <template #status-cell="{ row }"
            ><UBadge
              :color="row.original.status === 'PUBLISHED' ? 'success' : 'neutral'"
              variant="subtle"
              >{{ row.original.status === 'PUBLISHED' ? '已发布' : '草稿' }}</UBadge
            ></template
          >
          <template #featured-cell="{ row }"
            ><UBadge v-if="row.original.featured" variant="subtle">重点</UBadge
            ><span v-else class="text-muted">—</span></template
          >
          <template #actions-cell="{ row }"
            ><AdminProjectActions
              :project="row.original"
              :pending="pending"
              :transition="transition"
          /></template>
        </UTable>
      </UCard>
      <div class="admin-mobile-list">
        <UCard v-for="project in result.items" :key="project.id" as="article">
          <div class="flex items-start justify-between gap-3">
            <h2 class="font-semibold">{{ project.title || '未命名草稿' }}</h2>
            <UBadge
              :color="project.status === 'PUBLISHED' ? 'success' : 'neutral'"
              variant="subtle"
              >{{ project.status === 'PUBLISHED' ? '已发布' : '草稿' }}</UBadge
            >
          </div>
          <p class="mt-3 line-clamp-2 text-sm text-muted">{{ project.summary }}</p>
          <div class="mt-3 flex flex-wrap items-center gap-2 text-xs text-muted">
            <UBadge v-if="project.featured" variant="subtle">首页重点</UBadge
            ><span
              >v{{ project.version }} · 排序 {{ project.sortOrder }} ·
              {{ project.tags.length }} 个标签</span
            >
          </div>
          <AdminProjectActions :project="project" :pending="pending" :transition="transition" />
        </UCard>
      </div>
    </template>
    <div v-if="result" class="admin-pagination">
      <p class="text-sm text-muted">第 {{ result.page }} 页 · 共 {{ result.total }} 个项目</p>
      <UPagination
        v-if="result.total > result.size || result.page > 1"
        :page="result.page"
        :total="Math.max(result.total, (result.page - 1) * result.size + 1)"
        :items-per-page="result.size"
        :disabled="pending"
        @update:page="changePage"
      />
    </div>
  </div>
</template>
