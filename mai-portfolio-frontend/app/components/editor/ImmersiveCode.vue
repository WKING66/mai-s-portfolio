<script setup lang="ts">
import { NodeViewContent, NodeViewWrapper, type NodeViewProps } from '@tiptap/vue-3'
import { useClipboard } from '@vueuse/core'
import { CODE_THEMES, codeLabel, type CodeTheme } from '../../constants/codeThemes'
import ImmersiveDiagram from './ImmersiveDiagram.vue'
import { IMMERSIVE_CODE_THEMES, IMMERSIVE_OVERLAY_HOST } from '../../utils/editor/immersiveContext'
import { parseCodeInfo, formatCodeInfo } from '../../utils/codeInfo'
const props = defineProps<NodeViewProps>()
const languageOptions = [
  { label: 'Java', value: 'java' },
  { label: 'Python', value: 'python' },
  { label: 'JavaScript', value: 'javascript' },
  { label: 'TypeScript', value: 'typescript' },
  { label: 'SQL', value: 'sql' },
  { label: 'Bash', value: 'bash' },
  { label: 'JSON', value: 'json' },
  { label: 'HTML', value: 'html' },
  { label: 'CSS', value: 'css' },
  { label: 'Text', value: 'text' },
]
const availableLanguages = computed(() =>
  languageOptions.some((item) => item.value === (language.value || 'text'))
    ? languageOptions
    : [...languageOptions, { label: language.value, value: language.value }],
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
const portal = useTemplateRef<HTMLElement>('portal')
const diagram = useTemplateRef<InstanceType<typeof ImmersiveDiagram>>('diagram')
const language = computed(() => String(props.node.attrs.language || '').split(/\s/)[0] || '')
const filename = computed(() => parseCodeInfo(String(props.node.attrs.language || '')).filename)
const label = computed(() => codeLabel(filename.value, language.value))
const lineNumbers = computed(() =>
  Array.from({ length: props.node.textContent.split('\n').length }, (_, index) => index + 1).join(
    '\n',
  ),
)
const collapsed = ref(false)
const isMermaid = computed(() => language.value === 'mermaid')
const caretInside = computed(() =>
  props.decorations.some((decoration) => decoration.spec.immersiveCaret),
)
const { copy, copied } = useClipboard({ legacy: true })
watch(caretInside, (inside) => {
  if (inside && isMermaid.value) {
    showSource.value = true
  }
})
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
          :disabled="!editor.isEditable"
          @update:model-value="updateInfo(language, $event)"
        />
        <USelect
          v-model:open="languageMenuOpen"
          :model-value="language || 'text'"
          :items="availableLanguages"
          :portal="portal ?? false"
          :ui="{ content: 'z-50 min-w-40' }"
          aria-label="代码语言"
          variant="none"
          size="xs"
          class="w-24"
          :disabled="!editor.isEditable"
          @update:model-value="updateInfo($event, filename)"
        />
        <USelect
          v-model="theme"
          v-model:open="themeMenuOpen"
          :items="[...CODE_THEMES]"
          :portal="portal ?? false"
          :ui="{ content: 'z-50' }"
          size="xs"
          class="w-32"
          :aria-label="label + '代码主题'"
        />
      </template>
      <UButton
        v-if="!isMermaid"
        color="neutral"
        variant="ghost"
        size="xs"
        :icon="collapsed ? 'i-lucide-chevron-down' : 'i-lucide-chevron-up'"
        :aria-label="collapsed ? '展开代码' : '折叠代码'"
        :aria-expanded="!collapsed"
        @click="collapsed = !collapsed"
      />
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
    <!-- 唯一的 contentDOM 始终保留：普通代码直接编辑；Mermaid 的同一原文在图上方展开。 -->
    <div
      v-show="isMermaid ? showSource : !collapsed"
      class="immersive-code-lines"
      :class="{ 'mermaid-source-input': isMermaid }"
    >
      <pre
        class="immersive-line-gutter"
        style="white-space: pre"
        aria-hidden="true"
        contenteditable="false"
        data-editor-controls
        @mousedown.prevent
        >{{ lineNumbers }}</pre>
      <pre
        class="immersive-code-input"
      ><NodeViewContent style="white-space: pre" as="code" :data-language="language" :aria-label="isMermaid ? 'Mermaid 源码' : label + '代码正文'" spellcheck="false" /></pre>
    </div>
    <ImmersiveDiagram v-if="isMermaid" ref="diagram" :content="node.textContent" />
  </NodeViewWrapper>
</template>
<style scoped>
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
