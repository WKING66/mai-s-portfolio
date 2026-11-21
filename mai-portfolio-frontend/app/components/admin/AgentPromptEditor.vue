<script setup lang="ts">
import { useAgentSettingsApi } from '../../api/agentSettings'
import type { AgentPrompt, AgentPromptVersion } from '../../api/agentSettings'
const api = useAgentSettingsApi()
const prompts = ref<AgentPrompt[]>([])
const selectedId = ref<number>()
const versions = ref<AgentPromptVersion[]>([])
const versionId = ref<number>()
const content = ref('')
const pending = ref(false)
const reading = ref(false)
const error = ref('')
const notice = ref('')
const discardOpen = ref(false)
let afterDiscard: (() => void | Promise<void>) | undefined
const current = computed(() => prompts.value.find((prompt) => prompt.id === selectedId.value))
const preview = computed(() => versions.value.find((version) => version.id === versionId.value))
const active = computed(() =>
  versions.value.find((version) => version.id === current.value?.activeVersionId),
)
const dirty = computed(() => content.value !== (active.value?.content ?? ''))
defineExpose({ dirty, pending })
const promptOptions = computed(() =>
  prompts.value.map((prompt) => ({
    label: prompt.description || prompt.promptKey,
    value: prompt.id,
  })),
)
const versionOptions = computed(() =>
  versions.value.map((version) => ({
    label: `v${version.versionNo}${version.id === current.value?.activeVersionId ? ' · 当前生效' : ''}`,
    value: version.id,
  })),
)
function confirmSwitch(action: () => void | Promise<void>) {
  if (pending.value || reading.value) {
    return
  }
  if (dirty.value) {
    afterDiscard = action
    discardOpen.value = true
  } else {
    void action()
  }
}
function decideDiscard(discard: boolean) {
  const action = afterDiscard
  discardOpen.value = false
  afterDiscard = undefined
  if (discard) {
    void action?.()
  }
}
function selectPrompt(id: number) {
  confirmSwitch(() => {
    selectedId.value = id
    return readVersions()
  })
}
let loadVersion = 0
async function readVersions() {
  if (!current.value) {
    return
  }
  const sequence = ++loadVersion
  reading.value = true
  error.value = ''
  versions.value = []
  content.value = ''
  try {
    const response = await api.versions(current.value.id)
    if (sequence !== loadVersion) {
      return
    }
    if (!response) {
      throw new Error('未能读取历史版本，请重试。')
    }
    versions.value = response.versions
    versionId.value = current.value?.activeVersionId ?? response.versions[0]?.id
    content.value = active.value?.content ?? ''
  } catch (failure) {
    if (sequence === loadVersion) {
      error.value = failure instanceof Error ? failure.message : '读取版本失败。'
    }
  } finally {
    if (sequence === loadVersion) {
      reading.value = false
    }
  }
}
async function read() {
  if (reading.value || pending.value) {
    return
  }
  reading.value = true
  error.value = ''
  try {
    const response = await api.prompts()
    if (!response) {
      throw new Error('未能读取 Prompt，请重试。')
    }
    prompts.value = response.prompts
    selectedId.value = response.prompts[0]?.id
    if (selectedId.value) {
      await readVersions()
    }
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '读取 Prompt 失败。'
  } finally {
    reading.value = false
  }
}
async function change(publish: boolean) {
  const prompt = current.value
  if (!prompt || pending.value || reading.value || (!publish && !preview.value)) {
    return
  }
  pending.value = true
  error.value = ''
  notice.value = ''
  try {
    if (publish) {
      const result = await api.publish(prompt.id, content.value, prompt.activeVersionId)
      if (!result) {
        throw new Error('发布结果未确认，当前输入已保留。')
      }
      versions.value.unshift(result)
      prompt.activeVersionId = result.id
      versionId.value = result.id
      notice.value = `v${result.versionNo} 已发布并生效。`
    } else {
      const result = await api.activate(prompt.id, preview.value!.id, prompt.activeVersionId)
      if (!result) {
        throw new Error('版本切换结果未确认，请重新读取。')
      }
      const edited = dirty.value
      Object.assign(prompt, result)
      if (!edited) {
        content.value = active.value?.content ?? ''
      }
      notice.value = `已切换到 v${preview.value!.versionNo}。`
    }
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '保存失败，当前输入已保留。'
  } finally {
    pending.value = false
  }
}
onMounted(read)
onBeforeUnmount(() => {
  loadVersion++
})
</script>
<template>
  <section class="space-y-5 rounded-xl border border-default bg-default p-5 sm:p-6">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div>
        <h2 class="font-semibold">Prompt 版本</h2>
        <p class="mt-1 text-sm text-muted">发布新版本或切回历史版本，新轮次使用当前生效内容。</p>
      </div>
      <UButton
        variant="outline"
        color="neutral"
        icon="i-lucide-refresh-cw"
        :loading="reading"
        :disabled="pending"
        @click="confirmSwitch(read)"
        >重新读取</UButton
      >
    </div>
    <UAlert v-if="error" color="error" variant="subtle" :description="error" />
    <UAlert v-if="notice" color="success" variant="subtle" :description="notice" />
    <UFormField v-if="prompts.length" label="用途"
      ><USelect
        :model-value="selectedId"
        :items="promptOptions"
        :disabled="reading || pending"
        class="w-full sm:w-80"
        @update:model-value="selectPrompt"
    /></UFormField>
    <p v-if="!reading && !prompts.length && !error" class="py-8 text-center text-muted">
      暂无可配置的 Prompt。
    </p>
    <USkeleton v-if="reading" class="h-64 rounded-lg" />
    <div v-else-if="current" class="grid gap-6 xl:grid-cols-2">
      <form class="space-y-4" @submit.prevent="change(true)">
        <UFormField
          label="新版本内容"
          :description="`当前生效版本 ${active ? `v${active.versionNo}` : '暂无'}`"
          ><UTextarea
            v-model="content"
            :rows="12"
            :maxlength="2000"
            required
            :disabled="pending"
            class="w-full"
        /></UFormField>
        <div class="flex items-center justify-between gap-3">
          <span class="text-xs text-muted">{{ content.length }} / 2000</span
          ><UButton
            type="submit"
            icon="i-lucide-upload"
            :loading="pending"
            :disabled="!content.trim() || content === active?.content"
            >发布新版本</UButton
          >
        </div>
      </form>
      <div class="space-y-4">
        <UFormField label="历史版本"
          ><USelect
            v-model="versionId"
            :items="versionOptions"
            :disabled="pending"
            placeholder="暂无历史版本"
            class="w-full"
        /></UFormField>
        <pre
          class="max-h-80 min-h-48 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-muted p-4 font-sans text-sm leading-7"
          >{{ preview?.content ?? '暂无内容' }}</pre>
        <div class="flex items-center justify-between gap-3">
          <span class="text-xs text-muted">{{
            preview ? new Date(preview.createdAt).toLocaleString('zh-CN') : ''
          }}</span
          ><UButton
            variant="outline"
            icon="i-lucide-history"
            :disabled="pending || !preview || preview.id === current.activeVersionId"
            @click="change(false)"
            >使用此版本</UButton
          >
        </div>
      </div>
    </div>
    <AdminDiscardChanges :open="discardOpen" @decision="decideDiscard" />
  </section>
</template>
