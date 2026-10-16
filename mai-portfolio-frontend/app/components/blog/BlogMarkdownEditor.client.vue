<script setup lang="ts">
import type { Editor } from '@tiptap/core'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import BlogEditorImage from './BlogEditorImage.vue'
import type { EditorToolbarItem, EditorSuggestionMenuItem } from '@nuxt/ui'
import {
  BLOG_EDITOR_EXTENSIONS,
  EDITOR_STARTER_KIT,
  StableImage,
} from '../../utils/editor/markdownExtensions'
import {
  createBlogMarkdownManager,
  createBlogMarkdownOptions,
  inspectBlogMarkdown,
} from '../../utils/editor/markdownCompatibility'
import { readEditorMarkdown } from '../../utils/editor/markdownOutput'
import { inheritTableColumnAlignment } from '../../utils/editor/tableCommands'

const props = defineProps<{ modelValue: string; disabled?: boolean; embedded?: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [markdown: string]
  'update:blocked': [blocked: boolean]
  'update:mode': [mode: 'rich' | 'source']
  editor: [editor: Editor | null]
}>()
const richEditor = useTemplateRef('richEditor')
const mode = ref<'rich' | 'source'>('rich')
const richInput = ref('')
const compatibilityIssue = ref('')
const conversionIssue = ref('')
const sessionId = ref(0)
const manager = createBlogMarkdownManager()
// 只替换图片的编辑视图，Markdown Schema/属性仍与预检共享；续签由资源投影响应式更新。
const editorExtensions = BLOG_EDITOR_EXTENSIONS.map((extension) =>
  extension === StableImage
    ? StableImage.extend({ addNodeView: () => VueNodeViewRenderer(BlogEditorImage) })
    : extension,
)
const markdownOptions = shallowRef(createBlogMarkdownOptions())
let acceptedMarkdown: string | undefined
let initialDocument: Editor['state']['doc'] | undefined
let initialMarkdown = ''
let richSessionMarkdown = ''
let validationTimer: ReturnType<typeof setTimeout> | undefined
let pendingMarkdown: string | undefined
watch(mode, (value) => emit('update:mode', value), { immediate: true })
onBeforeUnmount(() => {
  clearTimeout(validationTimer)
  emit('editor', null)
})

// 1. 外部载入/重载和上传只接收 Markdown；自发的更新不重建编辑器，保留当前选择与撤销栈。
function prepareRichEditor(markdown: string) {
  clearTimeout(validationTimer)
  pendingMarkdown = undefined
  conversionIssue.value = ''
  emit('update:blocked', false)
  compatibilityIssue.value = inspectBlogMarkdown(markdown, manager)
  richInput.value = markdown
  richSessionMarkdown = markdown
  markdownOptions.value = createBlogMarkdownOptions()
  sessionId.value += 1
  if (compatibilityIssue.value) {
    mode.value = 'source'
  }
}
watch(
  () => props.modelValue,
  (markdown) => {
    if (markdown !== acceptedMarkdown) {
      prepareRichEditor(markdown)
    }
  },
  { immediate: true },
)

watch(
  () => props.disabled,
  (disabled) => richEditor.value?.editor?.setEditable(!disabled),
)

// 撤销恢复初始文档时同时恢复原 Markdown 字面值，避免仅因序列化空白让表单一直变脏。
watch(
  () => richEditor.value?.editor,
  (editor) => {
    emit('editor', editor ? markRaw(editor as Editor) : null)
    if (editor) {
      initialDocument = editor.state.doc
      initialMarkdown = props.modelValue
    }
  },
)

function switchMode(nextMode: 'rich' | 'source'): boolean {
  if (props.disabled) {
    return false
  }
  if (nextMode === mode.value) {
    return true
  }
  // 转换及往返校验成功后才提交模式；失败保留当前文档、光标和撤销历史。
  if (!flushRichUpdate()) {
    return false
  }
  if (nextMode === 'rich') {
    if (props.modelValue !== richSessionMarkdown || compatibilityIssue.value) {
      const issue = inspectBlogMarkdown(props.modelValue, manager)
      if (issue) {
        compatibilityIssue.value = issue
        return false
      }
      prepareRichEditor(props.modelValue)
    }
  }
  mode.value = nextMode
  return true
}

