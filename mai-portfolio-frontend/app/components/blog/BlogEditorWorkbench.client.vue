<script setup lang="ts">
import { BLOG_RESOURCES } from '../../utils/blogResources'
import type { DocumentResource } from '../../api/blogs'
import BlogRenderer from './BlogRenderer.vue'
import BlogMarkdownEditor from './BlogMarkdownEditor.client.vue'

const props = defineProps<{
  modelValue: string
  resources: DocumentResource[]
  disabled?: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [markdown: string]
  'update:blocked': [blocked: boolean]
}>()
provide(BLOG_RESOURCES, toRef(props, 'resources'))
const workspace = useTemplateRef<HTMLElement>('workspace')
const markdownEditor = useTemplateRef('markdownEditor')
const editorMode = ref<'rich' | 'source'>('rich')
const previewOnly = ref(false)
const isSplit = ref(false)
const containerWidth = ref(0)
const minimumPercent = computed(() =>
  containerWidth.value ? Math.min(45, (320 / containerWidth.value) * 100) : 30,
)
const previewVisited = ref(false)
const previewMarkdown = ref(props.modelValue)
const previewPercent = ref(42)
const isResizing = ref(false)
const previewId = useId()
let resizeObserver: ResizeObserver | undefined
let activePointer: number | null = null
let dragHandle: HTMLElement | null = null
let dragBounds: DOMRect | undefined

// 1. 窄容器切换单栏；隐藏预览时暂停输入同步，首次打开才创建阅读器。
watch(
  [() => props.modelValue, isSplit, previewOnly],
  ([markdown, split, preview]) => {
    if (split || preview) {
      previewVisited.value = true
      previewMarkdown.value = markdown
    }
  },
  { immediate: true },
)
function chooseEditor(mode: 'rich' | 'source') {
  markdownEditor.value?.switchMode(mode)
  previewOnly.value = false
}
function choosePreview() {
  markdownEditor.value?.flushRichUpdate()
  previewOnly.value = true
}

// 2. 宽度只影响 CSS 列尺寸，既不改变 Markdown，也不重建编辑器/阅读器实例。
function setPreviewWidth(percent: number) {
  const minimum = minimumPercent.value
  previewPercent.value = Math.min(100 - minimum, Math.max(minimum, percent))
}
function beginResize(event: PointerEvent) {
  if (!isSplit.value || event.button !== 0) {
    return
  }
  const handle = event.currentTarget as HTMLElement
  dragBounds = workspace.value?.getBoundingClientRect()
  if (!dragBounds) {
    return
  }
  event.preventDefault()
  handle.setPointerCapture(event.pointerId)
  activePointer = event.pointerId
  dragHandle = handle
  isResizing.value = true
}
function resize(event: PointerEvent) {
  if (event.pointerId !== activePointer || !dragBounds) {
    return
  }
  setPreviewWidth(((dragBounds.right - event.clientX) / dragBounds.width) * 100)
}
function finishResize() {
  if (activePointer !== null && dragHandle?.hasPointerCapture(activePointer)) {
    dragHandle.releasePointerCapture(activePointer)
  }
  activePointer = null
  dragHandle = null
  dragBounds = undefined
  isResizing.value = false
}
function resizeWithKeyboard(event: KeyboardEvent) {
  const adjustment = event.shiftKey ? 10 : 2
  if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
    event.preventDefault()
    setPreviewWidth(previewPercent.value + (event.key === 'ArrowLeft' ? adjustment : -adjustment))
  } else if (event.key === 'Home' || event.key === 'End') {
    event.preventDefault()
    setPreviewWidth(event.key === 'Home' ? 100 : 0)
  } else if (event.key === 'Enter') {
    event.preventDefault()
    previewPercent.value = 42
  }
}
onMounted(() => {
  resizeObserver = new ResizeObserver(([entry]) => {
    if (entry) {
      containerWidth.value = entry.contentRect.width
      isSplit.value = containerWidth.value >= 896
      if (!isSplit.value) {
        finishResize()
      }
      if (isSplit.value) {
        setPreviewWidth(previewPercent.value)
      }
    }
  })
  if (workspace.value) {
    resizeObserver.observe(workspace.value)
  }
})
onBeforeUnmount(() => {
  finishResize()
  resizeObserver?.disconnect()
})
</script>

