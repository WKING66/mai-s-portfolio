<script setup lang="ts">
import type { EditorToolbarItem, EditorSuggestionMenuItem, EditorCustomHandlers } from '@nuxt/ui'
import type { Editor } from '@tiptap/core'
import { TableKit } from '@tiptap/extension-table'
import { SHOWCASE_EDITOR_MARKDOWN, SHOWCASE_PORTAL } from '../../constants/designShowcase'
import { markdownSignature } from '../../utils/markdown'
import BlogRenderer from '../blog/BlogRenderer.vue'
import ResizableEditor from './ResizableEditor.vue'

// draft 是唯一内容源；visualValue 是保真的富文本投影，源码输入不直接覆盖它。
const draft = ref(SHOWCASE_EDITOR_MARKDOWN)
const visualValue = ref(draft.value)
const mode = ref('visual')
const formatIssue = ref('')
const editorRef = useTemplateRef<{ editor?: Editor }>('editorRef')
const modes = [{ label: '富文本', value: 'visual', icon: 'i-lucide-pen-line' }, { label: 'Markdown 源码', value: 'source', icon: 'i-lucide-code' }]
const extensions = [TableKit.configure({ table: { resizable: false } })]
const handlers = {
  insertTable: { canExecute: (editor: Editor) => editor.can().insertTable({ rows: 3, cols: 3, withHeaderRow: true }), execute: (editor: Editor) => editor.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }), isActive: (editor: Editor) => editor.isActive('table') },
  addRow: { canExecute: (editor: Editor) => editor.can().addRowAfter(), execute: (editor: Editor) => editor.chain().focus().addRowAfter(), isActive: () => false },
  addColumn: { canExecute: (editor: Editor) => editor.can().addColumnAfter(), execute: (editor: Editor) => editor.chain().focus().addColumnAfter(), isActive: () => false },
  deleteTable: { canExecute: (editor: Editor) => editor.can().deleteTable(), execute: (editor: Editor) => editor.chain().focus().deleteTable(), isActive: () => false },
} satisfies EditorCustomHandlers
const toolbar: EditorToolbarItem<typeof handlers>[][] = [
  [{ kind: 'undo', icon: 'i-lucide-undo', tooltip: { text: '撤销' } }, { kind: 'redo', icon: 'i-lucide-redo', tooltip: { text: '重做' } }],
  [{ icon: 'i-lucide-heading', tooltip: { text: '标题层次' }, items: [
    { kind: 'paragraph', label: '正文', icon: 'i-lucide-pilcrow' },
    ...([1, 2, 3] as const).map(level => ({ kind: 'heading' as const, level, label: ['一级标题', '二级标题', '三级标题'][level - 1], icon: 'i-lucide-heading-' + level })),
  ] }],
  [{ kind: 'mark', mark: 'bold', icon: 'i-lucide-bold', tooltip: { text: '加粗' } }, { kind: 'mark', mark: 'italic', icon: 'i-lucide-italic', tooltip: { text: '斜体' } }, { kind: 'mark', mark: 'strike', icon: 'i-lucide-strikethrough', tooltip: { text: '删除线' } }, { kind: 'mark', mark: 'code', icon: 'i-lucide-code', tooltip: { text: '行内代码' } }],
  [{ kind: 'bulletList', icon: 'i-lucide-list', tooltip: { text: '无序列表' } }, { kind: 'orderedList', icon: 'i-lucide-list-ordered', tooltip: { text: '有序列表' } }, { kind: 'blockquote', icon: 'i-lucide-text-quote', tooltip: { text: '引用' } }],
  [{ kind: 'link', icon: 'i-lucide-link', href: 'https://ui.nuxt.com', tooltip: { text: '链接到 Nuxt UI' } }, { kind: 'codeBlock', icon: 'i-lucide-square-code', tooltip: { text: '代码块' } }, { kind: 'horizontalRule', icon: 'i-lucide-minus', tooltip: { text: '分隔线' } }],
  [{ icon: 'i-lucide-table', tooltip: { text: '表格操作' }, items: [
    { kind: 'insertTable', label: '插入表格', icon: 'i-lucide-table' }, { kind: 'addRow', label: '增加行' }, { kind: 'addColumn', label: '增加列' }, { kind: 'deleteTable', label: '删除表格' },
  ] }],
]
const bubble: EditorToolbarItem[][] = [[
  { kind: 'mark', mark: 'bold', icon: 'i-lucide-bold', tooltip: { text: '选区加粗' } },
  { kind: 'mark', mark: 'italic', icon: 'i-lucide-italic', tooltip: { text: '选区斜体' } },
  { kind: 'mark', mark: 'strike', icon: 'i-lucide-strikethrough', tooltip: { text: '选区删除线' } },
]]
const suggestions: EditorSuggestionMenuItem<typeof handlers>[] = [
  { kind: 'paragraph', label: '正文', icon: 'i-lucide-pilcrow' }, { kind: 'heading', level: 2, label: '标题', icon: 'i-lucide-heading-2' },
  { kind: 'bulletList', label: '列表', icon: 'i-lucide-list' }, { kind: 'blockquote', label: '引用', icon: 'i-lucide-text-quote' },
  { kind: 'codeBlock', label: '代码块', icon: 'i-lucide-square-code' }, { kind: 'insertTable', label: '表格', icon: 'i-lucide-table' },
]
const appendMenu = () => document.querySelector(SHOWCASE_PORTAL) as HTMLElement
let switchRevision = 0
async function changeMode(next: string | number) {
  const current = ++switchRevision
  if (next === 'source') { mode.value = 'source'; return }
  const markdown = editorRef.value?.editor?.markdown
  if (!markdown) return
  const source = draft.value
  formatIssue.value = ''
  try {
    const serialized = markdown.serialize(markdown.parse(source))
    const [before, after] = await Promise.all([markdownSignature(source), markdownSignature(serialized)])
    if (current !== switchRevision || draft.value !== source) return
    if (before !== after) {
      formatIssue.value = '此内容转换为富文本后会改变格式。已保留完整 Markdown，请继续使用源码模式。'
      return
    }
    formatIssue.value = ''; visualValue.value = source; mode.value = 'visual'
  } catch { formatIssue.value = '此内容无法可靠转换，已保留 Markdown 源码。' }
}
function onVisualUpdate(value: string) { if (mode.value === 'visual') { visualValue.value = value; draft.value = value } }
function reset() { switchRevision++; draft.value = SHOWCASE_EDITOR_MARKDOWN; visualValue.value = draft.value; formatIssue.value = ''; mode.value = 'visual' }
</script>

