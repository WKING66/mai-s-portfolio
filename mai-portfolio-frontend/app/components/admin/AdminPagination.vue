<script setup lang="ts">
import { ADMIN_PAGE_SIZES } from '../../constants/admin'
const sizeOptions: { label: string; value: number }[] = ADMIN_PAGE_SIZES.map((value) => ({
  label: `${value} 条/页`,
  value,
}))
defineProps<{ page: number; size: number; total: number; disabled?: boolean }>()
defineEmits<{ page: [value: number]; size: [value: number] }>()
</script>
<template>
  <nav class="admin-pagination shrink-0" aria-label="管理列表分页">
    <p class="text-sm text-muted">共 {{ total }} 条 · 第 {{ page }} 页</p>
    <div class="flex flex-wrap items-center gap-3">
      <USelect
        :model-value="size"
        :items="sizeOptions"
        aria-label="每页条数"
        :disabled="disabled"
        class="w-28"
        @update:model-value="$emit('size', $event)"
      />
      <UPagination
        :page="page"
        :total="Math.max(total, (page - 1) * size + 1)"
        :items-per-page="size"
        :disabled="disabled"
        :sibling-count="1"
        @update:page="$emit('page', $event)"
      />
    </div>
  </nav>
</template>
