<script setup lang="ts">
import type { BlogCard } from '../../api/blogs'
import { blogDeletionBlocker } from '../../utils/blog/lifecycle'
import { ADMIN_PATHS } from '../../constants/admin'
defineProps<{
  article: BlogCard
  pending: boolean
  transition: (article: BlogCard) => Promise<boolean>
  remove: (article: BlogCard) => Promise<boolean>
}>()
defineEmits<{ properties: [id: number] }>()
</script>
<template>
  <div class="admin-row-actions">
    <UButton
      :to="`${ADMIN_PATHS.blogs}/${article.id}`"
      color="neutral"
      variant="outline"
      size="sm"
      class="justify-center"
      :disabled="pending"
      >正文</UButton
    >
    <UButton
      color="neutral"
      variant="outline"
      size="sm"
      class="justify-center"
      :disabled="pending"
      @click="$emit('properties', article.id)"
      >属性</UButton
    >
    <UiConfirmAction
      :label="article.status === 'PUBLISHED' ? '下架' : '发布'"
      :title="article.status === 'PUBLISHED' ? '下架文章？' : '发布已保存草稿？'"
      :description="
        article.status === 'PUBLISHED'
          ? '关闭公开入口，保留正式正文和工作草稿。'
          : '将已保存的草稿作为公开正文，请确认内容和资源已经准备完成。'
      "
      :pending="pending"
      :disabled="article.status !== 'PUBLISHED' && !article.hasDraft"
      :action="() => transition(article)"
    />
    <UiConfirmAction
      label="删除"
      icon="i-lucide-trash-2"
      destructive
      :title="`删除文章「${article.title || '未命名草稿'}」？`"
      description="将永久删除整篇文章及草稿和关联关系，无法恢复；共享图片与附件文件会保留。"
      :pending="pending"
      :disabled="!!blogDeletionBlocker(article)"
      :disabled-reason="blogDeletionBlocker(article)"
      :action="() => remove(article)"
    />
  </div>
</template>
