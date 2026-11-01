<script setup lang="ts">
import type { TableColumn } from '@nuxt/ui'
import type { ProjectEditor } from '../../api/projects'
import { ADMIN_PATHS } from '../../constants/admin'
const {
  result,
  filter,
  pending,
  error,
  load,
  reload,
  transition,
  changePage,
  changeFilter,
  pageSize,
  changeSize,
  keyword,
  search,
  clear,
} = useAdminProjectList()
const route = useRoute()
const columns: TableColumn<ProjectEditor>[] = [
  { accessorKey: 'title', header: '项目' },
  { accessorKey: 'status', header: '状态' },
  { accessorKey: 'featured', header: '首页重点' },
  { accessorKey: 'sortOrder', header: '排序' },
  {
    id: 'actions',
    header: '操作',
    meta: { class: { th: 'min-w-48 text-right whitespace-nowrap', td: 'min-w-48 text-right' } },
  },
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
  <div class="admin-list flex min-h-0 flex-1 flex-col gap-3">
    <div class="admin-list-toolbar flex shrink-0 flex-wrap items-center justify-between gap-3">
      <AdminListFilters
        :keyword="keyword"
        :status="filter"
        :pending="pending"
        noun="项目"
        @keyword="keyword = $event"
        @status="changeFilter"
        @search="search"
        @clear="clear"
      />
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
    </div>
    <div class="relative flex min-h-0 flex-1 flex-col" :aria-busy="pending">
      <UCard
        class="admin-desktop-list admin-list-card min-h-0 flex-1 flex-col"
        :ui="{ body: 'flex min-h-0 flex-1 flex-col p-0 sm:p-0' }"
      >
        <UTable
          sticky="header"
          class="min-h-0 flex-1"
          :data="result?.items ?? []"
          :columns="columns"
          :ui="{
            th: 'px-4 py-3 text-xs text-muted',
            td: 'px-4 py-3',
            tr: 'border-b border-default',
            thead: 'bg-elevated/50',
          }"
        >
          <template #empty
            ><AdminListState :loading="false" :error="error" @retry="reload()"
          /></template>
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
      <div class="admin-mobile-list min-h-0 flex-1 overflow-y-auto">
        <AdminListState
          v-if="!result?.items.length"
          :loading="false"
          :error="error"
          @retry="reload()"
        />
        <UCard v-for="project in result?.items ?? []" :key="project.id" as="article">
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
      <div
        v-if="pending"
        class="absolute inset-0 z-10 grid place-items-center rounded-xl bg-default/75"
        role="status"
        aria-label="正在读取列表"
      >
        <span
          class="flex items-center gap-2 rounded-lg bg-default px-4 py-3 text-sm font-medium shadow-sm"
          ><UIcon
            name="i-lucide-loader-circle"
            class="size-5 animate-spin text-primary"
          />正在加载…</span
        >
      </div>
    </div>
    <AdminPagination
      :page="result?.page ?? 1"
      :size="pageSize"
      :total="result?.total ?? 0"
      :disabled="pending"
      @page="changePage"
      @size="changeSize"
    />
  </div>
</template>
