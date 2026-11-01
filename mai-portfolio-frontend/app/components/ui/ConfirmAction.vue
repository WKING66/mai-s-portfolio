<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    label: string
    icon?: string
    disabledReason?: string
    title: string
    description: string
    pending?: boolean
    disabled?: boolean
    destructive?: boolean
    action: () => Promise<boolean>
  }>(),
  { pending: false, disabled: false, destructive: false, icon: undefined, disabledReason: '' },
)
const open = ref(false)
const error = ref('')
const confirming = ref(false)
async function confirm() {
  if (confirming.value || props.pending || props.disabled) {
    return
  }
  confirming.value = true
  error.value = ''
  try {
    if (await props.action()) {
      open.value = false
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '操作未完成，请重试。'
  } finally {
    confirming.value = false
  }
}
</script>
<template>
  <UPopover
    v-model:open="open"
    :dismissible="!confirming"
    :content="{ align: 'end', collisionPadding: 16 }"
    :ui="{ content: 'w-80 max-w-[calc(100vw-2rem)] p-4' }"
  >
    <UButton
      type="button"
      :icon="icon"
      :aria-label="label"
      :title="disabledReason || label"
      :color="destructive ? 'error' : 'primary'"
      variant="soft"
      size="sm"
      class="justify-center"
      :disabled="disabled || pending"
      :loading="confirming"
      >{{ icon ? undefined : label }}</UButton
    >
    <template #content>
      <div role="group" :aria-label="title" class="space-y-3">
        <p class="font-semibold">{{ title }}</p>
        <p class="text-sm leading-6 text-muted">{{ description }}</p>
        <p v-if="error" role="alert" class="text-sm text-error">{{ error }}</p>
        <div class="flex justify-end gap-2">
          <UButton
            color="neutral"
            variant="ghost"
            size="sm"
            :disabled="confirming"
            @click="open = false"
            >取消</UButton
          >
          <UButton
            :color="destructive ? 'error' : 'primary'"
            size="sm"
            :loading="confirming"
            :disabled="pending || disabled"
            @click="confirm"
            >确认{{ label }}</UButton
          >
        </div>
      </div>
    </template>
  </UPopover>
</template>