<template>
  <section
    ref="workspace"
    class="editor-workbench"
    :class="{ 'is-split': isSplit, 'is-resizing': isResizing }"
    :style="{ '--preview-width': previewPercent + '%' }"
    aria-label="博客正文工作台"
  >
    <header class="workbench-header">
      <div class="flex flex-wrap items-center gap-1" role="group" aria-label="正文工作方式">
        <UButton
          :variant="!previewOnly && editorMode === 'rich' ? 'soft' : 'ghost'"
          color="neutral"
          icon="i-lucide-pen-line"
          :aria-pressed="!previewOnly && editorMode === 'rich'"
          :disabled="disabled"
          @click="chooseEditor('rich')"
          >富文本编辑</UButton
        >
        <UButton
          :variant="!previewOnly && editorMode === 'source' ? 'soft' : 'ghost'"
          color="neutral"
          icon="i-lucide-code-xml"
          :aria-pressed="!previewOnly && editorMode === 'source'"
          :disabled="disabled"
          @click="chooseEditor('source')"
          >Markdown 源码</UButton
        >
        <UButton
          v-if="!isSplit"
          :variant="previewOnly ? 'soft' : 'ghost'"
          color="neutral"
          icon="i-lucide-book-open"
          :aria-pressed="previewOnly"
          @click="choosePreview"
          >阅读预览</UButton
        >
      </div>
      <span v-if="isSplit" class="text-xs text-muted">实时预览 · 当前草稿</span>
    </header>
    <div class="workbench-panes">
      <div v-show="isSplit || !previewOnly" class="workbench-edit-pane">
        <BlogMarkdownEditor
          ref="markdownEditor"
          :model-value="modelValue"
          :disabled="disabled"
          embedded
          @update:model-value="emit('update:modelValue', $event)"
          @update:blocked="emit('update:blocked', $event)"
          @update:mode="editorMode = $event"
        />
      </div>
      <div
        v-if="isSplit"
        role="separator"
        tabindex="0"
        aria-label="调整预览宽度"
        aria-orientation="vertical"
        :aria-valuemin="Math.round(minimumPercent)"
        :aria-valuemax="Math.round(100 - minimumPercent)"
        :aria-valuenow="Math.round(previewPercent)"
        :aria-valuetext="'预览宽度 ' + Math.round(previewPercent) + '%'"
        class="workbench-resizer"
        @pointerdown="beginResize"
        @pointermove="resize"
        @pointerup="finishResize"
        @pointercancel="finishResize"
        @lostpointercapture="finishResize"
        @keydown="resizeWithKeyboard"
        @dblclick="previewPercent = 42"
      >
        <span aria-hidden="true" />
      </div>
      <section
        v-if="previewVisited"
        v-show="isSplit || previewOnly"
        :id="previewId"
        class="workbench-preview-pane"
        aria-label="当前 Markdown 草稿预览"
        tabindex="0"
      >
        <p class="mb-6 text-xs text-muted">当前草稿 · 尚未保存的修改也会在这里显示</p>
        <Suspense>
          <BlogRenderer
            :markdown="previewMarkdown"
            :resources="resources"
            :debounce="600"
            progressive
          />
          <template #fallback
            ><p role="status" class="text-sm text-muted">正在准备阅读预览…</p></template
          >
        </Suspense>
      </section>
    </div>
  </section>
</template>

<style scoped>
.editor-workbench {
  min-width: 0;
  border: 1px solid var(--ui-border);
  border-radius: var(--ui-radius);
  background: var(--ui-bg);
}
.workbench-header {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem;
  border-bottom: 1px solid var(--ui-border);
}
.workbench-panes {
  min-width: 0;
  height: clamp(28rem, 72svh, 58rem);
}
.workbench-edit-pane,
.workbench-preview-pane {
  min-width: 0;
  height: 100%;
  overflow: auto;
  overscroll-behavior: contain;
}
.workbench-preview-pane {
  padding: clamp(1rem, 2vw, 2rem);
}
.is-split .workbench-panes {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 0.75rem minmax(0, var(--preview-width));
}
.workbench-resizer {
  display: grid;
  place-items: center;
  cursor: col-resize;
  touch-action: none;
  background: var(--ui-bg-elevated);
}
.workbench-resizer span {
  width: 2px;
  height: 2.5rem;
  border-radius: 1rem;
  background: var(--ui-border-accented);
}
.workbench-resizer:hover span,
.workbench-resizer:focus-visible span {
  background: var(--ui-primary);
}
.is-resizing {
  user-select: none;
  cursor: col-resize;
}
</style>
