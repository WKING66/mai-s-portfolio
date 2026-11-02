<script setup lang="ts">
defineProps<{ loading: boolean; error?: string }>()
defineEmits<{ retry: [] }>()
</script>
<template>
  <div v-if="loading" role="status" aria-label="正在读取列表" class="space-y-5 p-6 sm:p-8">
    <USkeleton v-for="row in 4" :key="row" class="h-12 w-full rounded-lg" />
  </div>
  <div v-else class="grid min-h-64 justify-items-center content-center gap-4 p-6 text-center">
    <UIcon :name="error ? 'i-lucide-cloud-alert' : 'i-lucide-inbox'" class="size-10 text-muted" />
    <p :role="error ? 'alert' : 'status'" class="text-sm text-muted">
      {{ error || '暂无数据，请稍后再试' }}
    </p>
    <UButton
      v-if="error"
      color="neutral"
      variant="outline"
      icon="i-lucide-refresh-cw"
      @click="$emit('retry')"
      >重新读取</UButton
    >
  </div>
</template>