<template>
  <div data-showcase-editor>
    <ResizableEditor>
      <template #editor>
        <div class="showcase-edit-content flex h-full min-h-0 flex-col">
          <div class="flex min-h-16 flex-wrap items-center justify-between gap-2 border-b border-default p-3">
            <UTabs :model-value="mode" :items="modes" :content="false" aria-label="编辑模式" @update:model-value="changeMode" />
            <UButton color="neutral" variant="ghost" icon="i-lucide-rotate-ccw" size="sm" @click="reset">重置样例</UButton>
          </div>
          <p v-if="formatIssue" role="alert" class="border-b border-default p-3 text-sm text-warning">{{ formatIssue }}</p>
          <UEditor v-show="mode === 'visual'" ref="editorRef" v-slot="{ editor }" :model-value="visualValue" content-type="markdown"
            :mention="false" :image="false" :starter-kit="{ underline: false }" :extensions="extensions" :handlers="handlers"
            :markdown="{ markedOptions: { gfm: true } }" class="flex min-h-0 flex-1 flex-col"
            :ui="{ content: 'min-h-0 flex-1 overflow-auto showcase-editor-scroll', base: 'showcase-editor-body min-h-full p-5' }"
            :editor-props="{ attributes: { 'aria-label': '富文本样板正文', role: 'textbox', 'aria-multiline': 'true' } }"
            placeholder="写下你的想法，或输入 / 打开菜单…" @update:model-value="onVisualUpdate">
            <UEditorToolbar :editor="editor" :items="toolbar" />
            <UEditorToolbar :editor="editor" :items="bubble" layout="bubble" :append-to="appendMenu" />
            <UEditorSuggestionMenu :editor="editor" :items="suggestions" :append-to="appendMenu" />
          </UEditor>
          <div v-show="mode === 'source'" class="min-h-0 flex-1 p-3">
            <UTextarea v-model="draft" aria-label="Markdown 样板源码" class="h-full w-full" :ui="{ base: 'h-full resize-none font-mono leading-7' }" />
          </div>
        </div>
      </template>
      <template #preview>
        <div class="flex h-full min-h-0 flex-col" data-editor-preview>
          <p class="flex min-h-16 items-center gap-2 border-b border-default px-5 text-sm font-medium"><UIcon name="i-lucide-book-open" />Markdown 实时预览</p>
          <div class="showcase-preview-scroll min-h-0 flex-1 overflow-auto p-5"><Suspense><BlogRenderer :markdown="draft" cache-key="showcase:editor" /><template #fallback><USkeleton class="h-48 w-full" /></template></Suspense></div>
        </div>
      </template>
    </ResizableEditor>
    <p class="mt-4 text-sm leading-6 text-muted">选中文字使用工具栏，或在空行输入 /。链接按钮将选中文字链接到 Nuxt UI 文档。预览与编辑独立滚动。</p>
    <details class="mt-4 rounded-lg border border-default p-4"><summary class="cursor-pointer text-sm font-medium">查看当前 Markdown 输出</summary><pre data-editor-output class="mt-4 overflow-x-auto text-sm leading-7"><code>{{ draft }}</code></pre></details>
  </div>
</template>