// 2. 仅真实内容更新才回传，打开、选区变化和模式切换不能使草稿变脏或触发保存。
function updateSource(markdown: string) {
  if (props.disabled) {
    return
  }
  acceptedMarkdown = markdown
  emit('update:modelValue', markdown)
}
// 连续输入只序列化编辑器本身；停顿后才验证完整往返，避免每键重复解析整篇正文。
function updateRich(markdown: string) {
  clearTimeout(validationTimer)
  pendingMarkdown = markdown
  emit('update:blocked', true)
  validationTimer = setTimeout(flushRichUpdate, 220)
}
function flushRichUpdate(): boolean {
  clearTimeout(validationTimer)
  if (pendingMarkdown === undefined) {
    return !conversionIssue.value
  }
  const markdown = pendingMarkdown
  pendingMarkdown = undefined
  return validateRichUpdate(markdown)
}
defineExpose({ switchMode, flushRichUpdate })
function validateRichUpdate(markdown: string): boolean {
  const editor = richEditor.value?.editor
  if (!editor || props.disabled) {
    return false
  }
  try {
    const content = readEditorMarkdown(editor as Editor, markdown)
    const markdownForForm =
      initialDocument && editor.state.doc.eq(initialDocument) ? initialMarkdown : content
    acceptedMarkdown = markdownForForm
    richSessionMarkdown = markdownForForm
    richInput.value = content
    conversionIssue.value = ''
    emit('update:blocked', false)
    if (markdownForForm !== props.modelValue) {
      emit('update:modelValue', markdownForForm)
    }
    return true
  } catch (cause) {
    // UEditor 出错时可能发出 getText()，不能把它作为可采用的源码；原富文本会话继续保留供撤销。
    conversionIssue.value = cause instanceof Error ? cause.message : '转换失败，请撤销后重试。'
    emit('update:blocked', true)
    return false
  }
}

function keepOriginalMarkdown() {
  if (props.disabled) {
    return
  }
  // 明确放弃失败的转换结果，回到最后一次完整确认的 Markdown；不触发保存。
  prepareRichEditor(props.modelValue)
  mode.value = 'source'
}

const toolbarItems: EditorToolbarItem[][] = [
  [
    { kind: 'undo', icon: 'i-lucide-undo-2', tooltip: { text: '撤销' } },
    { kind: 'redo', icon: 'i-lucide-redo-2', tooltip: { text: '重做' } },
  ],
  [
    {
      icon: 'i-lucide-heading',
      tooltip: { text: '标题' },
      items: [1, 2, 3, 4, 5, 6].map((level) => ({
        kind: 'heading' as const,
        level: level as 1 | 2 | 3 | 4 | 5 | 6,
        label: '标题 ' + level,
      })),
    },
  ],
  [
    { kind: 'mark', mark: 'bold', icon: 'i-lucide-bold', tooltip: { text: '加粗' } },
    { kind: 'mark', mark: 'italic', icon: 'i-lucide-italic', tooltip: { text: '斜体' } },
    { kind: 'mark', mark: 'strike', icon: 'i-lucide-strikethrough', tooltip: { text: '删除线' } },
    { kind: 'mark', mark: 'code', icon: 'i-lucide-code', tooltip: { text: '行内代码' } },
    { kind: 'link', icon: 'i-lucide-link', tooltip: { text: '链接' } },
  ],
  [
    { kind: 'bulletList', icon: 'i-lucide-list', tooltip: { text: '无序列表' } },
    { kind: 'orderedList', icon: 'i-lucide-list-ordered', tooltip: { text: '有序列表' } },
    { kind: 'taskList', icon: 'i-lucide-list-checks', tooltip: { text: '任务列表' } },
    { kind: 'blockquote', icon: 'i-lucide-quote', tooltip: { text: '引用' } },
    { kind: 'codeBlock', icon: 'i-lucide-square-code', tooltip: { text: '代码块' } },
    { kind: 'horizontalRule', icon: 'i-lucide-minus', tooltip: { text: '分隔线' } },
  ],
]
function tableActions(editor: Editor) {
  const isInTable = editor.isActive('table')
  return [
    {
      label: '插入表格',
      disabled: props.disabled || isInTable,
      onSelect: () =>
        editor.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run(),
    },
    {
      label: '在下方插入行',
      disabled: props.disabled || !isInTable,
      onSelect: () =>
        editor.chain().focus().addRowAfter().command(inheritTableColumnAlignment).run(),
    },
    {
      label: '在右侧插入列',
      disabled: props.disabled || !isInTable,
      onSelect: () => editor.chain().focus().addColumnAfter().run(),
    },
    {
      label: '删除当前行',
      // GFM 必须保留表头；删除整张表或在源码中调整表头仍可用。
      disabled: props.disabled || !isInTable || editor.isActive('tableHeader'),
      onSelect: () => editor.chain().focus().deleteRow().run(),
    },
    {
      label: '删除当前列',
      disabled: props.disabled || !isInTable,
      onSelect: () => editor.chain().focus().deleteColumn().run(),
    },
    {
      label: '删除表格',
      disabled: props.disabled || !isInTable,
      onSelect: () => editor.chain().focus().deleteTable().run(),
    },
  ]
}

