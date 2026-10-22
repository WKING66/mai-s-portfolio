<script setup lang="ts">
import {
  ImmersiveMarkdownGuard,
  type ImmersiveRejection,
} from '../../utils/editor/immersiveCapabilities'
import type { Node as TiptapNode } from '@tiptap/core'
import {
  ImmersiveChapters,
  immersiveChapters,
  revealImmersivePosition,
  IMMERSIVE_CHAPTER_KEY,
} from '../../utils/editor/immersiveChapters'
import { visibleToc } from '../../utils/blogChapters'
import { TextSelection, type Transaction } from '@tiptap/pm/state'
import type { EditorToolbarItem, FormError } from '@nuxt/ui'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import { useMediaQuery, useElementSize } from '@vueuse/core'
import { PreservedCodeBlock } from '../../utils/editor/markdownExtensions'
import { Markdown } from '@tiptap/markdown'
import {
  CANVAS_EDITOR_EXTENSIONS,
  CANVAS_STARTER_KIT,
  createCanvasMarkdownOptions,
} from '../../utils/editor/canvasMarkdown'
import {
  collectEditorHeadings,
  editorHeadingTree,
  type EditorHeading,
} from '../../utils/editor/editorOutline'
import ImmersiveCode from './ImmersiveCode.vue'
import ImmersiveMath from './ImmersiveMath.vue'
import ImmersiveImage from './ImmersiveImage.vue'
import { nodeInputRule } from '@tiptap/core'
import { openImmersiveLink } from '../../utils/editor/immersiveLinks'
import { openImmersiveMenu } from '../../utils/editor/immersiveCommands'
import { ImmersiveParagraphInput } from '../../utils/editor/immersiveParagraphInput'
import { ImmersiveSelection } from '../../utils/editor/immersiveSelection'
import {
  ImmersivePositionHighlight,
  IMMERSIVE_POSITION_KEY,
} from '../../utils/editor/immersivePositionHighlight'
import { ImmersiveDecorations } from '../../utils/editor/immersiveDecorations'
import { BLOG_RESOURCES } from '../../utils/blogResources'
import type { DocumentResource } from '../../api/blogs'
import { BLOG_DOWNLOAD_URL_RELEASE_DELAY_MS } from '../../constants/blogs'
import { useCanvasDocument } from '../../composables/useCanvasDocument'
import { CANVAS_RESOURCE_PREVIEW, insertCanvasFiles } from '../../utils/editor/canvasResources'
import {
  IMMERSIVE_CODE_THEMES,
  IMMERSIVE_OVERLAY_HOST,
  stopImmersiveControlEvent,
} from '../../utils/editor/immersiveContext'

