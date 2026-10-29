<script setup lang="ts">
import { NodeViewContent, NodeViewWrapper, type NodeViewProps } from '@tiptap/vue-3'
import { useClipboard } from '@vueuse/core'
import type { InputProps } from '@nuxt/ui'
import type { InputHTMLAttributes } from 'vue'
import { CODE_THEMES, CODE_FOLD, codeLabel, type CodeTheme } from '../../constants/codeThemes'
import { NodeSelection, Selection } from '@tiptap/pm/state'
import { codeLanguageOptions, resolveCodeLanguage } from '../../utils/markdown/code/codeLanguages'
import { codeFormatter, codeFormattingMessage } from '../../utils/editor/code/codeFormatting'
import { formatCodeInWorker } from '../../utils/editor/code/codeFormattingClient'
import { applyFormattedCode } from '../../utils/editor/code/codeFormattingTransaction'
import { IMMERSIVE_CODE_EXPANDED } from '../../utils/editor/interaction/immersiveDecorations'
import ImmersiveDiagram from './ImmersiveDiagram.vue'
import {
  CANVAS_CODE_INPUT,
  registerCanvasCodeInput,
  unregisterCanvasCodeInput,
  type CanvasCodeInput,
} from '../../utils/editor/code/canvasCodeInput'
import {
  IMMERSIVE_CODE_THEMES,
  IMMERSIVE_OVERLAY_HOST,
  IMMERSIVE_EDITABLE,
} from '../../utils/editor/session/immersiveContext'
import { parseCodeInfo, formatCodeInfo } from '../../utils/markdown/code/codeInfo'
const props = defineProps<NodeViewProps>()
const codeInputFactory = inject(CANVAS_CODE_INPUT, undefined)
const codeHost = useTemplateRef<HTMLElement>('codeHost')
const usesCodeInput = computed(() => Boolean(codeInputFactory) && !isMermaid.value)
let codeInput: CanvasCodeInput | undefined
if (codeInputFactory) {
  onMounted(() => {
    if (codeHost.value) {
      codeInput = codeInputFactory(codeHost.value, props)
      registerCanvasCodeInput(props.editor, codeHost.value, codeInput)
    }
  })
  onBeforeUnmount(() => {
    codeInput?.destroy()
    if (codeHost.value) {
      unregisterCanvasCodeInput(props.editor, codeHost.value)
    }
  })
}
const languageSearchInput: InputProps & Pick<InputHTMLAttributes, 'aria-label'> = {
  placeholder: '搜索语言或别名…',
  'aria-label': '搜索代码语言',
  icon: 'i-lucide-search',
  size: 'md',
}
const workbenchEditable = inject(IMMERSIVE_EDITABLE, undefined)
const canEdit = computed(() => workbenchEditable?.value ?? props.editor.isEditable)
if (codeInputFactory) {
  watch(canEdit, (value) => codeInput?.setEditable(value))
}
const availableLanguages = computed(() => codeLanguageOptions(language.value))
const selectedLanguage = computed(
  () =>
    availableLanguages.value.find((item) => item.value === language.value)?.value ||
    resolveCodeLanguage(language.value),
)
const selectedLanguageLabel = computed(
  () =>
    availableLanguages.value.find((item) => item.value === selectedLanguage.value)?.label ||
    '纯文本',
)
const showSource = ref(false)
const themeMenuOpen = ref(false)
const languageMenuOpen = ref(false)
const overlayHost = inject(IMMERSIVE_OVERLAY_HOST, ref<HTMLElement | null>(null))
const portalStyle = shallowRef<Record<string, string>>({})
watch([themeMenuOpen, languageMenuOpen], (values) => {
  if (!values.some(Boolean)) {
    return
  }
  const position = props.getPos()
  const element = typeof position === 'number' ? props.editor.view.nodeDOM(position) : null
  if (!(element instanceof HTMLElement)) {
    return
  }
  const styles = getComputedStyle(element)
  portalStyle.value = Object.fromEntries(
    ['light', 'dark', 'nord', 'tokyo', 'dracula']
      .flatMap((name) => ['--shiki-' + name, '--shiki-' + name + '-bg'])
      .map((name) => [name, styles.getPropertyValue(name)]),
  )
})
const themes = inject(IMMERSIVE_CODE_THEMES, reactive(new Map<string, CodeTheme>()))
const identity = computed(() =>
  String(
    props.decorations.find((item) => item.spec.immersiveBlockId)?.spec.immersiveBlockId ||
      'initial',
  ),
)
const theme = computed<CodeTheme>({
  get: () => themes.get(identity.value) || 'follow',
  set: (value) => {
    themes.set(identity.value, value)
  },
})
const themeItems = computed(() =>
  CODE_THEMES.map((item) => ({
    label: item.label,
    type: 'checkbox' as const,
    checked: theme.value === item.value,
    onUpdateChecked: () => {
      theme.value = item.value
    },
  })),
)
const portal = useTemplateRef<HTMLElement>('portal')
const diagram = useTemplateRef<InstanceType<typeof ImmersiveDiagram>>('diagram')
const language = computed(() => String(props.node.attrs.language || '').split(/\s/)[0] || '')
const currentFormatter = computed(() => codeFormatter(resolveCodeLanguage(language.value)))
const filename = computed(() => parseCodeInfo(String(props.node.attrs.language || '')).filename)
const label = computed(() => codeLabel(filename.value, language.value))
const lineNumbers = computed(() =>
  Array.from({ length: props.node.textContent.split('\n').length }, (_, index) => index + 1).join(
    '\n',
  ),
)
const expanded = ref(false)
if (!codeInputFactory) {
  watch(
    expanded,
    (value) => {
      const position = props.getPos()
      if (typeof position === 'number' && !props.editor.isDestroyed) {
        props.editor.view.dispatch(
          props.editor.state.tr
            .setMeta(IMMERSIVE_CODE_EXPANDED, { position, value })
            .setMeta('addToHistory', false),
        )
      }
    },
    { flush: 'post' },
  )
}
const isLongCodeBlock = computed(
  () => props.node.textContent.split(/\r?\n/).length > CODE_FOLD.threshold,
)
const collapsed = computed(() => isLongCodeBlock.value && !expanded.value && !caretInside.value)
const isMermaid = computed(() => language.value === 'mermaid')
const caretInside = ref(false)
function updateCaretInside() {
  const position = props.getPos()
  const { selection, doc } = props.editor.state
  const node = typeof position === 'number' ? doc.nodeAt(position) : null
  caretInside.value =
    typeof position === 'number' &&
    Boolean(node) &&
    !(selection instanceof NodeSelection) &&
    ((selection.from > position && selection.from < position + node!.nodeSize) ||
      (selection.to > position && selection.to < position + node!.nodeSize))
}
// Vue NodeView 不保证在仅选区变化时刷新 decorations props；光标必须跟随真实选区。
props.editor.on('selectionUpdate', updateCaretInside)
onBeforeUnmount(() => props.editor.off('selectionUpdate', updateCaretInside))
watch(() => props.node, updateCaretInside, { immediate: true })
const { copy, copied } = useClipboard({ legacy: true })
watch(
  [caretInside, canEdit],
  ([inside, editable]) => {
    if (inside && editable) {
      if (isMermaid.value) {
        showSource.value = true
      } else {
        expanded.value = true
      }
    }
  },
  { immediate: true },
)
const toast = useToast()

