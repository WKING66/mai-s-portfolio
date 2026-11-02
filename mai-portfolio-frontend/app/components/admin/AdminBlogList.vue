<script setup lang="ts">
import type { TableColumn } from '@nuxt/ui'
import type { BlogCard } from '../../api/blogs'
import { ADMIN_PATHS } from '../../constants/admin'
const {
  result: blogPage,
  pending,
  error,
  pageSize,
  keyword,
  filter,
  search,
  clear,
  changeFilter,
  load,
  changeSize,
  transition,
  remove,
} = useAdminBlogList()
const importOpen = ref(false)
const importing = ref(false)
const propertiesId = ref<number | null>(null)
const columns: TableColumn<BlogCard>[] = [
  { accessorKey: 'title', header: '文章' },
  { accessorKey: 'status', header: '状态' },
  { accessorKey: 'revision', header: '修订号', meta: { class: { th: 'whitespace-nowrap' } } },
  { accessorKey: 'slug', header: 'Slug' },
  {
    id: 'actions',
    header: '操作',
    meta: { class: { th: 'min-w-60 text-right whitespace-nowrap', td: 'min-w-60 text-right' } },
  },
]
onMounted(() => load())
</script>
<template>
  <div class="admin-list flex min-h-0 flex-1 flex-col gap-3">
    <div class="admin-list-toolbar flex shrink-0 flex-wrap items-center justify-between gap-3">
      <AdminListFilters
        :keyword="keyword"
        :status="filter"
        :pending="pending"
        noun="文章"
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
          :disabled="pending"
          :loading="pending"
          @click="load()"
          >刷新</UButton
        >
        <UButton
          color="neutral"
          variant="outline"
          icon="i-lucide-file-up"
          @click="importOpen = true"
          >导入文章</UButton
        >
        <UButton :to="`${ADMIN_PATHS.blogs}/new`" icon="i-lucide-plus">新建文章</UButton>
      </div>
    </div>
    <div class="admin-table-viewport relative flex min-h-0 flex-1 flex-col" :aria-busy="pending">
      <UCard
        class="admin-desktop-list admin-list-card min-h-0 flex-1 flex-col"
        :ui="{ body: 'flex min-h-0 flex-1 flex-col p-0 sm:p-0' }"
      >
        <UTable
          sticky="header"
          class="min-h-0 flex-1"
          :data="blogPage?.items ?? []"
          :columns="columns"
          :ui="{
            th: 'px-4 py-3 text-xs text-muted',
            td: 'px-4 py-3',
            tr: 'border-b border-default',
            thead: 'bg-elevated/50',
          }"
        >
          <template #empty
            ><AdminListState :loading="false" :error="error" @retry="load()"
          /></template>
          <template #title-cell="{ row }"
            ><div class="min-w-40 max-w-md">
              <NuxtLink
                :to="`${ADMIN_PATHS.blogs}/${row.original.id}`"
                class="font-semibold text-highlighted hover:text-primary"
                >{{ row.original.title || '未命名草稿' }}</NuxtLink
              >
              <p v-if="row.original.summary" class="mt-1 line-clamp-2 text-xs leading-5 text-muted">
                {{ row.original.summary }}
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
          <template #revision-cell="{ row }"
            ><span class="text-xs text-muted">v{{ row.original.revision }}</span></template
          >
          <template #slug-cell="{ row }"
            ><span
              class="block max-w-36 truncate text-xs text-muted"
              :title="row.original.slug || ''"
              >{{ row.original.slug || '未设置' }}</span
            ></template
          >
          <template #actions-cell="{ row }"
            ><AdminBlogActions
              :article="row.original"
              :pending="pending"
              :transition="transition"
              :remove="remove"
              @properties="propertiesId = $event"
          /></template>
        </UTable>
      </UCard>
      <div class="admin-mobile-list min-h-0 flex-1 overflow-y-auto">
        <AdminListState
          v-if="!blogPage?.items.length"
          :loading="false"
          :error="error"
          @retry="load()"
        />
        <UCard v-for="article in blogPage?.items ?? []" :key="article.id" as="article">
          <div class="flex items-start justify-between gap-3">
            <h2 class="font-semibold">{{ article.title || '未命名草稿' }}</h2>
            <UBadge
              :color="article.status === 'PUBLISHED' ? 'success' : 'neutral'"
              variant="subtle"
              >{{ article.status === 'PUBLISHED' ? '已发布' : '草稿' }}</UBadge
            >
          </div>
          <p v-if="article.summary" class="mt-3 line-clamp-2 text-sm text-muted">
            {{ article.summary }}
          </p>
          <p class="mt-3 break-all text-xs text-muted">
            v{{ article.revision }} · {{ article.slug || '未设置地址' }}
          </p>
          <AdminBlogActions
            :article="article"
            :pending="pending"
            :transition="transition"
            :remove="remove"
            @properties="propertiesId = $event"
          />
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
      :page="blogPage?.page ?? 1"
      :size="pageSize"
      :total="blogPage?.total ?? 0"
      :disabled="pending"
      @page="load"
      @size="changeSize"
    />
    <UModal
      :open="importOpen"
      title="导入 Markdown"
      description="导入为草稿，检查后再发布。"
      :dismissible="!importing"
      :close="!importing"
      :ui="{ content: 'sm:max-w-2xl', body: 'p-0 sm:p-0' }"
      @update:open="importOpen = $event"
    >
      <template #body><BlogImportPanel v-if="importOpen" @busy="importing = $event" /></template>
    </UModal>
    <BlogPropertiesDialog
      v-if="propertiesId !== null"
      :article-id="propertiesId"
      @close="propertiesId = null"
      @saved="load()"
    />
  </div>
</template>
