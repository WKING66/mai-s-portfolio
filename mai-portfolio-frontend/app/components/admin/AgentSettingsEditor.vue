<script setup lang="ts">
import { useAgentSettingsApi } from '../../api/agentSettings'
import type { AgentModel, AgentModelCatalog, AgentProvider } from '../../api/agentSettings'
import AgentProviderEditor from './AgentProviderEditor.vue'
import AgentModelEditor from './AgentModelEditor.vue'
import AgentExecutionChain from './AgentExecutionChain.vue'
import AgentModelTest from './AgentModelTest.vue'
import AgentPromptEditor from './AgentPromptEditor.vue'
import AgentWallets from './AgentWallets.vue'
import AgentSiteSettings from './AgentSiteSettings.vue'
import { useAdminFormLeave } from '../../composables/useAdminFormLeave'
const providerForm = useTemplateRef('providerForm')
const modelForm = useTemplateRef('modelForm')
const chatChain = useTemplateRef('chatChain')
const embeddingChain = useTemplateRef('embeddingChain')
const promptEditor = useTemplateRef('promptEditor')
const siteSettings = useTemplateRef('siteSettings')
const forms = computed(() => [
  providerForm.value,
  modelForm.value,
  chatChain.value,
  embeddingChain.value,
  promptEditor.value,
  siteSettings.value,
])
const dirty = computed(() => forms.value.some((form) => form?.dirty))
const saving = computed(() => forms.value.some((form) => form?.pending))
const { leaveOpen, decideLeave } = useAdminFormLeave(dirty, saving)
const api = useAgentSettingsApi()
const catalog = shallowRef<AgentModelCatalog | null>(null)
const pending = ref(false)
const error = ref('')
let reloadRequested = false
let disposed = false
const tab = ref('providers')
const walletOpened = ref(false)
const siteOpened = ref(false)
watch(tab, (value) => {
  if (value === 'site') {
    siteOpened.value = true
  }
  if (value === 'wallets') {
    walletOpened.value = true
  }
})
const tabs = [
  { label: '供应商与模型', value: 'providers', icon: 'i-lucide-boxes' },
  { label: '执行链', value: 'routes', icon: 'i-lucide-workflow' },
  { label: 'Prompt', value: 'prompts', icon: 'i-lucide-text-cursor-input' },
  { label: '钱包', value: 'wallets', icon: 'i-lucide-wallet' },
  { label: '站点与限额', value: 'site', icon: 'i-lucide-sliders-horizontal' },
]
const providerOpen = ref(false)
const providerToEdit = shallowRef<AgentProvider>()
const modelToEdit = shallowRef<{ provider: AgentProvider; model?: AgentModel }>()
const modelOpen = computed({
  get: () => Boolean(modelToEdit.value),
  set: (open: boolean) => {
    if (!open) {
      modelToEdit.value = undefined
    }
  },
})
const modelsFor = (id: number) =>
  catalog.value?.models.filter((model) => model.modelProviderId === id) ?? []