const props = defineProps<{
  initialMarkdown: string
  resources?: DocumentResource[]
}>()
const metadata = defineModel<{
  title: string
  slug: string
  summary: string
  allowDownload: boolean
  remoteRightsConfirmed: boolean
}>('metadata', { required: true })
provide(
  BLOG_RESOURCES,
  computed(() => props.resources ?? []),
)
provide(IMMERSIVE_CODE_THEMES, reactive(new Map()))
const overlayHost = useTemplateRef<HTMLElement>('overlayHost')
provide(IMMERSIVE_OVERLAY_HOST, overlayHost)
const simulateFailure = ref(false)
const simulateResourceFailure = ref(false)
const canvas = useCanvasDocument(
  props.initialMarkdown,
  metadata,
  props.resources ?? [],
  () => simulateFailure.value,
  () => simulateResourceFailure.value,
)
provide(CANVAS_RESOURCE_PREVIEW, canvas.localResources.preview)
const { initialDocument, editor, status: saveStatus } = canvas
const { dirty, saving } = canvas.saved
const title = computed({
  get: () => metadata.value.title,
  set: (title) => {
    metadata.value = { ...metadata.value, title }
  },
})
const summary = computed({
  get: () => metadata.value.summary,
  set: (summary) => {
    metadata.value = { ...metadata.value, summary }
  },
})
const isEditing = ref(true)
const settingsOpen = ref(false)
const tocOpen = ref(false)
const compact = useMediaQuery('(max-width: 1199px)')
const canvasPane = useTemplateRef<HTMLElement>('canvasPane')
const { width: canvasWidth, height: canvasHeight } = useElementSize(canvasPane)
const desktopTocVisible = computed(() => canvasWidth.value >= 1050)
watch(desktopTocVisible, (visible) => {
  if (visible) {
    tocOpen.value = false
  }
})
const splitRoot = useTemplateRef<HTMLElement>('splitRoot')
const actionBar = useTemplateRef<HTMLElement>('actionBar')
function richPointer(event: PointerEvent) {
  if (
    event.target instanceof Element &&
    editor.value?.view.dom.contains(event.target) &&
    !event.target.closest('[data-editor-controls]')
  ) {
    const current = editor.value
    const nativeSelection = current?.view.dom.ownerDocument.getSelection()
    if (
      current &&
      event.target.closest('code[data-node-view-content]') &&
      nativeSelection?.anchorNode &&
      nativeSelection.focusNode &&
      current.view.dom.contains(nativeSelection.anchorNode) &&
      current.view.dom.contains(nativeSelection.focusNode)
    ) {
      // NodeView 的原生光标可能先于 selectionchange；先同步真实选区，避免定位元事务恢复旧光标。
      const anchor = current.view.posAtDOM(nativeSelection.anchorNode, nativeSelection.anchorOffset)
      const head = current.view.posAtDOM(nativeSelection.focusNode, nativeSelection.focusOffset)
      const selection = TextSelection.between(
        current.view.state.doc.resolve(anchor),
        current.view.state.doc.resolve(head),
      )
      if (!selection.eq(current.view.state.selection)) {
        current.view.dispatch(current.view.state.tr.setSelection(selection))
      }
    }
  }
}
const settingsDraft = reactive({ ...metadata.value })
const toast = useToast()
const fileInput = useTemplateRef<HTMLInputElement>('fileInput')
function insertFiles(files: File[]) {
  const current = editor.value
  if (!current || !files.length) {
    return false
  }
  try {
    if (!insertCanvasFiles(current, canvas.localResources, files)) {
      throw new Error('当前位置无法插入文件，请在正文段落中重试。')
    }
    current.view.focus()
  } catch (cause) {
    toast.add({
      title: '无法插入文件',
      description: cause instanceof Error ? cause.message : '请重试。',
      color: 'error',
    })
  }
  return true
}
function chooseFiles(event: Event) {
  const input = event.target as HTMLInputElement
  insertFiles(Array.from(input.files ?? []))
  input.value = ''
}
let barObserver: ResizeObserver | undefined
async function saveDemo() {
  const hadChanges = dirty.value
  const success = await canvas.saved.save()
  const successTitle = hadChanges ? '模拟保存完成' : '没有需要保存的修改'
  toast.add({
    id: 'canvas-save',
    title: success ? successTitle : '当前内容未保存',
    description: saveStatus.value,
    color: success ? 'success' : 'error',
  })
  return success
}
function workbenchKeydown(event: KeyboardEvent) {
  if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 's' || event.altKey) {
    return
  }
  const target = event.target
  if (
    target instanceof Element &&
    (splitRoot.value?.contains(target) || target.closest('[data-immersive-overlay]'))
  ) {
    event.preventDefault()
    saveDemo()
  }
}
function toggleEditing() {
  isEditing.value = !isEditing.value
  editor.value?.setEditable(isEditing.value, false)
}
function validateMetadata(state: typeof settingsDraft): FormError[] {
  const errors: FormError[] = []
  if (!state.title.trim()) {
    errors.push({ name: 'title', message: '请输入文章标题。' })
  }
  if (!state.slug.trim()) {
    errors.push({ name: 'slug', message: '请输入文章地址。' })
  }
  if (/[\s/?#]/.test(state.slug)) {
    errors.push({ name: 'slug', message: '地址不能含空白、斜杠、问号或井号。' })
  }
  if (state.summary.length > 500) {
    errors.push({ name: 'summary', message: '摘要最多 500 字。' })
  }
  return errors
}
function applySettings() {
  if (
    Object.entries(settingsDraft).some(
      ([key, value]) => metadata.value[key as keyof typeof settingsDraft] !== value,
    )
  ) {
    metadata.value = { ...settingsDraft }
  }
  settingsOpen.value = false
}
watch(settingsOpen, (open) => {
  if (open) {
    Object.assign(settingsDraft, metadata.value)
  }
})
watch(metadata, () => canvas.saved.changed(false), { deep: true })
const richEditor = useTemplateRef('richEditor')
const headings = shallowRef<EditorHeading[]>([])
let headingPositions: EditorHeading[] = []
const activeId = ref('')
const contentCollapsedIds = shallowRef(new Set<string>())
const tocCollapsedIds = shallowRef(new Set<string>())
const headingTree = computed<ReturnType<typeof editorHeadingTree>>((previous) =>
  editorHeadingTree(headings.value, previous),
)
const links = computed(() => visibleToc(headingTree.value, new Set(), new Set()))
const visibleIds = computed(
  () =>
    new Set(
      visibleToc(headingTree.value, contentCollapsedIds.value, tocCollapsedIds.value).map(
        (link) => link.id,
      ),
    ),
)
function toggleToc(id: string) {
  const next = new Set(tocCollapsedIds.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  tocCollapsedIds.value = next
}
const minimumDepth = computed(() => Math.min(6, ...headings.value.map((heading) => heading.depth)))
const markdownOptions = createCanvasMarkdownOptions()
const extensions = CANVAS_EDITOR_EXTENSIONS.map((extension) => {
  if (extension === PreservedCodeBlock) {
    return PreservedCodeBlock.extend({
      addNodeView: () =>
        VueNodeViewRenderer(ImmersiveCode, { stopEvent: stopImmersiveControlEvent }),
    })
  }
  if (extension.name === 'image') {
    return (extension as TiptapNode).extend({
      addNodeView: () => VueNodeViewRenderer(ImmersiveImage),
    })
  }
  if (extension.name === 'preservedMathBlock' || extension.name === 'preservedMathInline') {
    return (extension as TiptapNode).extend({
      draggable: false,
      addInputRules() {
        return this.type.isInline
          ? [
              nodeInputRule({
                find: /(?<![\\$])\$(?!\$)(?:\\.|[^$\n])+?\$(?!\$)$/,
                type: this.type,
                getAttributes: (match) => ({ source: match[0] }),
              }),
            ]
          : []
      },
      addNodeView: () =>
        VueNodeViewRenderer(ImmersiveMath, { stopEvent: stopImmersiveControlEvent }),
    })
  }
  return extension
})
const rejection = shallowRef<ImmersiveRejection>()
let lastRejection = ''
function rejectEdit(failure: ImmersiveRejection) {
  rejection.value = failure
  if (failure.reason !== lastRejection) {
    toast.add({
      id: 'immersive-edit-rejection',
      duration: 4500,
      actions: [{ label: '定位内容', onClick: inspectRejection }],
      title: '无法完成本次编辑',
      description: failure.reason + ' 操作已取消，原文未被修改。',
      color: 'error',
    })
    lastRejection = failure.reason
  }
}
function clearRejection() {
  toast.remove('immersive-edit-rejection')
  rejection.value = undefined
  lastRejection = ''
}
function inspectRejection() {
  void navigatePosition(rejection.value?.position ?? 1)
}
const editorExtensions = [
  Markdown.configure(markdownOptions),
  ...extensions,
  ImmersiveChapters.configure({
    notice: (description) => toast.add({ title: '章节未折叠', description, color: 'warning' }),
  }),
  ImmersiveDecorations,
  ImmersivePositionHighlight,
  ImmersiveSelection,
  ImmersiveParagraphInput.configure({
    notice: (description) =>
      toast.add({ title: 'Markdown 格式限制', description, color: 'warning' }),
  }),
  ImmersiveMarkdownGuard.configure({ reject: rejectEdit, roundtrip: false }),
]
let identity = 0
let frame = 0
const toolbarItems: EditorToolbarItem[][] = [
  [
    { kind: 'mark', mark: 'bold', icon: 'i-lucide-bold', tooltip: { text: '加粗' } },
    { kind: 'mark', mark: 'italic', icon: 'i-lucide-italic', tooltip: { text: '斜体' } },
    { kind: 'mark', mark: 'strike', icon: 'i-lucide-strikethrough', tooltip: { text: '删除线' } },
    {
      icon: 'i-lucide-link',
      tooltip: { text: '链接' },
      onClick: () => {
        if (editor.value) {
          openImmersiveLink(editor.value)
        }
      },
    },
    { kind: 'mark', mark: 'code', icon: 'i-lucide-code', tooltip: { text: '行内代码' } },
  ],
  [
    {
      icon: 'i-lucide-heading',
      items: [1, 2, 3, 4, 5, 6].map((level) => ({
        kind: 'heading' as const,
        level: level as 1 | 2 | 3 | 4 | 5 | 6,
        label: '标题 ' + level,
      })),
    },
  ],
  [
    { kind: 'bulletList', icon: 'i-lucide-list' },
    { kind: 'blockquote', icon: 'i-lucide-quote' },
    { kind: 'undo', icon: 'i-lucide-undo-2' },
    { kind: 'redo', icon: 'i-lucide-redo-2' },
  ],
]
function insertTouchBlock() {
  if (!editor.value?.isEditable) {
    return
  }
  const resolved = editor.value.state.selection.$from
  openImmersiveMenu(editor.value, resolved.depth ? resolved.before(1) : 0)
}

// 1. 目录读取编辑器现有文档，不为标题另外解析 Markdown；普通输入不更新目录树。
function updateHeadings({ transaction }: { transaction?: Transaction } = {}) {
  if (
    !editor.value ||
    (transaction && !transaction.docChanged && !transaction.getMeta(IMMERSIVE_CHAPTER_KEY))
  ) {
    return
  }
  const chapterState = immersiveChapters(editor.value.state)
  if (chapterState && contentCollapsedIds.value !== chapterState.collapsed) {
    contentCollapsedIds.value = chapterState.collapsed
  }
  const next =
    chapterState?.chapters ??
    collectEditorHeadings(
      editor.value.state.doc,
      headingPositions,
      transaction?.mapping,
      () => 'editor-heading-' + ++identity,
    )
  headingPositions = next
  if (transaction?.getMeta(IMMERSIVE_CHAPTER_KEY)) {
    followPage()
  }
  if (
    next.length !== headings.value.length ||
    next.some((heading, index) => {
      const previous = headings.value[index]
      return (
        heading.id !== previous?.id ||
        heading.text !== previous.text ||
        heading.depth !== previous.depth
      )
    })
  ) {
    headings.value = next
  }
}
function selectHeading() {
  const position = editor.value?.state.selection.from ?? 0
  activeId.value = headingPositions.findLast((heading) => heading.position <= position)?.id ?? ''
}
watch(
  () => richEditor.value?.editor,
  (instance, _, cleanup) => {
    canvas.attach(instance ? markRaw(instance) : null)
    if (!editor.value) {
      return
    }
    const current = editor.value
    updateHeadings()
    selectHeading()
    const recoverRejection = ({ transaction }: { transaction: Transaction }) => {
      if (rejection.value && (transaction.docChanged || transaction.selectionSet)) {
        clearRejection()
      }
    }
    current.on('transaction', recoverRejection)
    current.on('transaction', updateHeadings)
    current.on('selectionUpdate', selectHeading)
    cleanup(() => {
      current.off('transaction', recoverRejection)
      current.off('transaction', updateHeadings)
      current.off('selectionUpdate', selectHeading)
    })
  },
)
let navigationTimer: ReturnType<typeof setTimeout> | undefined
async function navigatePosition(position: number) {
  const current = editor.value
  if (!current) {
    return
  }
  current.commands.command(revealImmersivePosition(position))
  current.view.dispatch(
    current.state.tr.setSelection(
      TextSelection.near(
        current.state.doc.resolve(Math.min(position, current.state.doc.content.size)),
      ),
    ),
  )
  await nextTick()
  const block = current.state.selection.$from.depth ? current.state.selection.$from.before(1) : 0
  const element = current.view.nodeDOM(block)
  if (element instanceof HTMLElement && canvasPane.value) {
    canvasPane.value.scrollTop +=
      element.getBoundingClientRect().top - canvasPane.value.getBoundingClientRect().top - 24
  }
  current.view.focus()
  current.view.dispatch(current.state.tr.setMeta(IMMERSIVE_POSITION_KEY, { from: block }))
  clearTimeout(navigationTimer)
  navigationTimer = setTimeout(() => {
    if (!current.isDestroyed) {
      current.view.dispatch(current.state.tr.setMeta(IMMERSIVE_POSITION_KEY, null))
    }
  }, 1200)
}
async function navigate(id: string) {
  tocOpen.value = false
  await nextTick()
  const heading = headingPositions.find((item) => item.id === id)
  if (!heading || !editor.value) {
    return
  }
  const currentHeading = headingPositions.find((item) => item.id === id)
  if (!currentHeading) {
    return
  }
  await navigatePosition(currentHeading.position + 1)
  activeId.value = id
}
// 2. 所有模式只监听内部画布；根据实际可见标题更新目录，卸载时释放监听。
function followPage() {
  if (frame) {
    return
  }
  frame = requestAnimationFrame(() => {
    frame = 0
    if (!editor.value || !headingPositions.length) {
      return
    }
    const headerHeight = canvasPane.value?.getBoundingClientRect().top ?? 0
    const chapters = immersiveChapters(editor.value.state)
    const visibleHeadings = headingPositions.filter(
      (heading) =>
        !chapters?.chapters.some(
          (chapter) =>
            chapters.collapsed.has(chapter.id) &&
            heading.position >= chapter.bodyFrom &&
            heading.position < chapter.end,
        ),
    )
    if (!visibleHeadings.length) {
      return
    }
    let low = 0
    let high = visibleHeadings.length - 1
    let current = visibleHeadings[0]!.id
    while (low <= high) {
      const middle = (low + high) >>> 1
      const heading = visibleHeadings[middle]!
      const element = editor.value.view.nodeDOM(heading.position)
      if (
        element instanceof HTMLElement &&
        element.getBoundingClientRect().top <= headerHeight + 32
      ) {
        current = heading.id
        low = middle + 1
      } else {
        high = middle - 1
      }
    }
    activeId.value = current
  })
}
watch(
  canvasPane,
  (_, __, cleanup) => {
    const scrollTarget = canvasPane.value
    scrollTarget?.addEventListener('scroll', followPage, { passive: true })
    cleanup(() => scrollTarget?.removeEventListener('scroll', followPage))
  },
  { immediate: true, flush: 'post' },
)
onMounted(() => {
  document.addEventListener('keydown', workbenchKeydown)
  window.addEventListener('beforeunload', beforeUnload)
  barObserver = new ResizeObserver(([entry]) => {
    if (entry && splitRoot.value) {
      splitRoot.value.style.setProperty(
        '--workbench-bar-height',
        entry.borderBoxSize[0]?.blockSize + 'px',
      )
    }
  })
  if (actionBar.value) {
    barObserver.observe(actionBar.value)
  }
})
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', beforeUnload)
  document.removeEventListener('keydown', workbenchKeydown)
  canvas.dispose()
  clearTimeout(navigationTimer)
  barObserver?.disconnect()
  cancelAnimationFrame(frame)
  finishLeave(false)
})
const leaveOpen = ref(false)
let resolveLeave: ((allow: boolean) => void) | undefined
function requestLeave(): Promise<boolean> {
  if (!dirty.value) {
    return Promise.resolve(true)
  }
  if (resolveLeave) {
    return Promise.resolve(false)
  }
  leaveOpen.value = true
  return new Promise((resolve) => {
    resolveLeave = resolve
  })
}
function finishLeave(allow: boolean) {
  leaveOpen.value = false
  resolveLeave?.(allow)
  resolveLeave = undefined
}
async function saveAndLeave() {
  const pendingLeave = resolveLeave
  if ((await saveDemo()) && !dirty.value && resolveLeave === pendingLeave) {
    finishLeave(true)
  }
}
onBeforeRouteLeave(requestLeave)
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
async function exportDemo() {
  try {
    const markdown = await canvas.exportMarkdown()
    const url = URL.createObjectURL(new Blob([markdown], { type: 'text/markdown;charset=utf-8' }))
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = metadata.value.slug.replace(/[^\w-]/g, '-') + '.md'
    anchor.hidden = true
    document.body.append(anchor)
    try {
      anchor.click()
    } finally {
      anchor.remove()
      setTimeout(() => URL.revokeObjectURL(url), BLOG_DOWNLOAD_URL_RELEASE_DELAY_MS)
    }
  } catch (cause) {
    toast.add({
      title: '无法导出',
      description: cause instanceof Error ? cause.message : '转换失败，正文未改变。',
      color: 'error',
    })
  }
}
defineExpose({ requestLeave })
</script>
<template>
  <main ref="splitRoot" class="site-container site-page blog-reading-page immersive-workbench">
    <div ref="overlayHost" class="workbench-overlays" />
    <div ref="actionBar" class="workbench-actions">
      <div class="flex min-w-0 flex-wrap items-center gap-2 text-sm">
        <UIcon name="i-lucide-file-pen-line" class="size-4" />
        <span class="font-medium">沉浸写作</span>
        <UBadge color="neutral" variant="subtle" size="sm">草稿 · 仅本页</UBadge>
        <span class="hidden text-xs text-muted sm:inline">{{ saveStatus }}</span>
      </div>
      <div class="flex flex-wrap items-center gap-1">
        <input
          ref="fileInput"
          type="file"
          multiple
          hidden
          aria-label="选择本地图片或附件"
          @change="chooseFiles"
        />
        <UButton
          v-if="isEditing"
          icon="i-lucide-paperclip"
          color="neutral"
          variant="ghost"
          aria-label="插入图片或附件"
          @mousedown.prevent
          @click="fileInput?.click()"
        />
        <UButton
          icon="i-lucide-save"
          color="neutral"
          variant="ghost"
          aria-label="保存演示草稿"
          @click="saveDemo"
        />
        <UButton
          color="neutral"
          variant="ghost"
          icon="i-lucide-download"
          aria-label="导出 Markdown"
          @click="exportDemo"
        />
        <UButton
          v-if="compact && isEditing"
          icon="i-lucide-plus"
          color="neutral"
          variant="ghost"
          aria-label="插入内容"
          @click="insertTouchBlock"
        />
        <UButton
          color="neutral"
          variant="ghost"
          icon="i-lucide-settings-2"
          aria-label="文章设置"
          @click="settingsOpen = true"
        />
        <UButton
          :icon="isEditing ? 'i-lucide-check' : 'i-lucide-pencil'"
          size="sm"
          @click="toggleEditing"
          >{{ isEditing ? '完成编辑' : '继续编辑' }}</UButton
        >
      </div>
    </div>
    <div class="workbench-panes">
      <div class="canvas-stage">
        <section
          ref="canvasPane"
          class="canvas-pane"
          :style="{ '--immersive-canvas-height': canvasHeight + 'px' }"
          aria-label="沉浸正文画布"
          @pointerup="richPointer"
        >
          <article
            class="blog-reading-layout blog-reading-with-header workbench-article"
            :class="{ 'has-desktop-toc': desktopTocVisible }"
          >
            <header class="blog-reading-title min-w-0">
              <h1 class="site-page-title break-words">{{ title }}</h1>
              <p class="my-6 text-lg leading-8 text-muted">{{ summary }}</p>
            </header>
            <aside v-if="desktopTocVisible" class="blog-reading-toc">
              <BlogTocResizeHandle />
              <h2 class="blog-toc-title">本篇目录</h2>
              <BlogTocTree
                :links="links"
                :selected="activeId"
                :minimum-depth="minimumDepth"
                :toc-collapsed-ids="tocCollapsedIds"
                :content-collapsed-ids="contentCollapsedIds"
                :visible-ids="visibleIds"
                :touch-directory="false"
                @toggle="toggleToc"
                @navigate="navigate"
              />
            </aside>
            <div class="blog-renderer workbench-body min-w-0">
              <UEditor
                ref="richEditor"
                v-slot="{ editor: currentEditor }"
                v-memo="[isEditing]"
                :model-value="initialDocument"
                content-type="json"
                :markdown="markdownOptions"
                :starter-kit="CANVAS_STARTER_KIT"
                :extensions="editorExtensions"
                :editable="isEditing"
                :image="false"
                :mention="false"
                :editor-props="{
                  attributes: { 'aria-label': '直接编辑文章正文', spellcheck: 'false' },
                  handlePaste: (_view, event) => {
                    const files = Array.from(event.clipboardData?.files ?? [])
                    if (!files.length) {
                      return false
                    }
                    event.preventDefault()
                    return insertFiles(files)
                  },
                  handleDrop: (view, event, moved) => {
                    const files = Array.from(event.dataTransfer?.files ?? [])
                    if (moved || view.dragging || !files.length) {
                      return false
                    }
                    event.preventDefault()
                    const position = view.posAtCoords({ left: event.clientX, top: event.clientY })
                    if (position) {
                      view.dispatch(
                        view.state.tr.setSelection(
                          TextSelection.near(view.state.doc.resolve(position.pos)),
                        ),
                      )
                    }
                    return insertFiles(files)
                  },
                }"
                :ui="{
                  root: 'immersive-editor-root',
                  content: 'min-w-0',
                  base: 'blog-renderer min-h-80 focus:outline-none',
                }"
              >
                <EditorImmersiveTools
                  :editor="currentEditor"
                  :enabled="isEditing"
                  :toolbar-items="toolbarItems"
                />
              </UEditor>
              <p class="workbench-end text-xs text-muted">
                {{ saveStatus }}
              </p>
            </div>
          </article>
        </section>
        <UButton
          v-if="!desktopTocVisible"
          class="workbench-mobile-toc"
          icon="i-lucide-list-tree"
          color="neutral"
          variant="outline"
          aria-label="打开文章目录"
          @click="tocOpen = true"
        />
        <USlideover
          v-model:open="tocOpen"
          :portal="false"
          :modal="false"
          :overlay="false"
          :transition="false"
          side="left"
          title="本篇目录"
          :content="{
            onCloseAutoFocus: (event: Event) => event.preventDefault(),
          }"
          :ui="{ content: 'immersive-local-toc z-30 shadow-lg', body: 'min-h-0 overflow-auto' }"
        >
          <template #body
            ><BlogTocTree
              :links="links"
              :selected="activeId"
              :minimum-depth="minimumDepth"
              :toc-collapsed-ids="tocCollapsedIds"
              :content-collapsed-ids="contentCollapsedIds"
              :visible-ids="visibleIds"
              :touch-directory="false"
              @toggle="toggleToc"
              @navigate="navigate"
          /></template>
        </USlideover>
      </div>
    </div>

    <UModal
      v-model:open="settingsOpen"
      title="文章设置"
      description="这里修改的是当前演示草稿，不会调用保存或发布 API。"
      :ui="{ content: 'sm:max-w-2xl' }"
    >
      <template #body>
        <UForm
          :state="settingsDraft"
          :validate="validateMetadata"
          class="space-y-5"
          @submit="applySettings"
        >
          <div class="flex items-center justify-between">
            <span class="text-sm text-muted">文章状态</span
            ><UBadge color="neutral" variant="subtle">草稿 · 本地演示</UBadge>
          </div>
          <UFormField name="title" label="文章标题" required
            ><UInput v-model="settingsDraft.title" class="w-full"
          /></UFormField>
          <UFormField
            name="slug"
            label="Slug"
            description="文章地址独立编辑，不会随标题自动改变。"
            required
            ><UInput v-model="settingsDraft.slug" class="w-full"
          /></UFormField>
          <UFormField name="summary" label="文章摘要" :hint="settingsDraft.summary.length + '/500'"
            ><UTextarea v-model="settingsDraft.summary" autoresize :rows="3" class="w-full"
          /></UFormField>
          <div class="space-y-3 border-t border-default pt-4">
            <h3 class="text-sm font-semibold">发布与下载</h3>
            <USwitch
              v-model="settingsDraft.allowDownload"
              label="允许下载 Markdown"
              description="复用现有文章设置；这里只更新演示数据。"
            />
            <UCheckbox
              v-model="settingsDraft.remoteRightsConfirmed"
              label="已确认远程图片与附件的使用权"
            />
            <USwitch v-model="simulateFailure" label="模拟保存失败（用于验证重试）" />
            <USwitch
              v-model="simulateResourceFailure"
              label="模拟图片与附件处理失败"
              description="文件仅留在本页，不上传服务器；刷新后不可恢复。"
            />
          </div>
          <div class="flex justify-end gap-2 border-t border-default pt-4">
            <UButton type="button" color="neutral" variant="ghost" @click="settingsOpen = false"
              >取消</UButton
            ><UButton type="submit">应用到演示草稿</UButton>
          </div>
        </UForm>
      </template>
    </UModal>
    <UModal
      :open="leaveOpen"
      title="有未保存修改"
      description="当前仅为模拟保存，不会上传服务器。"
      :dismissible="false"
      @update:open="
        (open) => {
          if (!open) {
            finishLeave(false)
          }
        }
      "
    >
      <template #footer>
        <UButton :disabled="saving" @click="saveAndLeave">保存并离开</UButton>
        <UButton color="error" variant="soft" :disabled="saving" @click="finishLeave(true)"
          >放弃并离开</UButton
        >
        <UButton color="neutral" variant="ghost" @click="finishLeave(false)">取消</UButton>
      </template>
    </UModal>
  </main>
