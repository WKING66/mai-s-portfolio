<script setup lang="ts">
import {
  ADMIN_KEYWORD_MAX_LENGTH,
  ADMIN_STATUS_FILTERS,
  type AdminStatusFilter,
} from '../../constants/admin'
defineProps<{ keyword: string; status: AdminStatusFilter; pending: boolean; noun: string }>()
defineEmits<{
  keyword: [value: string]
  status: [value: AdminStatusFilter]
  search: []
  clear: []
}>()
const options = ADMIN_STATUS_FILTERS.map((item) => ({ ...item }))
</script>
<template>
  <form
    class="flex w-full min-w-0 flex-wrap items-center gap-3 lg:w-auto"
    role="search"
    :aria-label="noun + '列表查询'"
    @submit.prevent="$emit('search')"
  >
    <UInput
      :model-value="keyword"
      :maxlength="ADMIN_KEYWORD_MAX_LENGTH"
      icon="i-lucide-search"
      placeholder="搜索标题或 Slug"
      :aria-label="'搜索' + noun"
      :disabled="pending"
      class="w-full min-w-0 sm:w-64"
      @update:model-value="$emit('keyword', $event)"
    />
    <USelect
      :model-value="status"
      :items="options"
      :aria-label="noun + '状态'"
      :disabled="pending"
      class="w-32"
      @update:model-value="$emit('status', $event)"
    />
    <UButton type="submit" :loading="pending" :disabled="pending">查询</UButton>
    <UButton
      type="button"
      color="neutral"
      variant="ghost"
      :disabled="pending"
      @click="$emit('clear')"
      >清除</UButton
    >
  </form>
</template>