async function read() {
  if (pending.value) {
    reloadRequested = true
    return
  }
  pending.value = true
  error.value = ''
  try {
    const result = await api.read()
    if (!result) {
      throw new Error('未能读取模型目录，请重试。')
    }
    if (!reloadRequested && !disposed) {
      catalog.value = result
    }
  } catch (failure) {
    if (!reloadRequested && !disposed) {
      error.value = failure instanceof Error ? failure.message : '配置中心暂不可用。'
    }
  } finally {
    pending.value = false
    if (reloadRequested && !disposed) {
      reloadRequested = false
      void read()
    }
  }
}
function editProvider(provider?: AgentProvider) {
  providerToEdit.value = provider
  providerOpen.value = true
}
async function providerSaved() {
  providerOpen.value = false
  await read()
}
async function modelSaved() {
  modelToEdit.value = undefined
  await read()
}
onMounted(read)
onBeforeUnmount(() => {
  disposed = true
})
</script>
<template>
  <div class="space-y-6">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div class="max-w-full min-w-0 overflow-x-auto">
        <UTabs v-model="tab" :items="tabs" :content="false" class="w-max" />
      </div>
      <div class="flex gap-2">
        <UButton
          v-if="tab === 'providers' || tab === 'routes'"
          color="neutral"
          variant="outline"
          icon="i-lucide-refresh-cw"
          :loading="pending"
          @click="read"
          >刷新目录</UButton
        ><UButton v-if="tab === 'providers'" icon="i-lucide-plus" @click="editProvider()"
          >添加供应商</UButton
        >
      </div>
    </div>
    <UAlert
      v-if="error && tab !== 'site'"
      color="error"
      variant="subtle"
      title="读取配置失败"
      :description="error"
      :actions="[{ label: '重试', onClick: read }]"
    />
    <div
      v-if="!catalog && pending && tab !== 'site'"
      class="grid gap-5 xl:grid-cols-2"
      role="status"
      aria-label="正在读取模型目录"
    >
      <USkeleton v-for="index in 2" :key="index" class="h-64 rounded-xl" />
    </div>
    <template v-if="catalog">
      <section v-show="tab === 'providers'" class="space-y-5">
        <div
          v-if="!catalog.providers.length"
          class="rounded-xl border border-dashed border-default bg-default p-12 text-center"
        >
          <UIcon name="i-lucide-boxes" class="size-9 text-primary" />
          <h2 class="mt-3 font-semibold">连接你的第一个供应商</h2>
          <p class="mt-2 text-sm text-muted">添加接入信息，在卡片下配置模型，再加入执行链。</p>
          <UButton class="mt-5" icon="i-lucide-plus" @click="editProvider()">添加供应商</UButton>
        </div>
        <div class="grid items-start gap-5 xl:grid-cols-2">
          <article
            v-for="provider in catalog.providers"
            :key="provider.id"
            class="overflow-hidden rounded-xl border border-default bg-default shadow-sm"
          >
            <header class="flex items-start gap-3 border-b border-default p-5">
              <span class="rounded-lg bg-primary/10 p-2.5 text-primary"
                ><UIcon name="i-lucide-plug-zap" class="size-5"
              /></span>
              <div class="min-w-0 flex-1">
                <h2 class="truncate font-semibold">{{ provider.title }}</h2>
                <p class="mt-1 truncate text-xs text-muted">
                  {{ provider.providerCode }} · OpenAI 兼容
                </p>
              </div>
              <UButton
                icon="i-lucide-settings-2"
                variant="ghost"
                color="neutral"
                :aria-label="`编辑 ${provider.title} 接入信息`"
                @click="editProvider(provider)"
              />
            </header>
            <div class="space-y-3 border-b border-default bg-muted/30 px-5 py-4">
              <p class="truncate font-mono text-xs text-muted" :title="provider.baseUrl">
                {{ provider.baseUrl }}
              </p>
              <div class="flex flex-wrap gap-2">
                <UBadge
                  :color="provider.keyConfigured ? 'success' : 'warning'"
                  variant="subtle"
                  size="sm"
                  >{{ provider.keyConfigured ? '密钥已配置' : '待配置密钥' }}</UBadge
                ><UBadge v-if="provider.walletKey" color="neutral" variant="outline" size="sm">{{
                  provider.walletKey
                }}</UBadge
                ><span class="ml-auto text-xs text-muted"
                  >{{ modelsFor(provider.id).length }} 个模型</span
                >
              </div>
            </div>
            <ul class="divide-y divide-default px-5">
              <li
                v-for="model in modelsFor(provider.id)"
                :key="model.id"
                class="flex items-center gap-3 py-4"
              >
                <span
                  class="size-2 shrink-0 rounded-full"
                  :class="model.isActive ? 'bg-success' : 'bg-muted'"
                />
                <div class="min-w-0 flex-1">
                  <p class="truncate text-sm font-medium">{{ model.modelNickname }}</p>
                  <p class="mt-1 truncate font-mono text-xs text-muted">{{ model.modelKey }}</p>
                  <AgentModelTest
                    :model="model"
                    :provider-revision="provider.revision"
                    class="mt-2"
                  />
                </div>
                <UBadge color="neutral" variant="subtle" size="sm">{{
                  model.modelType === 1 ? 'Chat' : 'Embedding'
                }}</UBadge
                ><UButton
                  icon="i-lucide-pencil"
                  color="neutral"
                  variant="ghost"
                  :aria-label="`编辑模型 ${model.modelNickname}`"
                  @click="modelToEdit = { provider, model }"
                />
              </li>
            </ul>
            <p v-if="!modelsFor(provider.id).length" class="px-5 py-6 text-sm text-muted">
              添加该供应商支持的对话或向量模型。
            </p>
            <footer class="border-t border-default px-5 py-3">
              <UButton
                icon="i-lucide-plus"
                variant="ghost"
                size="sm"
                @click="modelToEdit = { provider }"
                >添加模型</UButton
              >
            </footer>
          </article>
        </div>
      </section>
      <section v-show="tab === 'routes'" class="space-y-5">
        <AgentExecutionChain
          ref="chatChain"
          :type="1"
          :catalog="catalog"
          @saved="read"
        /><AgentExecutionChain ref="embeddingChain" :type="2" :catalog="catalog" @saved="read" />
      </section>
      <AgentPromptEditor v-show="tab === 'prompts'" ref="promptEditor" />
      <AgentWallets v-if="walletOpened" v-show="tab === 'wallets'" :providers="catalog.providers" />
    </template>
    <AgentSiteSettings v-if="siteOpened" v-show="tab === 'site'" ref="siteSettings" />
    <UModal
      v-model:open="providerOpen"
      :title="providerToEdit ? '编辑供应商' : '添加供应商'"
      :dismissible="false"
      :close="false"
      ><template #body
        ><AgentProviderEditor
          v-if="providerOpen"
          ref="providerForm"
          :provider="providerToEdit"
          @saved="providerSaved"
          @cancel="providerOpen = false" /></template
    ></UModal>
    <UModal
      v-model:open="modelOpen"
      :title="modelToEdit?.model ? '编辑模型' : '添加模型'"
      :dismissible="false"
      :close="false"
      ><template #body
        ><AgentModelEditor
          v-if="modelToEdit"
          ref="modelForm"
          :model="modelToEdit.model"
          :provider="modelToEdit.provider"
          @saved="modelSaved"
          @cancel="modelToEdit = undefined" /></template
    ></UModal>
    <AdminDiscardChanges :open="leaveOpen" @decision="decideLeave" />
  </div>
</template>