async function toggleCode(event?: MouseEvent) {
  if (event && event.currentTarget instanceof HTMLElement) {
    event.currentTarget.focus({ preventScroll: true })
  }
  const position = props.getPos()
  if (typeof position !== 'number') {
    return
  }
  const element = props.editor.view.nodeDOM(position)
  if (expanded.value || caretInside.value) {
    const { state } = props.editor
    if (
      canEdit.value &&
      state.selection.to > position &&
      state.selection.from < position + props.node.nodeSize
    ) {
      if (!state.selection.empty && !(state.selection instanceof NodeSelection)) {
        toast.add({ title: '请先取消代码中的文字选区，再收起代码。', color: 'neutral' })
        return
      }
      // 优先把光标移到相邻正文，不为收起制造整块全选；孤立代码块才使用节点选区。
      const visibleSelection =
        Selection.findFrom(state.doc.resolve(position + props.node.nodeSize), 1, true) ||
        Selection.findFrom(state.doc.resolve(position), -1, true) ||
        NodeSelection.create(state.doc, position)
      props.editor.view.dispatch(
        state.tr.setSelection(visibleSelection).setMeta('addToHistory', false),
      )
      // 焦点留在可见的收起按钮；文档选区已移到相邻正文，不再进入长文后立即失焦。
    }
    expanded.value = false
    await nextTick()
    // 编辑深处时浏览器可能滚动 pre；收起预览固定从首行开始，与行号保持一致。
    if (element instanceof HTMLElement) {
      const input = element.querySelector<HTMLElement>(
        '[data-canvas-code-input] .cm-scroller, .immersive-code-input',
      )
      if (input) {
        input.scrollTop = 0
      }
    }
    const pane =
      element instanceof HTMLElement ? element.closest<HTMLElement>('.canvas-pane') : null
    if (pane && element instanceof HTMLElement) {
      const offset = element.getBoundingClientRect().top - pane.getBoundingClientRect().top - 24
      if (offset < 0) {
        pane.scrollTop += offset
      }
    }
  } else {
    expanded.value = true
  }
}
const formatting = ref(false)
const formatError = ref('')
const formatErrorDetail = ref('')
watch(
  () => [props.node.textContent, props.node.attrs.language],
  () => {
    formatError.value = ''
    formatErrorDetail.value = ''
  },
)
let formatController: AbortController | undefined
async function formatCode() {
  if (formatting.value || !canEdit.value) {
    return
  }
  const snapshot = { source: props.node.textContent, info: String(props.node.attrs.language || '') }
  const controller = new AbortController()
  formatController = controller
  formatting.value = true
  formatError.value = ''
  formatErrorDetail.value = ''
  const formatterLabel = selectedLanguageLabel.value
  // 在用户点击时展开；后台完成后不再改折叠偏好，避免跳走后被迟到结果强制展开。
  expanded.value = true
  try {
    const formatted = await formatCodeInWorker(snapshot.source, language.value, controller.signal)
    if (!canEdit.value || !applyFormattedCode(props.editor, props.getPos(), snapshot, formatted)) {
      throw new Error('代码在格式化期间已变化，本次结果未覆盖新内容，请重试。')
    }
    toast.add({
      title: formatted === snapshot.source ? '代码格式已符合要求' : '代码已格式化，可撤销',
      color: 'success',
    })
  } catch (cause) {
    if (!controller.signal.aborted) {
      if (
        props.node.textContent !== snapshot.source ||
        String(props.node.attrs.language || '') !== snapshot.info
      ) {
        formatError.value = '代码在格式化期间已变化，请重试；当前内容已保留。'
      } else {
        const failure = codeFormattingMessage(cause, snapshot.source, formatterLabel)
        formatError.value = failure.message
        formatErrorDetail.value = failure.detail
      }
    }
  } finally {
    formatting.value = false
  }
}
onBeforeUnmount(() => formatController?.abort())
function updateInfo(nextLanguage: string, nextFilename: string) {
  if (!props.editor.isEditable) {
    return
  }
  props.updateAttributes({ language: formatCodeInfo(nextLanguage, nextFilename) })
}
</script>
<template>
  <NodeViewWrapper
    class="immersive-code-node my-6 min-w-0 rounded-xl border border-default"
    :class="{ 'blog-code': !isMermaid }"
    :data-code-theme="theme"
    @document-search-reveal="expanded = true"
  >
    <Teleport v-if="overlayHost" :to="overlayHost"
      ><div
        ref="portal"
        class="blog-code pointer-events-none"
        :data-code-theme="theme"
        :style="portalStyle"
        data-editor-controls
    /></Teleport>
    <div
      class="immersive-code-toolbar flex min-w-0 flex-wrap items-center gap-2 rounded-t-xl border-b border-default px-3 py-2 text-sm"
      :class="{ 'blog-code-toolbar': !isMermaid }"
      data-editor-controls
      contenteditable="false"
    >
      <span v-if="isMermaid" class="mr-auto font-medium">Mermaid</span>
      <template v-else>
        <UIcon name="i-lucide-file-code" class="size-4 shrink-0" />
        <UInput
          :model-value="filename"
          placeholder="文件名"
          aria-label="代码文件名"
          variant="none"
          size="xs"
          class="min-w-20 flex-1"
          :disabled="!canEdit"
          @update:model-value="updateInfo(language, $event)"
        />
        <USelectMenu
          v-model:open="languageMenuOpen"
          :model-value="selectedLanguage"
          :items="availableLanguages"
          value-key="value"
          :filter-fields="['label', 'value', 'aliases']"
          :search-input="languageSearchInput"
          :virtualize="{ estimateSize: 40 }"
          :portal="portal ?? false"
          :ui="{
            content: 'code-language-menu pointer-events-auto z-50 w-72 rounded-xl shadow-xl',
            input: 'code-language-search',
            viewport: 'code-language-options min-h-0',
            item: 'code-language-item h-10 items-center rounded-lg px-3 text-sm',
            itemLabel: 'flex min-w-0 items-center gap-3',
            itemTrailingIcon: 'size-4',
          }"
          aria-label="代码语言"
          variant="none"
          size="xs"
          class="w-24"
          :disabled="!canEdit"
          @update:model-value="updateInfo($event, filename)"
        >
          <template #content-top>
            <div class="code-language-heading">
              <span>代码语言</span>
              <span class="code-language-current" :title="selectedLanguageLabel">{{
                selectedLanguageLabel
              }}</span>
            </div>
          </template>
          <template #item-label="{ item }">
            <span class="min-w-0 flex-1 truncate">{{ item.label }}</span>
            <span v-if="item.value !== 'text'" class="code-language-id">{{ item.value }}</span>
          </template>
          <template #empty>
            <div class="code-language-empty">
              <UIcon name="i-lucide-search-x" class="size-5" />
              <span>未找到该语言</span>
            </div>
          </template>
          <template #content-bottom>
            <div class="code-language-footer">
              <span>支持名称和别名搜索</span><span>↑↓ 选择 · ↵ 确认</span>
            </div>
          </template>
        </USelectMenu>
        <UDropdownMenu
          v-model:open="themeMenuOpen"
          :items="themeItems"
          :modal="false"
          :portal="portal ?? false"
          :ui="{ content: 'pointer-events-auto z-50' }"
          :content="{ align: 'end' }"
        >
          <UButton
            color="neutral"
            variant="outline"
            size="xs"
            class="w-32 justify-between"
            trailing-icon="i-lucide-chevron-down"
            :aria-label="label + '代码主题'"
            >{{ CODE_THEMES.find((item) => item.value === theme)?.label }}</UButton
          >
        </UDropdownMenu>
      </template>
      <UButton
        v-if="!isMermaid && canEdit"
        color="neutral"
        variant="ghost"
        size="xs"
        icon="i-lucide-wand-sparkles"
        aria-label="格式化代码"
        :title="currentFormatter ? '格式化代码，可撤销' : '当前语言暂无浏览器格式化插件'"
        :disabled="!currentFormatter"
        :loading="formatting"
        @click="formatCode"
      />
      <UButton
        v-if="!isMermaid && isLongCodeBlock"
        color="neutral"
        variant="ghost"
        size="xs"
        :icon="collapsed ? 'i-lucide-chevron-down' : 'i-lucide-chevron-up'"
        :aria-label="collapsed ? '展开全部代码' : '收起代码'"
        :aria-expanded="!collapsed"
        @click="toggleCode"
        >{{ collapsed ? '展开全部代码' : '收起代码' }}</UButton
      >
      <UButton
        v-if="isMermaid"
        color="neutral"
        variant="ghost"
        size="xs"
        :aria-expanded="showSource"
        @click="showSource = !showSource"
        >{{ showSource ? '收起源码' : '查看源码' }}</UButton
      >
      <UButton
        v-if="isMermaid"
        color="neutral"
        variant="ghost"
        size="xs"
        icon="i-lucide-maximize"
        aria-label="全屏查看图表"
        @click="diagram?.openFullscreen()"
      />
      <UButton
        color="neutral"
        variant="ghost"
        size="xs"
        :icon="copied ? 'i-lucide-copy-check' : 'i-lucide-copy'"
        :aria-label="'复制 ' + label"
        @click="copy(node.textContent)"
      />
    </div>
    <!-- 普通代码由局部输入视图承载；Mermaid 仍使用同一份原文的原生 contentDOM。 -->
    <UAlert
      v-if="formatError"
      contenteditable="false"
      data-editor-controls
      color="warning"
      title="代码未格式化"
    >
      <template #description>
        <p>{{ formatError }}</p>
        <details v-if="formatErrorDetail" class="mt-2 text-xs">
          <summary class="cursor-pointer">查看错误详情</summary>
          <p class="mt-1 whitespace-pre-wrap break-words">{{ formatErrorDetail }}</p>
        </details>
      </template>
    </UAlert>
    <div
      class="blog-code-viewport"
      :data-code-collapsed="!isMermaid && collapsed"
      :style="{ '--code-preview-lines': CODE_FOLD.previewLines }"
    >
      <div
        v-show="!isMermaid || showSource"
        class="immersive-code-lines"
        :class="{ 'mermaid-source-input': isMermaid }"
        @pointerdown="canEdit && !isMermaid && (expanded = true)"
      >
        <pre
          v-if="!usesCodeInput"
          class="immersive-line-gutter"
          style="white-space: pre"
          aria-hidden="true"
          contenteditable="false"
          data-editor-controls
          @mousedown.prevent
          >{{ lineNumbers }}</pre>
        <div
          v-if="usesCodeInput"
          ref="codeHost"
          data-canvas-code-input
          contenteditable="false"
          class="min-w-0 flex-1"
        />
        <pre
          v-else
          class="immersive-code-input"
        ><NodeViewContent style="white-space: pre" as="code" :data-language="language" :aria-label="isMermaid ? 'Mermaid 源码' : label + '代码正文'" spellcheck="false" /></pre>
      </div>
    </div>
    <ImmersiveDiagram v-if="isMermaid" ref="diagram" :content="node.textContent" />
  </NodeViewWrapper>