const editorHandlers = {
  table: {
    canExecute: (editor: Editor) => !editor.isActive('table'),
    execute: (editor: Editor) =>
      editor.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }),
    isActive: (editor: Editor) => editor.isActive('table'),
  },
}
const suggestionItems: EditorSuggestionMenuItem[] = [
  { kind: 'paragraph', label: '正文', icon: 'i-lucide-text' },
  ...[1, 2, 3, 4, 5, 6].map((level) => ({
    kind: 'heading' as const,
    level: level as 1 | 2 | 3 | 4 | 5 | 6,
    label: '标题 ' + level,
    icon: 'i-lucide-heading',
  })),
  { kind: 'bulletList', label: '无序列表', icon: 'i-lucide-list' },
  { kind: 'orderedList', label: '有序列表', icon: 'i-lucide-list-ordered' },
  { kind: 'taskList', label: '任务列表', icon: 'i-lucide-list-checks' },
  { kind: 'table', label: '表格', icon: 'i-lucide-table' },
  { kind: 'blockquote', label: '引用', icon: 'i-lucide-quote' },
  { kind: 'codeBlock', label: '代码块', icon: 'i-lucide-square-code' },
  { kind: 'horizontalRule', label: '分隔线', icon: 'i-lucide-minus' },
]

// 3. HTML 粘贴只读取纯文本，再经过同一保真检查；不允许任意 HTML 落入 TipTap DOM。
const editorProps = {
  attributes: { 'aria-label': '博客富文本正文', spellcheck: 'false' },
  handlePaste(_view: unknown, event: ClipboardEvent) {
    const text = event.clipboardData?.getData('text/plain')
    if (!text || props.disabled) {
      return true
    }
    const editor = richEditor.value?.editor
    if (!editor) {
      return true
    }
    const issue = inspectBlogMarkdown(text, manager)
    if (issue) {
      // 不丢弃剪贴板：用纯文本插入，特殊 Markdown 可切到源码模式后再粘贴。
      editor.commands.insertContent({ type: 'text', text })
    } else {
      editor.commands.insertContent(text, { contentType: 'markdown' })
    }
    return true
  },
  handleDrop(_view: unknown, _event: DragEvent, _slice: unknown, moved: boolean) {
    // 编辑器内部的块和文本拖动交给 ProseMirror；外部文件仍使用显式上传入口。
    return !moved
  },
}
</script>

