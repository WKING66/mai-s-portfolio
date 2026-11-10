<script setup lang="ts">
import { useAgentSettingsApi } from '../../api/agentSettings'
import type { AgentRuntime } from '../../api/agentSettings'
import { useFeedback } from '../../composables/useFeedback'

const api = useAgentSettingsApi()
const feedback = useFeedback()
const revision = ref<number | null>(null)
const featureEnabled = ref(false)
const persona = ref('')
const chatKey = ref('')
const embeddingKey = ref('')
const chatKeyConfigured = ref(false)
const embeddingKeyConfigured = ref(false)
const runtime = ref<AgentRuntime>({})
const original = ref<AgentRuntime>({})
const supportsUnlimited = computed(
  () => typeof original.value.ai_daily_budget_unlimited === 'boolean',
)
const unlimited = computed(() => runtime.value.ai_daily_budget_unlimited === true)
const pending = ref(false)
const error = ref('')

const groups = [
  {
    title: '模型参数',
    fields: {
      chat_base_url: 'Chat API Base URL',
      chat_model: '对话模型',
      embedding_base_url: 'Embedding API Base URL',
      embedding_model: '向量模型',
      embedding_dimensions: '向量维度',
    },
  },
]

const prices = {
  chat_input_price_per_million: '对话输入最高单价',
  chat_output_price_per_million: '对话输出最高单价',
  embedding_input_price_per_million: '向量输入最高单价',
}

const advanced = {
  billing_currency: '计价币种',
  model_timeout_seconds: '请求超时（秒）',
  chat_max_output_tokens: '对话输出 Token 上限',
  chat_billable_input_limit: '对话计费输入上限（Token）',
  embedding_billable_input_limit: '向量计费输入上限（Token）',
  ...prices,
}

async function update(save = false) {
  if (pending.value || (save && revision.value === null)) {
    return
  }
  pending.value = true
  error.value = ''
  try {
    const changed = Object.fromEntries(
      Object.entries(runtime.value).filter(
        ([key, value]) => String(value) !== String(original.value[key]),
      ),
    )
    // 价格版本由修改价格的操作产生。保留旧版本，避免普通保存改写费用来源。
    if (
      save &&
      (Object.keys(prices).some((key) => key in changed) || !original.value.price_version)
    ) {
      changed.price_version = `manual-${new Date().toISOString()}`
    }
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
    featureEnabled.value = result.featureEnabled
    persona.value = result.persona
    chatKeyConfigured.value = result.chatKeyConfigured
    embeddingKeyConfigured.value = result.embeddingKeyConfigured
    chatKey.value = ''
    embeddingKey.value = ''
    runtime.value = {
      ...result.runtime,
      billing_currency: result.runtime.billing_currency || 'CNY',
    }
    original.value = result.runtime
    if (save) {
      feedback.success('数字人配置已保存。')
    }
  } catch (failure) {
    const reason = failure instanceof Error ? failure.message : '配置中心暂不可用。'
    error.value = save ? `保存失败，输入已保留。${reason}` : reason
    if (save) {
      feedback.error(error.value)
    }
  } finally {
    pending.value = false
  }
}

onMounted(() => update())
</script>

<template>
  <form class="space-y-6" @submit.prevent="update(true)">
    <p v-if="error" role="alert" class="text-error">{{ error }}</p>
    <p v-if="revision === null && pending" role="status">正在读取配置…</p>
    <fieldset v-if="revision !== null" :disabled="pending" class="space-y-6">
      <p class="text-sm text-muted">
        网站 AI 功能{{ featureEnabled ? '已开启' : '已关闭' }} · 当前修订 {{ revision }}
      </p>
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
        填写供应商的 HTTPS API
        基础地址。更换地址时请填写新供应商密钥。保存后预算归零，核价后再设置。
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
      <label v-if="supportsUnlimited" class="flex items-center gap-2">
        <input v-model="runtime.ai_daily_budget_unlimited" type="checkbox" />
        不限每日成本
      </label>
      <p v-if="unlimited" class="text-sm text-muted">继续记录每次调用费用，本站不限制每日总额。</p>
      <UFormField
        v-else
        :label="`每日预算（${runtime.billing_currency || 'CNY'}）`"
        description="填 0 暂停模型调用。正数是本站每日费用硬上限。"
      >
        <UInput
          :model-value="String(runtime.ai_daily_budget ?? '0')"
          class="w-full sm:max-w-xs"
          @update:model-value="runtime.ai_daily_budget = String($event)"
        />
      </UFormField>
      <details class="space-y-3 rounded border border-default p-4">
        <summary class="cursor-pointer font-medium">高级设置 · 请求限制与计价</summary>
        <p class="text-sm text-muted">
          价格按每百万 Token 填写。实时调用预占使用最高适用价；币种应与供应商实际报价一致。
        </p>
        <div class="grid gap-4 sm:grid-cols-2">
          <UFormField v-for="(label, key) in advanced" :key="key" :label="label">
            <UInput
              :model-value="String(runtime[key] ?? '')"
              class="w-full"
              @update:model-value="runtime[key] = String($event)"
            />
          </UFormField>
        </div>
      </details>
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
