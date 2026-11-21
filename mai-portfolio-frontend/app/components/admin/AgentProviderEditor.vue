<script setup lang="ts">
import type { AgentProvider } from '../../api/agentSettings'
import { useAgentSettingsApi } from '../../api/agentSettings'
const props = defineProps<{ provider?: AgentProvider }>()
const emit = defineEmits<{ saved: []; cancel: [] }>()
const api = useAgentSettingsApi()
const title = ref(props.provider?.title ?? '')
const providerCode = ref(props.provider?.providerCode ?? '')
const baseUrl = ref(props.provider?.baseUrl ?? '')
const walletKey = ref(props.provider?.walletKey ?? '')
const apiKey = ref('')
const pending = ref(false)
const error = ref('')
const original = JSON.stringify([title.value, providerCode.value, baseUrl.value, walletKey.value])
const dirty = computed(
  () =>
    Boolean(apiKey.value) ||
    JSON.stringify([title.value, providerCode.value, baseUrl.value, walletKey.value]) !== original,
)
defineExpose({ dirty, pending })
async function save() {
  if (pending.value || (props.provider && !dirty.value)) {
    return
  }
  pending.value = true
  error.value = ''
  try {
    const result = await api.saveProvider(
      {
        title: title.value.trim(),
        providerCode: providerCode.value.trim(),
        apiProtocol: 1,
        baseUrl: baseUrl.value.trim(),
        walletKey: walletKey.value.trim() || null,
        apiKey: apiKey.value || undefined,
        expectedRevision: props.provider?.revision,
      },
      props.provider?.id,
    )
    if (!result) {
      throw new Error('供应商未保存，请稍后重试。')
    }
    apiKey.value = ''
    emit('saved')
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '供应商未保存，输入已保留。'
  } finally {
    pending.value = false
  }
}
onBeforeUnmount(() => {
  apiKey.value = ''
})
</script>
<template>
  <form class="space-y-5" @submit.prevent="save">
    <UAlert v-if="error" color="error" variant="subtle" :description="error" />
    <fieldset :disabled="pending" class="space-y-4">
      <UFormField label="卡片标题" required
        ><UInput
          v-model="title"
          required
          :maxlength="100"
          placeholder="例如百炼 · 个人账户"
          class="w-full"
      /></UFormField>
      <UFormField
        label="供应商标识"
        required
        description="同一计费平台使用相同标识，例如 bailian、deepseek。"
        ><UInput
          v-model="providerCode"
          required
          pattern="[a-z][a-z0-9_-]*"
          :maxlength="64"
          class="w-full"
      /></UFormField>
      <UFormField label="接口协议"
        ><UInput model-value="OpenAI 兼容协议" disabled class="w-full"
      /></UFormField>
      <UFormField label="API 地址" required
        ><UInput
          v-model="baseUrl"
          type="url"
          required
          placeholder="https://api.example.com/v1"
          :maxlength="2048"
          class="w-full"
      /></UFormField>
      <UFormField
        label="API 密钥"
        :description="
          provider?.keyConfigured ? '已配置。留空保留现有密钥，填写后替换。' : '尚未配置密钥。'
        "
        ><UInput
          v-model="apiKey"
          type="password"
          autocomplete="new-password"
          :maxlength="2048"
          class="w-full"
      /></UFormField>
      <UFormField label="钱包别名" description="共用计费账户的卡片填写相同别名，可选。"
        ><UInput v-model="walletKey" :maxlength="80" class="w-full"
      /></UFormField>
    </fieldset>
    <div class="flex justify-end gap-2">
      <UButton variant="ghost" color="neutral" :disabled="pending" @click="emit('cancel')"
        >取消</UButton
      ><UButton type="submit" :loading="pending" :disabled="!!provider && !dirty"
        >保存供应商</UButton
      >
    </div>
  </form>
</template>
