<script setup lang="ts">
import { BLOG_PATH, type BlogPage } from '../../api/blogs'
import type { ApiResponse } from '../../api/types'
import {
  BLOG_FIRST_PAGE,
  BLOG_MAX_PAGE_NUMBER,
  BLOG_PUBLIC_PAGE_SIZE,
  BLOG_PUBLIC_CACHE_PREFIX,
  BLOG_VIEW,
} from '../../constants/blogs'
const route = useRoute()
const router = useRouter()
const keyword = computed(() =>
  typeof route.query.keyword === 'string' ? route.query.keyword.trim().slice(0, 50) : '',
)
const searchDraft = ref(keyword.value)
watch(keyword, (value) => {
  searchDraft.value = value
})
const currentPage = computed({
  get: () => {
    const pageNumber = Number(route.query.page ?? BLOG_FIRST_PAGE)
    return Number.isSafeInteger(pageNumber) &&
      pageNumber >= BLOG_FIRST_PAGE &&
      pageNumber <= BLOG_MAX_PAGE_NUMBER
      ? pageNumber
      : BLOG_FIRST_PAGE
  },
  set: (pageNumber) => {
    void router.replace({
      query: {
        keyword: keyword.value || undefined,
        page: pageNumber === BLOG_FIRST_PAGE ? undefined : String(pageNumber),
      },
    })
  },
})
function search() {
  void router.replace({ query: { keyword: searchDraft.value.trim() || undefined } })
}
function resetSearch() {
  searchDraft.value = ''
  search()
}
// 搜索与翻页复用公开版本接口；查询条件写入 URL，刷新/返回可恢复。
const {
  data: blogListResponse,
  error: blogListError,
  status,
  refresh: refreshBlogPosts,
} = await useFetch<ApiResponse<BlogPage>>(BLOG_PATH, {
  key: () =>
    BLOG_PUBLIC_CACHE_PREFIX + 'list:' + JSON.stringify([currentPage.value, keyword.value]),
  query: {
    view: BLOG_VIEW.PUBLIC,
    page: currentPage,
    size: BLOG_PUBLIC_PAGE_SIZE,
    keyword: computed(() => keyword.value || undefined),
  },
})
const items = computed(() => blogListResponse.value?.data?.items ?? [])
usePortfolioSeo({
  title: () => '博客笔记',
  description: () => '技术实践、项目复盘与学习记录',
  canonicalPath: () =>
    '/blog' + (currentPage.value === BLOG_FIRST_PAGE ? '' : '?page=' + currentPage.value),
  available: () => !blogListError.value && Boolean(items.value.length),
})
</script>
<template>
  <main class="site-container site-page journal-page">
    <header class="journal-intro">
      <p class="eyebrow">笔记 / JOURNAL</p>
      <div class="journal-heading">
        <h1>学习中的笔记。</h1>
        <span v-if="blogListResponse?.data" class="journal-count"
          >{{ blogListResponse.data.total }} 篇文章</span
        >
      </div>
      <p class="journal-description">记录开发实践、项目里遇到的问题，还有学习过程中的想法。</p>
    </header>
    <div class="journal-toolbar">
      <p>{{ keyword ? '搜索结果' : '全部文章' }}<span> · 最新发布在前</span></p>
      <form role="search" aria-label="搜索博客文章" @submit.prevent="search">
        <UInput
          v-model="searchDraft"
          type="search"
          name="keyword"
          icon="i-lucide-search"
          placeholder="搜索标题或 Slug…"
          aria-label="搜索标题或 Slug"
          :maxlength="50"
          class="journal-search"
        />
        <UButton
          type="submit"
          icon="i-lucide-arrow-right"
          color="neutral"
          variant="ghost"
          aria-label="搜索文章"
          :loading="status === 'pending'"
        />
        <UButton
          v-if="keyword"
          type="button"
          icon="i-lucide-x"
          color="neutral"
          variant="ghost"
          aria-label="清除搜索"
          @click="resetSearch"
        />
      </form>
    </div>
    <section class="journal-results" aria-label="博客文章列表" :aria-busy="status === 'pending'">
      <p v-if="status === 'pending'" role="status" class="journal-loading">
        <UIcon name="i-lucide-loader-circle" class="animate-spin" />正在读取文章…
      </p>
      <UAlert
        v-if="blogListError"
        title="文章读取失败"
        description="请稍后重试。"
        color="error"
        variant="soft"
        :actions="[{ label: '重试', onClick: () => refreshBlogPosts() }]"
        class="my-8"
      />
      <template v-else-if="items.length">
        <NuxtLink
          v-for="article in items"
          :key="article.id"
          :to="'/blog/' + encodeURIComponent(article.slug ?? '')"
          class="journal-entry"
        >
          <div class="journal-entry-copy">
            <h2>{{ article.title }}</h2>
            <p v-if="article.summary">{{ article.summary }}</p>
          </div>
          <div class="journal-entry-meta">
            <time v-if="article.publishedAt" :datetime="article.publishedAt">{{
              article.publishedAt.slice(0, 10)
            }}</time>
            <span class="journal-entry-arrow" aria-hidden="true">↗</span>
          </div>
        </NuxtLink>
      </template>
      <div v-else-if="status !== 'pending'" class="journal-empty">
        <h2>
          {{
            keyword
              ? '没有找到相关文章'
              : currentPage > 1
                ? '这一页暂时没有文章'
                : '还没有公开的文章'
          }}
        </h2>
        <p>
          {{
            keyword
              ? '试试其他标题关键词，或返回全部文章。'
              : currentPage > 1
                ? '返回第一页查看最新文章。'
                : '新的笔记会发布在这里。'
          }}
        </p>
        <UButton
          v-if="keyword || currentPage > 1"
          color="neutral"
          variant="outline"
          @click="resetSearch"
          >查看全部文章</UButton
        >
      </div>
    </section>
    <nav
      v-if="blogListResponse?.data && blogListResponse.data.total > BLOG_PUBLIC_PAGE_SIZE"
      aria-label="文章分页"
      class="journal-pagination"
    >
      <span>第 {{ currentPage }} 页</span>
      <UPagination
        v-model:page="currentPage"
        :total="blogListResponse.data.total"
        :items-per-page="BLOG_PUBLIC_PAGE_SIZE"
        :sibling-count="1"
      />
    </nav>
  </main>
