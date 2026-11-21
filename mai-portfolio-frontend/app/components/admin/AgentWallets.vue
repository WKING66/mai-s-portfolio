<script setup lang="ts">
import { useAgentSettingsApi } from '../../api/agentSettings'
import type { AgentProvider, AgentWallet } from '../../api/agentSettings'
const props = defineProps<{ providers: AgentProvider[] }>()
const api = useAgentSettingsApi()
const wallets = ref<AgentWallet[]>([])
const pending = ref(false)
const loaded = ref(false)
const error = ref('')
const configurationChanged = ref(false)
const providerVersion = computed(() =>
  JSON.stringify(props.providers.map((provider) => [provider.id, provider.revision])),
)
watch(providerVersion, () => {
  wallets.value = []
  loaded.value = false
  configurationChanged.value = true
})
const labels = {
  available: '余额已更新',
  stale: '上次结果 · 已过期',
  unavailable: '查询失败',
  unsupported: '余额无法查询',
}
function providerNames(wallet: AgentWallet) {
  return props.providers
    .filter((provider) => wallet.providerIds.includes(provider.id))
    .map((provider) => provider.title)
    .join('、')
}
async function read() {
  if (pending.value) {
    return
  }
  pending.value = true
  error.value = ''
  const version = providerVersion.value
  try {
    const result = await api.wallets()
    if (!result) {
      throw new Error('未能读取供应商余额。')
    }
    if (version !== providerVersion.value) {
      return
    }
    wallets.value = result.wallets
    loaded.value = true
    configurationChanged.value = false
  } catch (failure) {
    if (version !== providerVersion.value) {
      return
    }
    error.value = failure instanceof Error ? failure.message : '钱包查询失败。'
    wallets.value = wallets.value.map((wallet) => ({
      ...wallet,
      status: wallet.balances.length ? 'stale' : wallet.status,
    }))
  } finally {
    pending.value = false
  }
}
onMounted(read)
</script>
<template>
  <section class="space-y-5">
    <div class="flex items-start justify-between gap-4">
      <div>
        <h2 class="font-semibold">供应商钱包</h2>
        <p class="mt-1 text-sm text-muted">按计费账户查看可用余额，共用钱包的卡片合并展示。</p>
      </div>
      <UButton
        variant="outline"
        color="neutral"
        icon="i-lucide-refresh-cw"
        :loading="pending"
        @click="read"
        >刷新余额</UButton
      >
    </div>
    <UAlert
      v-if="configurationChanged"
      color="info"
      variant="subtle"
      description="供应商配置已更新，请刷新余额。"
    />
    <UAlert
      v-if="error"
      color="warning"
      variant="subtle"
      title="余额查询未完成"
      :description="error"
    />
    <div
      v-if="pending && !loaded"
      class="grid gap-5 sm:grid-cols-2"
      role="status"
      aria-label="正在读取供应商余额"
    >
      <USkeleton v-for="index in 2" :key="index" class="h-48 rounded-xl" />
    </div>
    <div class="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
      <article
        v-for="(wallet, index) in wallets"
        :key="index"
        class="space-y-4 rounded-xl border border-default bg-default p-5"
      >
        <div class="flex items-start gap-3">
          <UIcon name="i-lucide-wallet" class="mt-1 size-5 shrink-0 text-primary" />
          <div class="min-w-0 flex-1">
            <h3 class="font-medium">
              {{ wallet.walletKey || providerNames(wallet) || wallet.providerCode }}
            </h3>
            <p class="mt-1 text-xs text-muted">{{ providerNames(wallet) }}</p>
          </div>
        </div>
        <UBadge :color="wallet.status === 'available' ? 'success' : 'neutral'" variant="subtle">{{
          labels[wallet.status]
        }}</UBadge>
        <div
          v-for="balance in wallet.balances"
          :key="balance.currency"
          class="flex items-baseline gap-2"
        >
          <strong class="text-2xl font-medium tabular-nums">{{ balance.totalBalance }}</strong
          ><span class="text-sm text-muted">{{ balance.currency }}</span>
        </div>
        <p v-if="wallet.status === 'unsupported'" class="text-sm text-muted">
          此接入尚未提供可用余额查询。
        </p>
        <p v-if="wallet.status === 'unavailable'" class="text-sm text-muted">
          暂未取得余额，请检查供应商配置后刷新。
        </p>
        <p v-if="wallet.queriedAt" class="text-xs text-muted">
          查询于 {{ new Date(wallet.queriedAt).toLocaleString('zh-CN') }}
        </p>
      </article>
    </div>
    <p
      v-if="loaded && !wallets.length"
      class="rounded-xl border border-dashed border-default p-10 text-center text-sm text-muted"
    >
      添加供应商后，可在这里查看钱包。
    </p>
  </section>
</template>
