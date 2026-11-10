<script setup lang="ts">
import { useAgentSettingsApi } from '../../api/agentSettings'
import type { AgentRuntime } from '../../api/agentSettings'

const api = useAgentSettingsApi()
const revision = ref<number | null>(null)
const persona = ref('')
const chatKey = ref('')
const embeddingKey = ref('')
const chatKeyConfigured = ref(false)
const embeddingKeyConfigured = ref(false)
const runtime = ref<AgentRuntime>({})
const original = ref<AgentRuntime>({})
const pending = ref(false)
const error = ref('')
const saved = ref(false)

const groups = [
  {
    title: '模型参数',
    fields: {
      chat_base_url: 'Chat API Base URL',
      chat_model: '对话模型',
      embedding_base_url: 'Embedding API Base URL',
      embedding_model: '向量模型',
      embedding_dimensions: '向量维度',
      model_timeout_seconds: '请求超时（秒）',
      chat_max_output_tokens: '对话输出 Token 上限',
      chat_billable_input_limit: '对话计费输入上限',
      embedding_billable_input_limit: '向量计费输入上限',
    },
  },
  {
    title: '费用与预算',
    fields: {
      ai_daily_budget: '每日预算',
      billing_currency: '币种',
      billing_time_zone: '计费时区',
      price_version: '价格版本',
      chat_input_price_per_million: '对话输入每百万 Token 单价',
      chat_output_price_per_million: '对话输出每百万 Token 单价',
      embedding_input_price_per_million: '向量输入每百万 Token 单价',
    },
  },
]

async function update(save = false) {
  if (pending.value || (save && revision.value === null)) {
    return
  }
  pending.value = true
  saved.value = false
  error.value = ''
  try {
    const changed = Object.fromEntries(
      Object.entries(runtime.value).filter(
        ([key, value]) => String(value) !== String(original.value[key]),
      ),
    )
    const result =
      save && revision.value !== null
        ? await api.save(revision.value, persona.value, changed, {
            chatApiKey: chatKey.value || undefined,
            embeddingApiKey: embeddingKey.value || undefined,
          })
        : await api.read()
    if (!result) {
      throw new Error('配置中心没有返回有效结果。')
    }
    revision.value = result.revision
    persona.value = result.persona
    chatKeyConfigured.value = result.chatKeyConfigured
    embeddingKeyConfigured.value = result.embeddingKeyConfigured
    chatKey.value = ''
    embeddingKey.value = ''
    runtime.value = { ...result.runtime }
    original.value = result.runtime
    saved.value = save
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '配置中心暂不可用。'
  } finally {
    pending.value = false
  }
}

onMounted(() => update())
</script>

<template>
  <form class="space-y-6" @submit.prevent="update(true)">
    <p v-if="error" role="alert" class="text-error">{{ error }}</p>
    <p v-if="saved" role="status">配置已保存并生效。</p>
    <p v-if="revision === null && pending" role="status">正在读取配置…</p>
    <fieldset v-if="revision !== null" :disabled="pending" class="space-y-6">
      <p class="text-sm text-muted">当前修订 {{ revision }}</p>
      <UFormField label="回答风格">
        <UTextarea v-model="persona" class="w-full" :maxlength="2000" required />
      </UFormField>
      <section v-for="group in groups" :key="group.title" class="space-y-3">
        <h2 class="font-semibold">{{ group.title }}</h2>
        <div class="grid gap-4 sm:grid-cols-2">
          <UFormField v-for="(label, key) in group.fields" :key="key" :label="label">
            <UInput
              :model-value="String(runtime[key] ?? '')"
              class="w-full"
              @update:model-value="runtime[key] = String($event)"
            />
          </UFormField>
        </div>
      </section>
      <p class="text-sm text-muted">
        填写供应商的 HTTPS API 基础地址。更换地址时请关闭收费，并填写新供应商的密钥。
      </p>
      <section class="grid gap-4 sm:grid-cols-2">
        <UFormField
          label="对话 API 密钥"
          :description="chatKeyConfigured ? '已配置，留空保留' : '尚未配置'"
        >
          <UInput v-model="chatKey" type="password" autocomplete="new-password" class="w-full" />
        </UFormField>
        <UFormField
          label="向量 API 密钥"
          :description="embeddingKeyConfigured ? '已配置，留空保留' : '尚未配置'"
        >
          <UInput
            v-model="embeddingKey"
            type="password"
            autocomplete="new-password"
            class="w-full"
          />
        </UFormField>
      </section>
      <label class="flex items-center gap-2">
        <input v-model="runtime.ai_paid_calls_enabled" type="checkbox" />
        开启真实模型收费调用
      </label>
      <p class="text-sm text-muted">开启前须核对供应商计价、单次费用上界和批准预算。</p>
    </fieldset>
    <div class="flex gap-3">
      <UButton type="submit" :disabled="revision === null || pending" :loading="pending"
        >保存配置</UButton
      >
      <UButton type="button" variant="outline" :disabled="pending" @click="update()"
        >重新读取</UButton
      >
    </div>
  </form>
</template>