</template>
<style scoped>
.workbench-body :deep(.immersive-chapter-toggle) {
  vertical-align: middle;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.75rem;
  height: 1.75rem;
  margin-inline-end: 0.4rem;
  padding: 0.25rem;
  border: 0;
  border-radius: 0.375rem;
  color: var(--ui-text-muted);
  background: transparent;
  cursor: pointer;
}
.workbench-body :deep(.canvas-alert) {
  margin-block: 1.25rem;
  padding: 0.75rem 1rem;
  border-inline-start: 3px solid var(--ui-primary);
  background: color-mix(in srgb, var(--ui-primary) 6%, transparent);
}
.workbench-body :deep(.canvas-alert::before) {
  content: attr(data-canvas-alert);
  display: block;
  margin-bottom: 0.5rem;
  color: var(--ui-primary);
  font-weight: 600;
  font-size: 0.875rem;
}
.workbench-body :deep(.canvas-alert[data-canvas-alert='WARNING']),
.workbench-body :deep(.canvas-alert[data-canvas-alert='CAUTION']) {
  border-inline-start-color: var(--ui-warning);
}
.workbench-body :deep(.immersive-chapter-toggle:hover) {
  background: var(--ui-bg-elevated);
  color: var(--ui-text);
}
.workbench-body :deep(.immersive-chapter-toggle:focus-visible) {
  outline: 2px solid var(--ui-primary);
  outline-offset: 2px;
}
.workbench-body :deep(.immersive-chapter-toggle svg) {
  width: 1rem;
  height: 1rem;
}

