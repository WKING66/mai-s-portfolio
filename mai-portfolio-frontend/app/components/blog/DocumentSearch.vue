<script setup lang="ts">
import type { DocumentSearchAdapter } from '../../utils/search/textSearch'
import { nextTextMatch } from '../../utils/search/textSearch'

const props = withDefaults(
  defineProps<{
    adapter: DocumentSearchAdapter
    replaceable?: boolean
    disabled?: boolean
  }>(),
  { replaceable: false, disabled: false },
)
const opened = ref(false)
const replacing = ref(false)
const query = ref('')
const replacement = ref('')
const total = ref(0)
const index = ref(-1)
const busy = ref(false)
const message = ref('')
const historyAction = ref<'undo' | 'redo' | null>(null)
const searchInput = useTemplateRef<HTMLInputElement>('searchInput')
const panel = useTemplateRef<HTMLElement>('panel')
let timer: ReturnType<typeof setTimeout> | undefined
let disconnect = () => {}
let previousFocus: HTMLElement | undefined
let version = 0
const counter = computed(() =>
  query.value ? `${index.value < 0 ? 0 : index.value + 1} / ${total.value}` : '',
)
const cannotReplace = computed(
  () => busy.value || !total.value || !props.replaceable || replacement.value === query.value,
)

async function refresh(locate: boolean) {
  const current = ++version
  try {
    total.value = props.adapter.find(query.value)
    index.value = total.value ? Math.min(Math.max(index.value, 0), total.value - 1) : -1
    await props.adapter.activate(index.value, locate)
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '无法查找当前正文，请重试。'
    total.value = 0
    index.value = -1
  } finally {
    if (current === version) {
      busy.value = false
    }
  }
}
function schedule(locate = false, delay = 250) {
  if (!opened.value) {
    return
  }
  version++
  busy.value = Boolean(query.value)
  clearTimeout(timer)
  // 只有面板打开时才观察正文；正文输入立即落地，查找等短暂停顿后再更新。
  timer = setTimeout(() => void refresh(locate), delay)
}
watch(query, () => {
  index.value = -1
  message.value = ''
  schedule(true, 120)
})
watch(
  () => props.replaceable,
  (value) => {
    if (!value) {
      replacing.value = false
    }
  },
)
watch(
  () => props.disabled,
  (value) => {
    if (value) {
      close(false)
    }
  },
)
async function open(replace = false) {
  if (props.disabled) {
    return
  }
  replacing.value = replace && props.replaceable
  if (!opened.value) {
    previousFocus =
      document.activeElement instanceof HTMLElement ? document.activeElement : undefined
    opened.value = true
    historyAction.value = null
    const selected = props.adapter.selectedText?.() ?? ''
    if (selected && !/[\r\n\ufffc]/.test(selected)) {
      query.value = selected
    }
    disconnect = props.adapter.observe(() => {
      historyAction.value = null
      message.value = ''
      schedule()
    })
    schedule(true, 0)
  }
  await nextTick()
  searchInput.value?.focus({ preventScroll: true })
  searchInput.value?.select()
}
function close(focus = true) {
  if (!opened.value) {
    return
  }
  opened.value = false
  version++
  clearTimeout(timer)
  disconnect()
  disconnect = () => {}
  if (focus) {
    if (index.value >= 0 && props.adapter.focus) {
      props.adapter.focus(index.value)
    } else {
      previousFocus?.focus({ preventScroll: true })
    }
  }
  props.adapter.clear()
  busy.value = false
}
async function move(direction: -1 | 1) {
  if (busy.value) {
    const first = index.value < 0
    clearTimeout(timer)
    await refresh(false)
    if (first) {
      await props.adapter.activate(index.value, true)
      return
    }
  }
  if (!total.value) {
    return
  }
  index.value = nextTextMatch(index.value, total.value, direction)
  await props.adapter.activate(index.value, true)
}
async function replace(all: boolean) {
  if (cannotReplace.value || !props.adapter.replace) {
    return
  }
  busy.value = true
  try {
    const result = props.adapter.replace(index.value, replacement.value, all)
    clearTimeout(timer)
    version++
    historyAction.value = result.replaced ? 'undo' : historyAction.value
    total.value = result.total
    index.value = result.next
    await props.adapter.activate(index.value, true)
    message.value = result.replaced
      ? `已替换 ${result.replaced} 处，可在正文中撤销。`
      : '正文没有变化。'
  } catch (cause) {
    message.value = cause instanceof Error ? cause.message : '本次替换未完成，正文已保留。'
    clearTimeout(timer)
    await refresh(false)
  } finally {
    busy.value = false
  }
}
async function history(direction: 'undo' | 'redo') {
  if (!props.adapter.history?.(direction)) {
    return
  }
  clearTimeout(timer)
  await refresh(false)
  historyAction.value = direction === 'undo' ? 'redo' : 'undo'
  message.value = direction === 'undo' ? '本次替换已撤销。' : '本次替换已重做。'
}
function keydown(event: KeyboardEvent) {
  if (event.isComposing || event.defaultPrevented) {
    return
  }
  if ((event.ctrlKey || event.metaKey) && !event.altKey && event.key.toLowerCase() === 'f') {
    const target = event.target instanceof Element ? event.target : null
    if (target?.closest('[role="dialog"]') && !panel.value?.contains(target)) {
      return
    }
    event.preventDefault()
    void open(event.shiftKey)
  } else if (
    opened.value &&
    historyAction.value &&
    (event.ctrlKey || event.metaKey) &&
    ['z', 'y'].includes(event.key.toLowerCase()) &&
    event.target instanceof Element &&
    panel.value?.contains(event.target) &&
    !(event.target instanceof HTMLInputElement)
  ) {
    event.preventDefault()
    const direction = event.shiftKey || event.key.toLowerCase() === 'y' ? 'redo' : 'undo'
    if (direction === historyAction.value) {
      void history(direction)
    }
  } else if (opened.value && event.key === 'Escape') {
    event.preventDefault()
    close()
  }
}
function pasteText(event: ClipboardEvent) {
  if (/[\r\n]/.test(event.clipboardData?.getData('text/plain') ?? '')) {
    event.preventDefault()
    message.value = '查找和替换仅支持单行文字；可以输入空格。'
  }
}
function inputKey(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.isComposing) {
    event.preventDefault()
    void move(event.shiftKey ? -1 : 1)
  }
}
onMounted(() => window.addEventListener('keydown', keydown))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', keydown)
  close(false)
})
</script>

