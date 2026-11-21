<script setup lang="ts">
import { useAgentSettingsApi, type AgentSitePolicy } from '../../api/agentSettings'
import { ApiRequestError } from '../../api/request'
import { useFeedback } from '../../composables/useFeedback'

const api = useAgentSettingsApi()
const feedback = useFeedback()
const saved = shallowRef<Awaited<ReturnType<typeof api.readSite>>>(null)
const draft = ref<AgentSitePolicy>()
const reading = ref(false)
const pending = ref(false)
const discardOpen = ref(false)
const error = ref('')
const fields = [
  { key: 'globalWindowHours', label: '全站滚动窗口（小时）' },
  { key: 'globalRequestLimit', label: '全站窗口内最多受理轮次' },
  { key: 'visitorDailyTurnLimit', label: '匿名访客 24 小时轮次上限' },
  { key: 'accountDailyTurnLimit', label: '登录账号 24 小时轮次上限' },
] as const
const dirty = computed(() =>
  Boolean(
    saved.value &&
    draft.value &&
    (draft.value.agentEnabled !== saved.value.agentEnabled ||
      fields.some(({ key }) => draft.value?.[key] !== saved.value?.[key])),
  ),
)
defineExpose({ dirty, pending })

function apply(result: NonNullable<typeof saved.value>) {
  saved.value = result
  const {
    agentEnabled,
    globalWindowHours,
    globalRequestLimit,
    visitorDailyTurnLimit,
    accountDailyTurnLimit,
  } = result
  draft.value = {
    agentEnabled,
    globalWindowHours,
    globalRequestLimit,
    visitorDailyTurnLimit,
    accountDailyTurnLimit,
  }
}

async function read() {
  if (reading.value || pending.value) {
    return
  }
  reading.value = true
  error.value = ''
  try {
    const result = await api.readSite()
    if (!result) {
      throw new Error('未能读取站点设置。')
    }
    apply(result)
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '读取站点设置失败。'
  } finally {
    reading.value = false
  }
}

async function save() {
  const policy = draft.value
  const previous = saved.value
  if (!policy || !previous || !dirty.value || reading.value || pending.value) {
    return
  }
  if (fields.some(({ key }) => !Number.isSafeInteger(policy[key]) || policy[key] < 1)) {
    error.value = '窗口小时数和轮次上限均须为正整数。'
    return
  }
  pending.value = true
  error.value = ''
  try {
    const result = await api.saveSite(policy, previous.updatedAt)
    if (!result) {
      throw new Error('保存结果未确认。')
    }
    apply(result)
    feedback.success('站点设置已保存。')
  } catch (failure) {
    let reason = '保存站点设置失败。'
    if (failure instanceof ApiRequestError && failure.status === 409) {
      reason = '站点设置已被更新。请核对当前输入后重新读取最新设置。'
    } else if (failure instanceof Error) {
      reason = failure.message
    }
    error.value = `${reason} 当前输入已保留。`
  } finally {
    pending.value = false
  }
}

function refresh() {
  if (dirty.value) {
    discardOpen.value = true
  } else {
    void read()
  }
}

function decideDiscard(discard: boolean) {
  discardOpen.value = false
  if (discard) {
    void read()
  }
}

onMounted(read)
</script>

<template>
  <section class="max-w-3xl space-y-5 rounded-xl border border-default bg-default p-5 sm:p-6">
    <div class="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h2 class="font-semibold">站点与限额</h2>
        <p class="mt-1 text-sm text-muted">控制站点是否开放 AI 向导，以及开放后的请求上限。</p>
      </div>
      <UButton
        color="neutral"
        variant="outline"
        icon="i-lucide-refresh-cw"
        :loading="reading"
        :disabled="pending"
        @click="refresh"
        >重新读取</UButton
      >
    </div>
    <UAlert v-if="error" color="error" variant="subtle" :description="error" />
    <USkeleton v-if="reading && !draft" class="h-56 rounded-lg" />
    <form v-if="draft" class="space-y-5" @submit.prevent="save">
      <fieldset class="space-y-5" :disabled="reading || pending">
        <USwitch v-model="draft.agentEnabled" label="开放站点 AI 向导" />
        <div class="grid gap-4 border-t border-default pt-5 sm:grid-cols-2">
          <UFormField v-for="field in fields" :key="field.key" :label="field.label" required>
            <UInput
              v-model.number="draft[field.key]"
              type="number"
              required
              :min="1"
              :step="1"
              class="w-full"
            />
          </UFormField>
        </div>
      </fieldset>
      <div class="flex justify-end">
        <UButton type="submit" :loading="pending" :disabled="!dirty || reading"
          >保存站点设置</UButton
        >
      </div>
    </form>
    <AdminDiscardChanges :open="discardOpen" @decision="decideDiscard" />
  </section>
</template>