.canvas-stage :deep(.immersive-local-toc) {
  position: absolute !important;
  inset: 0 auto 0 0 !important;
  width: min(22rem, 100%) !important;
}
.canvas-stage {
  position: relative;
  min-width: 0;
  min-height: 0;
  height: 100%;
}
.workbench-body :deep(.immersive-selected-block::selection),
.workbench-body :deep(.immersive-selected-block *::selection) {
  background: transparent;
}
.workbench-body :deep(.immersive-column-edge) {
  box-shadow: inset 0 3px var(--site-accent);
}
.workbench-body :deep(.immersive-row-edge) {
  box-shadow: inset 3px 0 var(--site-accent);
}
.workbench-body :deep(.immersive-table-selected) {
  border-color: color-mix(in srgb, var(--site-accent) 40%, var(--ui-border));
}
.workbench-body :deep(.selectedCell) {
  background: color-mix(in srgb, var(--site-accent) 14%, transparent);
}

.immersive-workbench {
  --workbench-bar-height: 4.5rem;
  flex: 1;
  min-height: 0;
  max-width: none;
  padding-block: 0;
  display: flex;
  flex-direction: column;
}
.workbench-overlays {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 50;
}
.workbench-panes {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  flex: 1;
  min-height: 0;
}
.workbench-actions {
  position: relative;
  flex: 0 0 auto;
  z-index: 20;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.65rem;
  padding-block: 0.85rem;
  margin-bottom: 0;
  background: var(--site-bg);
  border-bottom: 1px solid var(--ui-border);
}
.workbench-article {
  position: relative;
}
.canvas-pane {
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: auto;
  padding: clamp(0.85rem, 2vw, 2rem);
  overflow-anchor: none;
}
.workbench-body :deep(.immersive-editor-root) {
  border: 0;
  border-radius: 0;
  background: transparent;
}
.workbench-body :deep(.tiptap) {
  padding: 0;
  font-size: 1.0625rem;
  line-height: 1.85;
}
.workbench-body :deep(.tiptap > :first-child) {
  margin-top: 0;
}
.workbench-body :deep(.tiptap :is(h1, h2, h3, h4, h5, h6)) {
  scroll-margin-top: 1rem;
}
.workbench-body :deep(.tiptap pre code),
.workbench-body :deep(.tiptap pre code span) {
  border: 0;
  outline: none;
  box-shadow: none;
  background: transparent;
}
.workbench-body :deep(.tableWrapper) {
  max-width: 100%;
  overflow: auto;
}
.workbench-body :deep(table) {
  width: 100%;
  border-collapse: collapse;
}
.workbench-body :deep(th),
.workbench-body :deep(td) {
  border: 1px solid var(--ui-border);
  padding: 0.65rem 0.85rem;
  min-width: 5rem;
}
.workbench-body :deep(th) {
  background: var(--ui-bg-elevated);
}
.workbench-body :deep(ul:has(> li[data-checked])) {
  list-style: none;
  padding-left: 0;
}
.workbench-body :deep(li[data-checked]) {
  display: flex;
  align-items: baseline;
  gap: 0.65rem;
}
.workbench-body :deep(li[data-checked] > div) {
  min-width: 0;
  flex: 1;
}
.workbench-body :deep(li[data-checked] p) {
  margin-block: 0.25rem;
}
.workbench-body :deep(.immersive-selected-block),
.workbench-body :deep(.ProseMirror-selectednode) {
  outline: 2px solid var(--site-accent);
  outline-offset: 3px;
  background-color: color-mix(in srgb, var(--site-accent) 10%, transparent);
  border-radius: 0.4rem;
}
.workbench-body :deep(.immersive-position-flash) {
  animation: immersive-locate 1.1s ease-out;
  border-radius: 0.25rem;
}
@keyframes immersive-locate {
  from {
    background-color: color-mix(in srgb, var(--site-accent) 16%, transparent);
  }
  to {
    background-color: transparent;
  }
}
.workbench-body :deep(.immersive-math-node.ProseMirror-selectednode),
.workbench-body :deep(.immersive-math-node.immersive-selected-block) {
  outline: none;
  background: transparent;
  border-radius: 0;
}
.workbench-body :deep(.editor-image img) {
  max-height: 18rem;
  width: auto;
}
.workbench-mobile-toc {
  position: absolute;
  top: 0.75rem;
  right: 1rem;
  width: 2.5rem;
  height: 2.5rem;
  z-index: 5;
}
.workbench-end {
  margin-top: 4rem;
  padding-top: 1.5rem;
  border-top: 1px solid var(--ui-border);
}
.workbench-article > .blog-reading-toc {
  top: 1rem;
}
.workbench-article :deep(.blog-toc-scroll) {
  max-height: max(6rem, calc(var(--immersive-canvas-height) - 3rem));
}
.workbench-article {
  display: block;
}
.workbench-article.has-desktop-toc {
  display: grid;
  grid-template-columns: var(--blog-toc-width, 18rem) minmax(0, 1fr);
}
.workbench-article.has-desktop-toc > .blog-reading-title {
  grid-area: 1 / 2;
  padding-inline-end: 0;
}
.workbench-article.has-desktop-toc > .workbench-body {
  grid-area: 2 / 2;
}
.workbench-article.has-desktop-toc > .blog-reading-toc {
  grid-area: 1 / 1 / span 2;
  justify-self: stretch;
  border: 0;
  border-inline-end: 1px solid var(--ui-border);
  padding: 0 0.75rem 0 0;
}

@media (max-width: 1199px) {
  .workbench-article > .blog-reading-title {
    padding-right: 3rem;
  }
}
@media (max-width: 599px) {
  .workbench-actions {
    gap: 0.5rem;
  }
  .workbench-actions > div:last-child {
    margin-left: auto;
  }
}
.canvas-pane {
  scrollbar-width: thin;
  scrollbar-color: color-mix(in srgb, var(--ui-text-muted) 28%, transparent) transparent;
  scrollbar-gutter: stable;
}
.canvas-pane:hover {
  scrollbar-color: color-mix(in srgb, var(--ui-text-muted) 50%, transparent) transparent;
}
.canvas-pane::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
.canvas-pane::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--ui-text-muted) 35%, transparent);
  border-radius: 1rem;
}
</style>
