<script setup lang="ts">
import type { AgentModel, AgentProvider } from '../../api/agentSettings'
import { useAgentSettingsApi } from '../../api/agentSettings'

const props = defineProps<{ model?: AgentModel; provider: AgentProvider }>()
const emit = defineEmits<{ saved: []; cancel: [] }>()
const api = useAgentSettingsApi()
const nickname = ref(props.model?.modelNickname ?? '')
const modelKey = ref(props.model?.modelKey ?? '')
const type = ref<1 | 2>(props.model?.modelType ?? 1)
const maxOutputTokens = ref(props.model?.config.maxOutputTokens ?? 1024)
const dimensions = ref<number | undefined>(props.model?.config.dimensions ?? undefined)
const isActive = ref(props.model?.isActive ?? false)
const pending = ref(false)
const error = ref('')
const signature = () =>
  JSON.stringify([
    nickname.value,
    modelKey.value,
    type.value,
    type.value === 1 ? maxOutputTokens.value : dimensions.value,
    isActive.value,
  ])
const original = signature()
const dirty = computed(() => signature() !== original)
defineExpose({ dirty, pending })
const types = [
  { label: 'Chat · 对话', value: 1 },
  { label: 'Embedding · 向量', value: 2 },
]
async function save() {
  if (pending.value || (props.model && !dirty.value)) {
    return
  }
  pending.value = true
  error.value = ''
  try {
    const result = await api.saveModel(
      {
        modelProviderId: props.provider.id,
        modelNickname: nickname.value.trim(),
        modelKey: modelKey.value.trim(),
        modelType: type.value,
        config:
          type.value === 1
            ? { maxOutputTokens: Number(maxOutputTokens.value) }
            : { dimensions: dimensions.value ? Number(dimensions.value) : null },
        isActive: isActive.value,
        expectedRevision: props.model?.revision,
      },
      props.model?.id,
    )
    if (!result) {
      throw new Error('模型未保存，请稍后重试。')
    }
    emit('saved')
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '模型未保存，输入已保留。'
  } finally {
    pending.value = false
  }
}
</script>
<template>
  <form class="space-y-5" @submit.prevent="save">
    <p class="text-sm text-muted">{{ provider.title }}</p>
    <UAlert v-if="error" color="error" variant="subtle" :description="error" />
    <fieldset :disabled="pending" class="space-y-4">
      <UFormField label="显示名称" required
        ><UInput v-model="nickname" required :maxlength="100" class="w-full"
      /></UFormField>
      <UFormField label="模型 ID" required description="填写供应商接口使用的模型标识。"
        ><UInput v-model="modelKey" required :maxlength="200" class="w-full"
      /></UFormField>
      <UFormField label="模型能力"
        ><USelect v-model="type" :items="types" class="w-full"
      /></UFormField>
      <UFormField v-if="type === 1" label="最大输出 Token"
        ><UInput
          v-model="maxOutputTokens"
          type="number"
          :min="1"
          :max="32767"
          required
          class="w-full"
      /></UFormField>
      <UFormField v-else label="输出维度" description="加入执行链前需明确有效输出维度。"
        ><UInput v-model="dimensions" type="number" :min="1" class="w-full"
      /></UFormField>
      <USwitch v-model="isActive" label="启用此模型" description="加入执行链后才会参与站点请求。" />
    </fieldset>
    <div class="flex justify-end gap-2">
      <UButton variant="ghost" color="neutral" :disabled="pending" @click="emit('cancel')"
        >取消</UButton
      ><UButton type="submit" :loading="pending" :disabled="!!model && !dirty">保存模型</UButton>
    </div>
  </form>
</template>