</template>
<style scoped>
.blog-code-viewport[data-code-collapsed='true'] :deep(.cm-editor) {
  max-height: calc((var(--code-preview-lines) + 1) * 1.5rem);
  overflow: hidden;
}
.immersive-code-lines {
  display: flex;
  align-items: stretch;
  overflow-x: auto;
  border-radius: 0 0 0.75rem 0.75rem;
}
.immersive-line-gutter {
  position: sticky;
  left: 0;
  z-index: 1;
  flex: none;
  min-width: 2.5rem;
  margin: 0;
  padding: 1rem 0.5rem 1rem 0.75rem;
  font: 0.875rem/1.65 var(--font-mono);
  text-align: right;
  white-space: pre;
  user-select: none;
  pointer-events: none;
  color: color-mix(
    in srgb,
    var(--code-fg, var(--site-text)) 60%,
    var(--code-bg, var(--site-bg-muted))
  );
  background: var(--code-bg, var(--site-bg-muted));
  border-right: 1px solid
    color-mix(in srgb, var(--code-fg, var(--site-text)) 12%, var(--code-bg, var(--site-bg-muted)));
  border: 0;
  border-radius: 0;
}

.immersive-code-input {
  margin: 0;
  padding: 1rem;
  min-height: 3.5rem;
  overflow: visible;
  flex: 1 0 max-content;
  white-space: pre;
  overflow-wrap: normal;
  font: 0.875rem/1.65 var(--font-mono);
  border: 0;
  border-radius: 0 0 0.75rem 0.75rem;
  background: transparent;
  color: inherit;
  caret-color: currentColor;
}
.immersive-code-input :deep(code),
.immersive-code-input :deep([data-node-view-content]) {
  display: block;
  font: inherit;
  color: inherit;
  white-space: pre;
  border: 0;
  box-shadow: none;
  border-radius: 0;
  padding: 0;
  background: transparent;
  outline: none;
}
.mermaid-source-input {
  background: var(--site-bg-muted);
  border-bottom: 1px solid var(--ui-border);
}
.immersive-code-node:focus-within {
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--site-accent) 35%, transparent);
}
</style>