<template>
  <UButton
    icon="i-lucide-search"
    color="neutral"
    variant="ghost"
    aria-label="查找正文"
    title="查找正文（Ctrl / ⌘ + F）"
    :disabled="disabled"
    :aria-expanded="opened"
    @click="open()"
  />
  <Teleport to="body">
    <section
      v-if="opened"
      ref="panel"
      role="dialog"
      :aria-label="replaceable ? '正文查找与替换' : '正文查找'"
      class="document-search-panel"
      data-document-search-panel
    >
      <div class="document-search-heading">
        <div role="tablist" aria-label="查找方式" class="document-search-tabs">
          <button
            type="button"
            role="tab"
            :aria-selected="!replacing"
            :class="{ selected: !replacing }"
            @click="replacing = false"
          >
            查找
          </button>
          <button
            v-if="replaceable"
            type="button"
            role="tab"
            :aria-selected="replacing"
            :class="{ selected: replacing }"
            @click="replacing = true"
          >
            替换
          </button>
        </div>
        <UButton
          icon="i-lucide-x"
          color="neutral"
          variant="ghost"
          size="xs"
          aria-label="关闭正文查找"
          @click="close()"
        />
      </div>
      <div class="document-search-field">
        <UIcon name="i-lucide-search" class="size-4 shrink-0 text-muted" />
        <input
          ref="searchInput"
          v-model="query"
          aria-label="查找内容"
          placeholder="查找正文"
          autocomplete="off"
          @keydown="inputKey"
          @paste="pasteText"
        />
        <span class="document-search-counter" aria-live="polite">{{
          busy ? '查找中…' : counter
        }}</span>
      </div>
      <div v-if="replacing" class="document-search-field mt-3">
        <UIcon name="i-lucide-replace" class="size-4 shrink-0 text-muted" />
        <input
          v-model="replacement"
          aria-label="替换为"
          placeholder="替换为（留空即删除）"
          autocomplete="off"
          @keydown="inputKey"
          @paste="pasteText"
        />
      </div>
      <div class="document-search-actions">
        <template v-if="replacing">
          <UButton size="xs" :disabled="cannotReplace" @click="replace(false)">替换</UButton>
          <UButton
            size="xs"
            color="neutral"
            variant="outline"
            :disabled="cannotReplace"
            @click="replace(true)"
            >全部替换</UButton
          >
        </template>
        <span class="flex-1" />
        <UButton
          icon="i-lucide-chevron-up"
          size="xs"
          color="neutral"
          variant="ghost"
          aria-label="上一处"
          title="上一处（Shift + Enter）"
          :disabled="busy || !total"
          @click="move(-1)"
        />
        <UButton
          icon="i-lucide-chevron-down"
          size="xs"
          color="neutral"
          variant="ghost"
          aria-label="下一处"
          title="下一处（Enter）"
          :disabled="busy || !total"
          @click="move(1)"
        />
      </div>
      <UButton
        v-if="historyAction && replaceable"
        size="xs"
        color="neutral"
        variant="link"
        :aria-label="historyAction === 'undo' ? '撤销上次替换' : '重做上次替换'"
        @click="history(historyAction)"
        >{{ historyAction === 'undo' ? '撤销替换' : '重做替换' }}</UButton
      >
      <p class="document-search-status" role="status">
        {{
          message ||
          (query && !busy && !total ? '未找到匹配内容' : '仅查找正文 · Enter 下一处 · Esc 关闭')
        }}
      </p>
    </section>
  </Teleport>
