<script setup lang="ts">
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/vue-3'
import { NodeSelection } from '@tiptap/pm/state'
import { renderBlogMath, compactBlogMath } from '../../utils/blogMath'
import BlogMath from '../blog/BlogMath.vue'
import {
  immersiveMathSource,
  mathExitDirection,
  exitImmersiveMath,
} from '../../utils/editor/immersiveMath'
import { IMMERSIVE_MATH_PREVIEWS } from '../../utils/editor/immersiveContext'
const props = defineProps<NodeViewProps>()
const previews = inject(IMMERSIVE_MATH_PREVIEWS, undefined)
const editing = ref(false)
const input = useTemplateRef<HTMLTextAreaElement>('input')
const anchor = useTemplateRef<HTMLElement>('anchor')
const editReference = shallowRef<{
  contextElement: HTMLElement
  getBoundingClientRect: () => DOMRect
}>()
function anchorInput() {
  const element = anchor.value
  const context =
    element?.closest<HTMLElement>('.math-block, p, h1, h2, h3, h4, h5, h6, td, th') ??
    element?.parentElement
  if (!element || !context) {
    return
  }
  const initial = element.getBoundingClientRect()
  const origin = context.getBoundingClientRect()
  // 预览可实时改变尺寸，输入框保留打开时的锚点；上下文位置仍跟随正文滚动/位移。
  editReference.value = {
    contextElement: element,
    getBoundingClientRect: () => {
      const current = context.getBoundingClientRect()
      return new DOMRect(
        current.left + initial.left - origin.left,
        current.top + initial.top - origin.top,
        initial.width,
        initial.height,
      )
    },
  }
}
const expression = ref('')
const error = ref('')
const html = shallowRef('')
const block = computed(() => props.node.type.name === 'preservedMathBlock')
const composing = ref(false)
let revision = 0
const pointerOrigin = ref({ x: 0, y: 0 })
function delimiters() {
  const source = String(props.node.attrs.source || '')
  if (source.startsWith('\\[')) {
    return ['\\[', '\\]']
  }
  if (source.startsWith('\\(')) {
    return ['\\(', '\\)']
  }
  return block.value ? ['$$', '$$'] : ['$', '$']
}
function unwrap(source: string) {
  const [left, right] = delimiters()
  return source.trimEnd().slice(left!.length, -right!.length).trim()
}
function commit() {
  if (!props.editor.isEditable || composing.value) {
    return
  }
  // 只打开再关闭公式时保留分隔符内原有空白，不制造无意义的 Markdown 变更。
  if (expression.value === unwrap(String(props.node.attrs.source || ''))) {
    return
  }
  const source = immersiveMathSource(
    String(props.node.attrs.source || ''),
    expression.value,
    block.value,
  )
  const position = props.getPos()
  if (
    typeof position === 'number' &&
    props.editor.state.doc.nodeAt(position)?.attrs.source === source
  ) {
    return
  }
  if (source !== props.node.attrs.source) {
    props.updateAttributes({ source })
  }
}
function finishComposition(event: Event) {
  composing.value = false
  change(event)
}
function change(event: Event) {
  expression.value = (event.target as HTMLTextAreaElement).value
  schedulePreview()
}
function open(event?: MouseEvent) {
  if (
    !props.editor.isEditable ||
    (event &&
      Math.hypot(event.clientX - pointerOrigin.value.x, event.clientY - pointerOrigin.value.y) > 5)
  ) {
    return
  }
  if (!editing.value) {
    anchorInput()
  }
  editing.value = true
}
function panFormula(event: KeyboardEvent) {
  if (!event.shiftKey || !['ArrowLeft', 'ArrowRight'].includes(event.key)) {
    return
  }
  const container = (event.currentTarget as HTMLElement).querySelector<HTMLElement>('.blog-math')
  if (container && container.scrollWidth > container.clientWidth + 1) {
    event.preventDefault()
    event.stopPropagation()
    container.scrollLeft += event.key === 'ArrowRight' ? 80 : -80
  }
}
function enter(event: KeyboardEvent) {
  if (!block.value) {
    event.preventDefault()
    closeWithFocus()
  }
}
function closeWithFocus(side: 'before' | 'after' = 'after') {
  composing.value = false
  commit()
  editing.value = false
  const position = props.getPos()
  if (typeof position === 'number') {
    props.editor.commands.command(exitImmersiveMath(position, side === 'before' ? -1 : 1))
    props.editor.view.focus()
  }
}
function navigateExpression(event: KeyboardEvent) {
  if (
    event.isComposing ||
    event.shiftKey ||
    event.altKey ||
    event.ctrlKey ||
    event.metaKey ||
    composing.value ||
    !input.value ||
    input.value.selectionStart !== input.value.selectionEnd
  ) {
    return
  }
  const direction = mathExitDirection(
    expression.value,
    input.value.selectionStart,
    input.value.selectionEnd,
    event.key,
  )
  if (direction) {
    event.preventDefault()
    event.stopPropagation()
    closeWithFocus(direction < 0 ? 'before' : 'after')
  }
}
watch(editing, (value, _, cleanup) => {
  if (value) {
    window.addEventListener('resize', anchorInput)
    cleanup(() => window.removeEventListener('resize', anchorInput))
  } else {
    composing.value = false
    commit()
  }
})
watch(
  () => props.selected,
  (selected) => {
    if (
      selected &&
      props.editor.state.selection instanceof NodeSelection &&
      props.editor.state.selection.from === props.getPos()
    ) {
      open()
    }
  },
)
let previewTimer: ReturnType<typeof setTimeout> | undefined
let previewController: AbortController | undefined
async function renderExpression(value: string) {
  const current = ++revision
  previewController?.abort()
  previewController = new AbortController()
  if (!value.trim()) {
    html.value = ''
    error.value = ''
    return
  }
  const rendered = previews
    ? await previews.render(value, block.value, previewController.signal, editing.value)
    : await compactBlogMath(await renderBlogMath(value, block.value))
  if (!rendered || current !== revision) {
    return
  }
  if (rendered[0] !== 'blog-math') {
    html.value = ''
    error.value = '公式语法暂时无效，LaTeX 原文已保留。'
    return
  }
  if (current === revision) {
    html.value = String(rendered[1].html || '')
    error.value = ''
  }
}
function schedulePreview() {
  clearTimeout(previewTimer)
  // 输入只刷新此公式的安全 KaTeX 结果；失焦/Esc 再提交一次文档事务。
  previewTimer = setTimeout(() => {
    void renderExpression(expression.value)
  }, 80)
}
watch(
  () => props.node.attrs.source,
  (source) => {
    if (!editing.value) {
      expression.value = unwrap(String(source))
    }
    void renderExpression(unwrap(String(source)))
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  revision++
  previewController?.abort()
  clearTimeout(previewTimer)
})
</script>
<template>
  <NodeViewWrapper
    :as="block ? 'div' : 'span'"
    class="immersive-math-node"
    :class="{ 'math-block': block }"
    contenteditable="false"
    :draggable="false"
    @dragstart.prevent
  >
    <!-- 静态公式不初始化浮层上下文；仅当前正在编辑的公式创建 Popover。 -->
    <span
      ref="anchor"
      role="button"
      tabindex="0"
      class="math-open"
      aria-label="原位编辑公式"
      title="点击编辑公式；长公式可横向滑动或按 Shift + 左右键平移"
      :aria-expanded="editing"
      @keydown="panFormula"
      @pointerdown="pointerOrigin = { x: $event.clientX, y: $event.clientY }"
      @click="open"
      @keydown.enter.prevent="open()"
      @keydown.space.prevent="open()"
    >
      <span v-if="!expression.trim()" class="math-empty text-sm text-muted">添加 LaTeX 公式</span
      ><BlogMath v-else-if="html" :block="block" :html="html" /><code v-else>{{ expression }}</code>
    </span>
    <UPopover
      v-if="editing"
      v-model:open="editing"
      :reference="editReference ?? anchor ?? undefined"
      :content="{
        onOpenAutoFocus: (event: Event) => {
          event.preventDefault()
          input?.focus()
        },
        onCloseAutoFocus: (event: Event) => event.preventDefault(),
        onEscapeKeyDown: (event: Event) => {
          event.preventDefault()
          closeWithFocus()
        },
      }"
      :ui="{ content: 'p-3 w-[min(28rem,calc(100vw-2rem))]' }"
    >
      <template #content>
        <div data-editor-controls data-immersive-overlay class="math-editor" @pointerdown.stop>
          <textarea
            ref="input"
            :value="expression"
            aria-label="公式 LaTeX"
            :rows="Math.max(1, expression.split('\n').length)"
            spellcheck="false"
            writingsuggestions="false"
            @input="change"
            @compositionstart="composing = true"
            @compositionend="finishComposition"
            @blur="editing = false"
            @keydown="navigateExpression"
            @keydown.enter.exact="enter"
            @keydown.tab="editing = false"
          />
          <p class="mt-2 text-xs text-muted">Esc 结束输入，点击外部自动保留到当前草稿</p>
          <p v-if="error" class="mt-2 text-xs text-warning" role="status">{{ error }}</p>
        </div>
      </template>
    </UPopover>
    <span v-if="error && !editing" class="math-error text-xs text-warning" role="status">{{
      error
    }}</span>
  </NodeViewWrapper>
