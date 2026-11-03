<script setup lang="ts">
defineProps<{
  title: string
  summary?: string | null
  publishedAt?: string | null
  updatedAt?: string | null
}>()
defineEmits<{ share: [] }>()
const dateFormatter = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'long', timeZone: 'UTC' })
function displayDate(value: string) {
  // 保留接口日期部分，避免带时区的时间戳转换后跨日。
  const date = new Date(value.slice(0, 10) + 'T00:00:00Z')
  return Number.isNaN(date.getTime()) ? value.slice(0, 10) : dateFormatter.format(date)
}
</script>

<template>
  <header class="article-intro" aria-labelledby="article-title">
    <h1 id="article-title">{{ title }}</h1>
    <p v-if="summary" class="article-summary">{{ summary }}</p>
    <div class="article-intro-footer">
      <dl class="article-metadata">
        <div v-if="publishedAt">
          <dt>发布</dt>
          <dd>
            <time :datetime="publishedAt">{{ displayDate(publishedAt) }}</time>
          </dd>
        </div>
        <div v-if="updatedAt && updatedAt.slice(0, 10) !== publishedAt?.slice(0, 10)">
          <dt>更新</dt>
          <dd>
            <time :datetime="updatedAt">{{ displayDate(updatedAt) }}</time>
          </dd>
        </div>
      </dl>
      <UButton
        color="neutral"
        variant="ghost"
        icon="i-lucide-share-2"
        aria-label="复制文章链接"
        @click="$emit('share')"
      />
    </div>
  </header>
</template>

<style scoped>
/* 留白跟随内容，不把短标题和日期强行分散到屏幕两端。 */
.article-intro {
  padding-top: 2rem;
}
.article-intro h1 {
  font-family: var(--site-editorial-font);
  font-size: clamp(2.5rem, 5.75vw, 5.5rem);
  font-weight: 400;
  line-height: 1.35;
  letter-spacing: normal;
  text-wrap: balance;
  overflow-wrap: anywhere;
}
.article-summary {
  max-width: 50rem;
  margin-top: 1.75rem;
  font-size: 1.25rem;
  line-height: 1.65;
  color: var(--site-muted);
  overflow-wrap: anywhere;
}
.article-intro-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1.5rem;
  margin-top: 2.5rem;
  border-block: 1px solid var(--site-line);
  padding-block: 1.5rem;
}
.article-metadata {
  display: flex;
  flex-wrap: wrap;
  gap: 1.5rem 3rem;
  font-size: 0.9375rem;
}
.article-metadata dt {
  margin-bottom: 0.5rem;
  font-size: 0.75rem;
  color: var(--site-muted);
}
@media (max-width: 600px) {
  .article-intro {
    padding-top: 1rem;
  }
  .article-intro h1 {
    font-size: clamp(2rem, 9.66vw, 2.5rem);
    line-height: 1.35;
  }
  .article-summary {
    font-size: 1rem;
    line-height: 1.75;
  }
  .article-intro-footer {
    padding-block: 1.25rem;
    gap: 0.75rem;
  }
  .article-metadata {
    font-size: 0.875rem;
    gap: 1.25rem;
  }
}
</style>
