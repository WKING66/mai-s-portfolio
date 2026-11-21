<script setup lang="ts">
import type { AgentModelCatalog, AgentModelRoute, AgentModelType } from '../../api/agentSettings'
import { useAgentSettingsApi } from '../../api/agentSettings'
const props = defineProps<{ type: AgentModelType; catalog: AgentModelCatalog }>()
const emit = defineEmits<{ saved: [] }>()
const api = useAgentSettingsApi()
const storedRoute = () =>
  props.catalog.routes.find((route) => route.type === props.type)?.modelIds ?? []
const original = ref([...storedRoute()])
const selected = ref([...original.value])
const pending = ref(false)
const error = ref('')
const query = ref('')
const differs = computed(() => JSON.stringify(selected.value) !== JSON.stringify(original.value))
defineExpose({ dirty: differs, pending })
const providerTitle = (id: number) =>
  props.catalog.providers.find((provider) => provider.id === id)?.title ?? '未知供应商'
const candidates = computed(() =>
  props.catalog.models
    .filter((model) => model.modelType === props.type)
    .map((model) => ({
      ...model,
      label: `${providerTitle(model.modelProviderId)} · ${model.modelNickname}`,
    })),
)
const filtered = computed(() =>
  candidates.value.filter((model) =>
    `${model.label} ${model.modelKey}`
      .toLocaleLowerCase()
      .includes(query.value.trim().toLocaleLowerCase()),
  ),
)
const ordered = computed(() =>
  selected.value.map((id) => candidates.value.find((model) => model.id === id)),
)
watch(storedRoute, (ids) => {
  // 目录刷新可以带来新的模型，保留管理员尚未保存的候选和预期版本。
  if (!differs.value) {
    original.value = [...ids]
    selected.value = [...ids]
  }
})
function toggle(id: number, checked: boolean) {
  selected.value = checked ? [...selected.value, id] : selected.value.filter((item) => item !== id)
}
function move(index: number, direction: -1 | 1) {
  const target = index + direction
  if (target < 0 || target >= selected.value.length) {
    return
  }
  const ids = [...selected.value]
  const value = ids[index]!
  ids[index] = ids[target]!
  ids[target] = value
  selected.value = ids
}
function reset() {
  original.value = [...storedRoute()]
  selected.value = [...original.value]
  error.value = ''
}
async function save() {
  if (pending.value || !differs.value) {
    return
  }
  pending.value = true
  error.value = ''
  try {
    const route: AgentModelRoute | null = await api.saveRoute(
      props.type,
      [...selected.value],
      [...original.value],
    )
    if (!route) {
      throw new Error('执行链未保存，请稍后重试。')
    }
    original.value = [...route.modelIds]
    selected.value = [...route.modelIds]
    emit('saved')
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '执行链未保存，当前顺序已保留。'
  } finally {
    pending.value = false
  }
}
</script>
<template>
  <section class="rounded-xl border border-default bg-default p-5 sm:p-6">
    <div class="mb-6 flex items-start gap-3">
      <span class="rounded-lg bg-primary/10 p-2 text-primary"
        ><UIcon
          :name="type === 1 ? 'i-lucide-messages-square' : 'i-lucide-workflow'"
          class="size-5"
      /></span>
      <div>
        <h2 class="font-semibold">{{ type === 1 ? 'Chat 回复' : 'Embedding 向量化' }}</h2>
        <p class="mt-1 text-sm text-muted">第一项为主模型，后续模型按顺序尝试。</p>
      </div>
    </div>
    <UAlert v-if="error" color="error" variant="subtle" :description="error" class="mb-4" />
    <fieldset :disabled="pending" class="grid gap-6 lg:grid-cols-2">
      <div class="min-w-0 space-y-3">
        <h3 class="text-sm font-medium">选择模型</h3>
        <UInput
          v-model="query"
          icon="i-lucide-search"
          placeholder="搜索供应商、模型名称或 ID"
          class="w-full"
        />
        <div class="max-h-80 space-y-1 overflow-y-auto rounded-lg border border-default p-2">
          <label
            v-for="model in filtered"
            :key="model.id"
            class="flex cursor-pointer items-start gap-3 rounded-md p-3 hover:bg-muted"
            ><UCheckbox
              :model-value="selected.includes(model.id)"
              :aria-label="model.label"
              @update:model-value="toggle(model.id, $event === true)"
            /><span class="min-w-0 text-sm"
              ><span class="block break-words font-medium">{{ model.label }}</span
              ><span class="mt-1 block break-all text-xs text-muted"
                >{{ model.modelKey }} · {{ model.isActive ? '已启用' : '保存链时启用' }}</span
              ></span
            ></label
          >
          <p v-if="!filtered.length" class="p-4 text-center text-sm text-muted">
            暂无匹配模型，请先在供应商中添加。
          </p>
        </div>
      </div>
      <div class="min-w-0 space-y-3">
        <h3 class="text-sm font-medium">
          执行顺序 <span class="text-muted">{{ selected.length }} 个候选</span>
        </h3>
        <ol class="space-y-2">
          <li
            v-for="(model, index) in ordered"
            :key="selected[index]"
            class="flex items-center gap-3 rounded-lg border border-default p-3"
          >
            <span class="text-sm font-medium tabular-nums text-primary">{{
              String(index + 1).padStart(2, '0')
            }}</span>
            <div class="min-w-0 flex-1">
              <p class="truncate text-sm font-medium">
                {{ model?.modelNickname ?? '模型已不存在' }}
              </p>
              <p class="truncate text-xs text-muted">{{ model?.label }}</p>
            </div>
            <UBadge v-if="index === 0" variant="subtle" size="sm">主模型</UBadge
            ><UButton
              icon="i-lucide-arrow-up"
              aria-label="上移候选模型"
              color="neutral"
              variant="ghost"
              :disabled="index === 0"
              @click="move(index, -1)"
            /><UButton
              icon="i-lucide-arrow-down"
              aria-label="下移候选模型"
              color="neutral"
              variant="ghost"
              :disabled="index === selected.length - 1"
              @click="move(index, 1)"
            /><UButton
              icon="i-lucide-x"
              aria-label="移出执行链"
              color="neutral"
              variant="ghost"
              @click="toggle(selected[index]!, false)"
            />
          </li>
        </ol>
        <p
          v-if="!selected.length"
          class="rounded-lg border border-dashed border-default p-6 text-center text-sm text-muted"
        >
          此链暂无候选，保存空链后将暂停该能力。
        </p>
      </div>
    </fieldset>
    <p v-if="type === 2" class="mt-5 text-xs text-muted">
      链中模型的标识与有效输出维度须一致，保存时整条校验。
    </p>
    <div class="mt-6 flex flex-wrap justify-end gap-2">
      <UButton variant="ghost" color="neutral" :disabled="pending || !differs" @click="reset"
        >撤销未保存修改</UButton
      ><UButton :loading="pending" :disabled="!differs" @click="save">启用所选模型并保存链</UButton>
    </div>
  </section>
</template>
