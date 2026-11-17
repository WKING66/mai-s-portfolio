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
const pending = ref(false)
const error = ref('')
const pricesConfirmed = ref(false)

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

const priceGroups = [
  {
    address: 'chat_base_url',
    model: 'chat_model',
    fields: ['chat_input_price_per_million', 'chat_output_price_per_million'],
  },
  {
    address: 'embedding_base_url',
    model: 'embedding_model',
    fields: ['embedding_input_price_per_million'],
  },
] as const

const affectedPrices = computed(() => {
  const differs = (key: string) => String(runtime.value[key]) !== String(original.value[key])
  return priceGroups
    .filter(
      ({ address, model }) =>
        runtime.value[address] &&
        runtime.value[model] &&
        (differs(address) || differs(model) || differs('billing_currency')),
    )
    .flatMap(({ fields }) => fields)
})

watch(runtime, () => {
  pricesConfirmed.value = false
}, { deep: true, flush: 'sync' })

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
    // 切换模型时需要完整报价，即使用户确认的金额和上一套相同。
    if (save && affectedPrices.value.length) {
      if (!pricesConfirmed.value) {
        throw new Error('请确认受影响模型的单价后再保存。')
      }
      for (const key of affectedPrices.value) {
        changed[key] = runtime.value[key] ?? ''
      }
    }
    // 报价被修改或重新确认时生成新版本，普通保存保留原版本。
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
        基础地址。更换地址来源时请重新填写对应密钥。金额预算由 Spring 配置。
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
      <div v-if="affectedPrices.length" class="space-y-2">
        <p class="text-sm">请核对以下每百万 Token 单价（{{ runtime.billing_currency || '币种未填写' }}）。如需修改，可在下方计价设置中填写。</p>
        <ul class="list-disc ps-5 text-sm">
          <li v-for="key in affectedPrices" :key="key">
            {{ prices[key] }} {{ runtime[key] || '未填写' }}
          </li>
        </ul>
        <UCheckbox v-model="pricesConfirmed" label="确认使用上述单价（相同价格可直接沿用）" />
      </div>
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
