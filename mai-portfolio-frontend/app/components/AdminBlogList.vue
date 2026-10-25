<script setup lang="ts">
import { useBlogApi, type BlogPage } from '../api/blogs'
import { BLOG_FIRST_PAGE, BLOG_PAGE_SIZE } from '../constants/blogs'
import { ADMIN_PATHS } from '../constants/admin'

const blogApi = useBlogApi()
const feedback = useFeedback()
const blogPage = ref<BlogPage | null>(null)
const isLoading = ref(true)
// 读取成功才替换分页快照；失败保留原页内容并展示错误，结束后释放加载状态。
async function loadBlogPage(page = blogPage.value?.page ?? BLOG_FIRST_PAGE) {
  isLoading.value = true
  try {
    // 分页以成功读回的结果为准；请求失败时，不显示一个并未加载成功的页码。
    blogPage.value = await blogApi.list(page)
  } catch (error) {
    feedback.error(error)
  } finally {
    isLoading.value = false
  }
}
// 由 AdminShell 在 OWNER 鉴权完成后挂载，不在权限恢复期间发出管理请求。
onMounted(() => loadBlogPage())
</script>

<template>
  <div class="admin-content-layout">
    <section class="min-w-0 space-y-4" aria-label="文章列表" :aria-busy="isLoading">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div class="flex items-center gap-3">
          <h2 class="text-lg font-semibold">全部文章</h2>
          <UBadge v-if="blogPage" color="neutral" variant="subtle">{{ blogPage.total }} 篇</UBadge>
        </div>
        <UButton
          color="neutral"
          variant="ghost"
          icon="i-lucide-refresh-cw"
          :loading="isLoading"
          :disabled="isLoading"
          @click="loadBlogPage()"
          >刷新列表</UButton
        >
      </div>
      <USkeleton v-if="isLoading && !blogPage" class="h-64 rounded-xl" aria-label="正在读取文章" />
      <UCard v-else-if="!blogPage">
        <div class="grid justify-items-center gap-3 py-8">
          <p class="text-sm text-muted">文章列表暂时无法读取。</p>
          <UButton color="neutral" variant="outline" @click="loadBlogPage()">重新读取</UButton>
        </div>
      </UCard>
      <UCard v-else-if="!blogPage.items.length">
        <div class="grid justify-items-center gap-4 py-12 text-center">
          <div class="grid size-14 place-items-center rounded-2xl bg-elevated">
            <UIcon name="i-lucide-notebook-pen" class="size-7 text-muted" aria-hidden="true" />
          </div>
          <h3 class="font-semibold">写下第一篇文章</h3>
          <p class="text-sm leading-6 text-muted">新建草稿，或导入已有的 Markdown 文档。</p>
          <UButton :to="`${ADMIN_PATHS.blogs}/new`" icon="i-lucide-plus">新建文章</UButton>
        </div>
      </UCard>
      <template v-else>
        <UCard v-for="article in blogPage.items" :key="article.id" as="article">
          <div class="flex flex-wrap items-start justify-between gap-4">
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-3">
                <h3 class="break-words font-semibold">
                  {{ article.title || '未命名草稿' }}
                </h3>
                <UBadge
                  :color="article.status === 'PUBLISHED' ? 'success' : 'neutral'"
                  variant="subtle"
                >
                  {{ article.status === 'PUBLISHED' ? '已发布' : '草稿' }}
                </UBadge>
              </div>
              <p
                v-if="article.summary"
                class="mt-3 line-clamp-2 break-words text-sm leading-6 text-muted"
              >
                {{ article.summary }}
              </p>
              <p class="mt-3 text-xs text-muted">
                修订 {{ article.revision }} ·
                {{ article.slug || '尚未设置文章标识' }}
              </p>
            </div>
            <UButton
              :to="`${ADMIN_PATHS.blogs}/${article.id}`"
              color="neutral"
              variant="outline"
              icon="i-lucide-pencil"
              >编辑</UButton
            >
          </div>
        </UCard>
        <div class="flex flex-wrap items-center justify-between gap-3 pt-2">
          <p class="text-sm text-muted">第 {{ blogPage.page }} 页 · 共 {{ blogPage.total }} 篇</p>
          <UPagination
            v-if="blogPage.total > BLOG_PAGE_SIZE"
            :page="blogPage.page"
            :disabled="isLoading"
            :total="blogPage.total"
            :items-per-page="BLOG_PAGE_SIZE"
            @update:page="loadBlogPage"
          />
        </div>
      </template>
    </section>
    <BlogImportPanel />
  </div>
</template>