<template>
  <div class="blog-markdown-editor min-w-0 max-w-full space-y-3" :class="{ embedded }">
    <div
      v-if="!embedded"
      class="flex flex-wrap items-center gap-2"
      role="group"
      aria-label="正文编辑模式"
    >
      <UButton
        :variant="mode === 'rich' ? 'soft' : 'ghost'"
        color="neutral"
        :aria-pressed="mode === 'rich'"
        :disabled="disabled || Boolean(conversionIssue)"
        @click="switchMode('rich')"
        >富文本</UButton
      >
      <UButton
        :variant="mode === 'source' ? 'soft' : 'ghost'"
        color="neutral"
        :aria-pressed="mode === 'source'"
        :disabled="disabled"
        @click="switchMode('source')"
        >Markdown 源码</UButton
      >
    </div>
    <UAlert
      v-if="compatibilityIssue"
      color="warning"
      variant="soft"
      title="已保留 Markdown 原文"
      :description="compatibilityIssue"
    />
    <UAlert
      v-if="conversionIssue"
      color="error"
      variant="soft"
      title="转换未通过，已保留当前富文本输入"
      :description="conversionIssue"
    >
      <template #actions>
        <UButton color="neutral" variant="ghost" :disabled="disabled" @click="keepOriginalMarkdown"
          >放弃本次富文本修改，恢复已确认源码</UButton
        >
      </template>
    </UAlert>
    <UTextarea
      v-show="mode === 'source'"
      :model-value="modelValue"
      :disabled="disabled"
      :rows="18"
      placeholder="使用 Markdown 开始写作…"
      aria-label="博客 Markdown 源码"
      class="w-full"
      :ui="{ base: 'font-mono leading-7' }"
      @update:model-value="updateSource"
    />
    <UEditor
      v-if="!compatibilityIssue"
      v-show="mode === 'rich'"
      ref="richEditor"
      :key="sessionId"
      v-slot="{ editor }"
      :model-value="richInput"
      content-type="markdown"
      :markdown="markdownOptions"
      :editable="!disabled"
      :starter-kit="EDITOR_STARTER_KIT"
      :extensions="editorExtensions"
      :handlers="editorHandlers"
      :image="false"
      :mention="false"
      :editor-props="editorProps"
      placeholder="开始写作…"
      :ui="{
        root: embedded ? 'editor-rich-root' : 'min-w-0 rounded-lg border border-default bg-default',
        content: embedded ? 'editor-rich-content' : 'min-w-0 max-h-[65svh] overflow-auto',
        base: 'blog-renderer min-h-96 px-4 py-5 sm:px-6',
      }"
      @update:model-value="updateRich"
    >
      <UEditorSuggestionMenu :editor="editor" :items="suggestionItems" />
      <UEditorToolbar
        :editor="editor"
        :items="[
          ...toolbarItems,
          [{ icon: 'i-lucide-table', tooltip: { text: '表格操作' }, items: tableActions(editor) }],
        ]"
        layout="bubble"
        :ui="{ base: 'max-w-[calc(100vw-2rem)] flex-wrap' }"
      />
      <UEditorToolbar
        :editor="editor"
        :items="[
          toolbarItems[1]!,
          toolbarItems[3]!,
          [{ icon: 'i-lucide-table', tooltip: { text: '表格操作' }, items: tableActions(editor) }],
        ]"
        layout="floating"
        :ui="{ base: 'max-w-[calc(100vw-2rem)] flex-wrap' }"
      />
      <BlogEditorBlockMenu :editor="editor" :disabled="disabled" />
    </UEditor>
    <p v-if="!embedded" class="text-xs leading-6 text-muted">
      正文始终保存为 Markdown。公式保留原文；表达式、代码语言与文件名可在源码模式修改。
    </p>
  </div>
</template>

<style scoped>
.embedded {
  min-height: 100%;
}
.embedded :deep(.editor-rich-root) {
  border: 0;
  border-radius: 0;
  background: transparent;
}
.embedded :deep(.editor-rich-content) {
  min-width: 0;
  width: min(100%, 58rem);
  margin-inline: auto;
  padding: clamp(1rem, 3vw, 3rem) clamp(1rem, 4vw, 3rem) 6rem;
}
.embedded :deep(.tiptap) {
  padding: 0;
}
.embedded :deep(.tiptap p) {
  line-height: inherit;
}
.embedded :deep(textarea) {
  min-height: 65svh;
  border: 0;
  border-radius: 0;
  resize: vertical;
}
.editor-toolbar {
  position: sticky;
  top: 0;
  z-index: 2;
  background: var(--ui-bg);
}
.embedded > [role='alert'] {
  margin: 0.75rem;
}

.blog-markdown-editor :deep(.tiptap) {
  min-width: 0;
  overflow-wrap: anywhere;
}
.blog-markdown-editor :deep(pre) {
  max-width: 100%;
  overflow-x: auto;
}
.blog-markdown-editor :deep(.tableWrapper) {
  max-width: 100%;
  overflow-x: auto;
}
.blog-markdown-editor :deep(table) {
  border-collapse: collapse;
  width: 100%;
}
.blog-markdown-editor :deep(th),
.blog-markdown-editor :deep(td) {
  min-width: 5rem;
  border: 1px solid var(--ui-border);
  padding: 0.5rem 0.75rem;
}
.blog-markdown-editor :deep(ul:has(> li[data-checked])) {
  list-style: none;
  padding-left: 0.5rem;
}
.blog-markdown-editor :deep(li[data-checked]) {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
}
.blog-markdown-editor :deep(li[data-checked] > label) {
  flex-shrink: 0;
  padding-top: 0.25rem;
}
.blog-markdown-editor :deep(li[data-checked] > div > p) {
  margin-block: 0;
}
.blog-markdown-editor :deep(li[data-checked] > div) {
  min-width: 0;
  flex: 1;
}
.blog-markdown-editor :deep(.editor-preserved-markdown),
.blog-markdown-editor :deep(.editor-preserved-math),
.blog-markdown-editor :deep(.editor-resource) {
  border-radius: 0.25rem;
  background: var(--ui-bg-elevated);
  padding: 0.125rem 0.25rem;
  white-space: pre-wrap;
}
</style>
