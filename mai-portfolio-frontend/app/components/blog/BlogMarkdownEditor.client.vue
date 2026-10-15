<script setup lang="ts">
import type { Editor } from '@tiptap/core'
import type { EditorToolbarItem } from '@nuxt/ui'
import { BLOG_EDITOR_EXTENSIONS, EDITOR_STARTER_KIT } from '../../utils/editor/markdownExtensions'
import {
  createBlogMarkdownManager,
  createBlogMarkdownOptions,
  inspectBlogMarkdown,
} from '../../utils/editor/markdownCompatibility'
import { readEditorMarkdown } from '../../utils/editor/markdownOutput'
import { inheritTableColumnAlignment } from '../../utils/editor/tableCommands'

const props = defineProps<{ modelValue: string; disabled?: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [markdown: string]
  'update:blocked': [blocked: boolean]
}>()
const richEditor = useTemplateRef('richEditor')
const mode = ref<'rich' | 'source'>('rich')
const richInput = ref('')
const compatibilityIssue = ref('')
const conversionIssue = ref('')
const recoveryMarkdown = ref<string | null>(null)
const sessionId = ref(0)
const manager = createBlogMarkdownManager()
const markdownOptions = shallowRef(createBlogMarkdownOptions())
let acceptedMarkdown: string | undefined
let initialDocument: Editor['state']['doc'] | undefined
let initialMarkdown = ''

// 1. 外部载入/重载和上传只接收 Markdown；自发的更新不重建编辑器，保留当前选择与撤销栈。
function prepareRichEditor(markdown: string) {
  conversionIssue.value = ''
  recoveryMarkdown.value = null
  emit('update:blocked', false)
  compatibilityIssue.value = inspectBlogMarkdown(markdown, manager)
  richInput.value = markdown
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
    if (editor) {
      initialDocument = editor.state.doc
      initialMarkdown = props.modelValue
    }
  },
)

function switchMode(nextMode: 'rich' | 'source') {
  if (props.disabled || nextMode === mode.value) {
    return
  }
  if (nextMode === 'rich') {
    if (conversionIssue.value) {
      return
    }
    prepareRichEditor(props.modelValue)
    if (compatibilityIssue.value || conversionIssue.value) {
      return
    }
  }
  mode.value = nextMode
}

// 2. 仅真实内容更新才回传，打开、选区变化和模式切换不能使草稿变脏或触发保存。
function updateSource(markdown: string) {
  if (props.disabled) {
    return
  }
  if (conversionIssue.value) {
    recoveryMarkdown.value = markdown
    return
  }
  acceptedMarkdown = markdown
  emit('update:modelValue', markdown)
}
function updateRich(markdown: string) {
  const editor = richEditor.value?.editor
  if (!editor || props.disabled) {
    return
  }
  try {
    const content = readEditorMarkdown(editor as Editor, markdown)
    const markdownForForm =
      initialDocument && editor.state.doc.eq(initialDocument) ? initialMarkdown : content
    acceptedMarkdown = markdownForForm
    richInput.value = content
    conversionIssue.value = ''
    recoveryMarkdown.value = null
    emit('update:blocked', false)
    if (markdownForForm !== props.modelValue) {
      emit('update:modelValue', markdownForForm)
    }
  } catch (cause) {
    // 保留转换结果供源码修复，同时阻止父级保存/发布；绝不能拿纯文本覆盖原正文。
    conversionIssue.value = cause instanceof Error ? cause.message : '转换失败，请在源码模式检查。'
    recoveryMarkdown.value = markdown
    emit('update:blocked', true)
  }
}
function acceptRecoverySource() {
  if (props.disabled || recoveryMarkdown.value === null) {
    return
  }
  // 用户明确检查并采用源码后才解除阻塞；保留原编辑缓冲直到这个动作发生。
  acceptedMarkdown = recoveryMarkdown.value
  emit('update:modelValue', recoveryMarkdown.value)
  recoveryMarkdown.value = null
  conversionIssue.value = ''
  emit('update:blocked', false)
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
  handleDrop() {
    // 文件统一通过现有上传入口；阻止浏览器把本地路径或 data URL 写入正文。
    return true
  },
}
</script>

<template>
  <div class="blog-markdown-editor min-w-0 max-w-full space-y-3">
    <div class="flex flex-wrap items-center gap-2" role="group" aria-label="正文编辑模式">
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
      title="尚未将转换结果用于保存"
      :description="conversionIssue"
    >
      <template #actions>
        <UButton color="neutral" variant="ghost" :disabled="disabled" @click="keepOriginalMarkdown"
          >保留转换前正文</UButton
        >
        <UButton
          color="neutral"
          variant="outline"
          :disabled="disabled || mode !== 'source'"
          @click="acceptRecoverySource"
          >检查后采用源码</UButton
        >
      </template>
    </UAlert>
    <UTextarea
      v-if="mode === 'source'"
      :model-value="recoveryMarkdown ?? modelValue"
      :disabled="disabled"
      :rows="18"
      placeholder="使用 Markdown 开始写作…"
      aria-label="博客 Markdown 源码"
      class="w-full"
      :ui="{ base: 'font-mono leading-7' }"
      @update:model-value="updateSource"
    />
    <UEditor
      v-else
      ref="richEditor"
      :key="sessionId"
      v-slot="{ editor }"
      :model-value="richInput"
      content-type="markdown"
      :markdown="markdownOptions"
      :editable="!disabled"
      :starter-kit="EDITOR_STARTER_KIT"
      :extensions="BLOG_EDITOR_EXTENSIONS"
      :image="false"
      :mention="false"
      :editor-props="editorProps"
      placeholder="开始写作…"
      :ui="{
        root: 'min-w-0 rounded-lg border border-default bg-default',
        content: 'min-w-0 max-h-[65svh] overflow-auto',
        base: 'min-h-96 px-4 py-5 sm:px-6',
      }"
      @update:model-value="updateRich"
    >
      <div class="flex min-w-0 flex-wrap items-center gap-1 border-b border-default p-2">
        <UEditorToolbar :editor="editor" :items="toolbarItems" :ui="{ root: 'flex-wrap' }" />
        <UDropdownMenu :items="tableActions(editor)"
          ><UButton
            color="neutral"
            variant="ghost"
            icon="i-lucide-table"
            aria-label="表格操作"
            :disabled="disabled"
        /></UDropdownMenu>
      </div>
    </UEditor>
    <p class="text-xs leading-6 text-muted">
      正文始终保存为
      Markdown。公式保留原文，图片显示稳定引用；表达式、代码语言与文件名可在源码模式修改。
    </p>
  </div>
</template>

<style scoped>
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
.blog-markdown-editor :deep(ul[data-type='taskList']) {
  list-style: none;
  padding-left: 0.5rem;
}
.blog-markdown-editor :deep(li[data-type='taskItem']) {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
}
.blog-markdown-editor :deep(li[data-type='taskItem'] > div) {
  min-width: 0;
  flex: 1;
}
.blog-markdown-editor :deep(.editor-preserved-math),
.blog-markdown-editor :deep(.editor-resource) {
  border-radius: 0.25rem;
  background: var(--ui-bg-elevated);
  padding: 0.125rem 0.25rem;
  white-space: pre-wrap;
}
</style>
