<script setup lang="ts">
defineProps<{ open: boolean }>()
const emit = defineEmits<{ decision: [leave: boolean] }>()
</script>
<template>
  <UModal
    :open="open"
    title="放弃未保存的修改？"
    description="尚未保存的输入将丢失，已保存内容不会改变。"
    @update:open="!$event && emit('decision', false)"
  >
    <template #footer>
      <div class="flex w-full justify-end gap-2">
        <UButton color="neutral" variant="outline" @click="emit('decision', false)"
          >继续编辑</UButton
        >
        <UButton color="error" @click="emit('decision', true)">放弃修改</UButton>
      </div>
    </template>
  </UModal>
</template>