</template>

<style scoped>
.document-search-panel {
  position: fixed;
  z-index: 60;
  top: calc(var(--site-header-height, 5rem) + 4rem);
  right: max(1rem, env(safe-area-inset-right));
  width: min(23rem, calc(100vw - 2rem));
  max-height: calc(100dvh - var(--site-header-height, 5rem) - 5rem);
  overflow: auto;
  padding: 0.875rem;
  border: 1px solid var(--ui-border);
  border-radius: 0.875rem;
  background: var(--ui-bg-elevated);
  box-shadow: 0 12px 36px rgb(0 0 0 / 12%);
  color: var(--ui-text);
  font-size: 0.875rem;
}
.document-search-heading,
.document-search-tabs,
.document-search-actions,
.document-search-field {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.document-search-heading {
  justify-content: space-between;
  margin-bottom: 0.75rem;
}
.document-search-tabs button {
  padding: 0.25rem 0.5rem;
  border-bottom: 2px solid transparent;
  color: var(--ui-text-muted);
}
.document-search-tabs button.selected {
  border-color: var(--ui-primary);
  color: var(--ui-primary);
}
.document-search-field {
  padding: 0.5rem 0.625rem;
  border: 1px solid var(--ui-border-accented);
  border-radius: 0.5rem;
  background: var(--ui-bg);
}
.document-search-field:focus-within {
  border-color: var(--ui-primary);
  outline: 2px solid color-mix(in srgb, var(--ui-primary) 15%, transparent);
}
.document-search-field input {
  min-width: 0;
  width: 100%;
  outline: none;
  background: transparent;
}
.document-search-counter {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
  font-size: 0.75rem;
  color: var(--ui-text-muted);
}
.document-search-actions {
  margin-top: 0.75rem;
}
.document-search-status {
  margin: 0.5rem 0 0;
  font-size: 0.75rem;
  line-height: 1.5;
  color: var(--ui-text-muted);
}
</style>