</template>
<style scoped>
.journal-page {
  max-width: calc(72.5rem + var(--site-gutter) * 2);
}
.journal-intro {
  padding-block: 1rem 3.5rem;
}
.journal-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1.5rem;
  margin-top: 1rem;
}
.journal-heading h1 {
  font-family: var(--site-editorial-font);
  font-size: clamp(2.5rem, 5vw, 4.5rem);
  font-weight: 300;
  line-height: 1.3;
  text-wrap: balance;
}
.journal-count {
  flex-shrink: 0;
  border: 1px solid var(--site-line);
  border-radius: 999px;
  padding: 0.5rem 0.85rem;
  font-size: 0.8rem;
  color: var(--site-muted);
}
.journal-description {
  text-wrap: pretty;
  margin-top: 1.75rem;
  max-width: 50ch;
  font-size: 1.125rem;
  line-height: 1.8;
  color: var(--site-muted);
}
.journal-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  border-block: 1px solid var(--site-line);
  padding-block: 0.85rem;
}
.journal-toolbar > p {
  font-size: 0.875rem;
}
.journal-toolbar > p > span {
  color: var(--site-muted);
}
.journal-toolbar form {
  display: flex;
  align-items: center;
  gap: 0.25rem;
}
.journal-search {
  width: 17rem;
}
.journal-results {
  position: relative;
  min-height: 18rem;
}
.journal-loading {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding-block: 1rem;
  color: var(--site-muted);
  font-size: 0.875rem;
}
.journal-entry {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 3rem;
  padding: 2.25rem 0;
  border-bottom: 1px solid var(--site-line);
  transition: background-color 180ms ease;
}
.journal-entry-copy {
  max-width: 48rem;
  transition: transform 180ms ease;
}
.journal-entry h2 {
  font-family: var(--site-editorial-font);
  font-size: clamp(1.5rem, 2.3vw, 2rem);
  line-height: 1.45;
  font-weight: 400;
  overflow-wrap: anywhere;
  transition: color 180ms ease;
}
.journal-entry-copy > p {
  margin-top: 0.85rem;
  font-size: 1rem;
  line-height: 1.85;
  color: var(--site-muted);
  overflow-wrap: anywhere;
}
.journal-entry-meta {
  display: flex;
  align-items: center;
  gap: 1.5rem;
  padding-top: 0.45rem;
  color: var(--site-muted);
  font-size: 0.8rem;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.journal-entry-arrow {
  transition: transform 180ms ease;
}
.journal-entry:hover .journal-entry-copy {
  transform: translateX(0.25rem);
}
.journal-entry:hover h2 {
  color: var(--site-accent);
}
.journal-entry:hover .journal-entry-arrow {
  transform: translate(0.15rem, -0.15rem);
}
.journal-entry:focus-visible {
  outline: 2px solid var(--site-accent);
  outline-offset: 4px;
  border-radius: 0.25rem;
}
.journal-empty {
  padding-block: 4rem;
  text-align: center;
}
.journal-empty h2 {
  font-size: 1.25rem;
}
.journal-empty p {
  margin-block: 0.75rem 1.5rem;
  color: var(--site-muted);
}
.journal-pagination {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  margin-top: 2rem;
  font-size: 0.875rem;
  color: var(--site-muted);
}
@media (max-width: 600px) {
  .journal-intro {
    padding-block: 0.5rem 2.25rem;
  }
  .journal-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 1rem;
  }
  .journal-description {
    text-wrap: pretty;
    font-size: 1rem;
    margin-top: 1.25rem;
  }
  .journal-toolbar form {
    width: 100%;
  }
  .journal-search {
    min-width: 0;
    flex: 1;
    width: auto;
  }
  .journal-entry {
    grid-template-columns: 1fr;
    gap: 1rem;
    padding-block: 1.75rem;
  }
  .journal-entry-copy > p {
    font-size: 0.9375rem;
  }
  .journal-entry-meta {
    justify-content: space-between;
    padding: 0;
  }
}
@media (prefers-reduced-motion: reduce) {
  .journal-entry,
  .journal-entry-copy,
  .journal-entry h2,
  .journal-entry-arrow {
    transition: none;
  }
  .journal-entry:hover .journal-entry-copy,
  .journal-entry:hover .journal-entry-arrow {
    transform: none;
  }
}
</style>
