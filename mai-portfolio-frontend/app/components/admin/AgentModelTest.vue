<script setup lang="ts">
import { useAgentSettingsApi } from '../../api/agentSettings'
import type { AgentModel, AgentModelProbe } from '../../api/agentSettings'
const props = defineProps<{ model: AgentModel; providerRevision: number }>()
const api = useAgentSettingsApi()
const pending = ref(false)
const result = ref<AgentModelProbe>()
const error = ref('')
const reasons: Record<string, string> = {
  PROVIDER_KEY_REQUIRED: '请先配置供应商密钥。',
  PROVIDER_KEY_UNAVAILABLE: '供应商凭证暂不可用，请检查配置。',
  EMPTY_MODEL_RESPONSE: '接口已响应，但未返回有效内容。',
  EMBEDDING_DIMENSION_MISMATCH: '返回的向量维度与模型配置不一致。',
  MODEL_TIMEOUT: '模型未在测试时限内响应，请检查地址或稍后重试。',
  MODEL_CONNECTION_FAILED: '无法连接模型，请检查 API 地址。',
  MODEL_FREE_QUOTA_EXHAUSTED: '该模型免费额度已用尽。',
  MODEL_REQUEST_REJECTED: '供应商拒绝了测试请求，请检查模型 ID、密钥或可用额度。',
}
async function test() {
  if (pending.value) {
    return
  }
  pending.value = true
  error.value = ''
  result.value = undefined
  const revision = `${props.model.revision}:${props.providerRevision}`
  try {
    const response = await api.testModel(props.model.id)
    if (revision !== `${props.model.revision}:${props.providerRevision}`) {
      return
    }
    if (!response) {
      throw new Error('未收到测试结果，请重试。')
    }
    result.value = response
    if (!response.ok) {
      error.value = reasons[response.code ?? ''] ?? '模型测试失败，请检查接入配置后重试。'
    }
  } catch (failure) {
    if (revision === `${props.model.revision}:${props.providerRevision}`) {
      error.value = failure instanceof Error ? failure.message : '模型测试暂不可用。'
    }
  } finally {
    pending.value = false
  }
}
watch(
  () => `${props.model.revision}:${props.providerRevision}`,
  () => {
    result.value = undefined
    error.value = ''
  },
)
</script>
<template>
  <div class="flex flex-wrap items-center gap-2">
    <UButton
      variant="outline"
      color="neutral"
      size="xs"
      icon="i-lucide-flask-conical"
      :loading="pending"
      :aria-label="`测试模型 ${model.modelNickname}`"
      @click="test"
      >测试连接</UButton
    >
    <span v-if="result?.ok" role="status" class="text-xs text-success"
      >连接成功{{ result.dimensions ? ` · ${result.dimensions} 维` : '' }}</span
    >
    <span v-if="error" role="alert" class="text-xs text-error">{{ error }}</span>
  </div>
</template>