</template>
<style scoped>
.immersive-math-node {
  display: inline;
  max-width: 100%;
  vertical-align: baseline;
}
.math-block {
  display: block;
  margin-block: 1.25rem;
}
.math-open {
  display: inline-block;
  max-width: 100%;
  cursor: text;
  user-select: text;
  vertical-align: middle;
}
.math-open:focus-visible {
  outline: 2px solid var(--ui-primary);
  outline-offset: 3px;
  border-radius: 0.25rem;
}
.math-open[aria-expanded='true'] {
  border-radius: 0.2rem;
  background: color-mix(in srgb, var(--site-accent) 15%, transparent);
}
.math-error {
  margin-inline-start: 0.5rem;
}
.math-editor textarea {
  display: block;
  width: 100%;
  resize: none;
  background: var(--ui-bg);
  border: 1px solid var(--ui-border);
  border-radius: 0.35rem;
  padding: 0.5rem;
  color: var(--ui-text);
  font: 0.875rem/1.6 var(--font-mono);
}

/* 块公式锚点必须使用正文宽度；inline-block 收缩包装层会在 KaTeX 小数宽度处产生假溢出。 */
.math-block {
  min-width: 0;
}
.math-block .math-open {
  display: block;
  width: 100%;
  min-width: 0;
}
.math-block :deep(.blog-math-block) {
  width: 100%;
  min-width: 0;
  margin-block: 0;
}
.math-block :deep(.katex-display) {
  width: max-content;
  min-width: 100%;
}
/* 极长公式保留横向平移能力，不裁切或改写 LaTeX；普通公式不再挤在收缩容器里。 */
.immersive-math-node :deep(.blog-math) {
  scrollbar-width: none;
}
.immersive-math-node :deep(.blog-math::-webkit-scrollbar) {
  display: none;
}
</style>
