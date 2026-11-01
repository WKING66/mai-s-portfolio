<script setup lang="ts">
import type { TableColumn } from '@nuxt/ui'
import type { BlogCard } from '../../api/blogs'
import { ADMIN_PATHS } from '../../constants/admin'
import { BLOG_PAGE_SIZE } from '../../constants/blogs'
const { result: blogPage, pending, error, load, transition } = useAdminBlogList()
const importOpen = ref(false)
const importing = ref(false)
const propertiesId = ref<number | null>(null)
const columns: TableColumn<BlogCard>[] = [
  { accessorKey: 'title', header: '文章' },
  { accessorKey: 'status', header: '状态' },
  { accessorKey: 'revision', header: '修订号' },
  { accessorKey: 'slug', header: 'Slug' },
  { id: 'actions', header: '操作', meta: { class: { th: 'text-right', td: 'text-right' } } },
]
onMounted(() => load())
</script>
<template>
  <div class="grid min-w-0 gap-5">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <p class="text-sm text-muted">
        共 {{ blogPage?.total ?? '—' }} 篇文章 · 每页 {{ BLOG_PAGE_SIZE }} 条
      </p>
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
    <UCard v-if="!blogPage || !blogPage.items.length" :ui="{ body: 'p-0 sm:p-0' }"
      ><AdminListState :loading="pending" :error="error" @retry="load()"
    /></UCard>
    <template v-else>
      <UCard class="admin-desktop-list admin-list-card" :ui="{ body: 'p-0 sm:p-0' }">
        <UTable
          :data="blogPage.items"
          :columns="columns"
          :ui="{
            th: 'px-5 py-4 text-xs text-muted',
            td: 'px-5 py-5',
            tr: 'border-b border-default last:border-b-0',
            thead: 'bg-elevated/50',
          }"
        >
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
              @properties="propertiesId = $event"
          /></template>
        </UTable>
      </UCard>
      <div class="admin-mobile-list">
        <UCard v-for="article in blogPage.items" :key="article.id" as="article">
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
            @properties="propertiesId = $event"
          />
        </UCard>
      </div>
    </template>
    <div v-if="blogPage" class="admin-pagination">
      <p class="text-sm text-muted">第 {{ blogPage.page }} 页 · 共 {{ blogPage.total }} 篇</p>
      <UPagination
        v-if="blogPage.total > BLOG_PAGE_SIZE || blogPage.page > 1"
        :page="blogPage.page"
        :total="Math.max(blogPage.total, (blogPage.page - 1) * BLOG_PAGE_SIZE + 1)"
        :items-per-page="BLOG_PAGE_SIZE"
        :disabled="pending"
        @update:page="load"
      />
    </div>
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
