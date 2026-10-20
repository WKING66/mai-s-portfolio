<script setup lang="ts">
import {
  ImmersiveMarkdownGuard,
  type ImmersiveRejection,
} from '../../utils/editor/immersiveCapabilities'
import { indentMarkdownSource } from '../../utils/editor/immersiveSourceIndent'
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
import {
  BLOG_EDITOR_EXTENSIONS,
  EDITOR_STARTER_KIT,
  PreservedCodeBlock,
} from '../../utils/editor/markdownExtensions'
import { Markdown } from '@tiptap/markdown'
import {
  createImmersiveMarkdownOptions,
  createImmersiveMarkdownManager,
} from '../../utils/editor/immersiveMarkdown'
import {
  collectEditorHeadings,
  editorHeadingTree,
  type EditorHeading,
} from '../../utils/editor/editorOutline'
import ImmersiveCode from './ImmersiveCode.vue'
import ImmersiveMath from './ImmersiveMath.vue'
import ImmersiveImage from './ImmersiveImage.vue'
import ImmersivePreserved from './ImmersivePreserved.vue'
import { nodeInputRule } from '@tiptap/core'
import { openImmersiveLink } from '../../utils/editor/immersiveLinks'
import { openImmersiveMenu } from '../../utils/editor/immersiveCommands'
import { ImmersiveParagraphInput } from '../../utils/editor/immersiveParagraphInput'
import { ImmersiveSelection } from '../../utils/editor/immersiveSelection'
import { ImmersivePositionHighlight } from '../../utils/editor/immersivePositionHighlight'
import { ImmersiveDecorations } from '../../utils/editor/immersiveDecorations'
import { BLOG_RESOURCES } from '../../utils/blogResources'
import type { DocumentResource } from '../../api/blogs'
import { useImmersiveDraft } from '../../composables/useImmersiveDraft'
import { inspectImmersiveSource } from '../../utils/editor/immersiveDraft'
import {
  IMMERSIVE_CODE_THEMES,
  IMMERSIVE_OVERLAY_HOST,
  stopImmersiveControlEvent,
} from '../../utils/editor/immersiveContext'
import { useImmersivePosition, type ImmersiveAnchor } from '../../composables/useImmersivePosition'

const props = defineProps<{
  modelValue: string
  resources?: DocumentResource[]
  backupStatus?: string
  persistLocal?: () => boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [markdown: string] }>()
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
const localResourceUris = () =>
  new Set(
    (props.resources ?? [])
      .filter((item) => item.resourceUrl?.startsWith('/__poc/editor-fixture-resource'))
      .map((item) => item.originalUri)
      .filter((uri): uri is string => Boolean(uri)),
  )
const initialIssue = inspectImmersiveSource(props.modelValue, undefined, localResourceUris())
const initialMarkdown = initialIssue ? '' : props.modelValue
// UEditor 即时事件使用原生 JSON；草稿、源码和保存仍执行 Markdown 往返校验。
const initialDocument = markRaw(createImmersiveMarkdownManager().parse(initialMarkdown))
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
const draft = useImmersiveDraft(
  props.modelValue,
  (value) => emit('update:modelValue', value),
  isEditing,
  localResourceUris,
)
const { source, issue, sourcePending, checking, unvalidated, editor } = draft
const sourceOpen = ref(Boolean(initialIssue))
if (initialIssue) {
  issue.value = initialIssue
  sourcePending.value = true
}
const settingsOpen = ref(false)
const tocOpen = ref(false)
const compact = useMediaQuery('(max-width: 1199px)')
const sourceCompact = useMediaQuery('(max-width: 899px)')
const mobilePane = ref<'source' | 'canvas'>('source')
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
const sourceInput = useTemplateRef<HTMLTextAreaElement>('sourceInput')
const positions = useImmersivePosition({
  editor,
  source,
  prepared: draft.positionMap,
  pending: sourcePending,
  split: sourceOpen,
  sourceElement: sourceInput,
  canvas: canvasPane,
  bar: actionBar,
  reveal: (position) => {
    editor.value?.commands.command(revealImmersivePosition(position))
  },
})
let modeAnchor: ImmersiveAnchor | undefined
function rememberModePosition() {
  modeAnchor = sourcePending.value ? undefined : positions.capture()
}
async function sourceAction(event?: Event) {
  if (sourceComposing.value) {
    return
  }
  if (
    event instanceof KeyboardEvent &&
    ![
      'ArrowLeft',
      'ArrowRight',
      'ArrowUp',
      'ArrowDown',
      'Home',
      'End',
      'PageUp',
      'PageDown',
    ].includes(event.key) &&
    !(event.key.toLowerCase() === 'a' && (event.ctrlKey || event.metaKey))
  ) {
    return
  }
  if (sourcePending.value && !(await draft.applySource())) {
    return
  }
  positions.sourceAction()
}
let positionFrame = 0
function scheduleRichPosition(force = false) {
  cancelAnimationFrame(positionFrame)
  // Selection 更新结束后再派发定位装饰，避免嵌套事务重置浏览器尚未提交的选区。
  positionFrame = requestAnimationFrame(async () => {
    positionFrame = 0
    const instance = editor.value
    const document = instance?.state.doc
    const selection = instance?.state.selection
    await draft.flushRich()
    if (
      instance &&
      instance === editor.value &&
      instance.state.doc === document &&
      selection?.eq(instance.state.selection)
    ) {
      positions.richAction(force)
    }
  })
}
function followEditPosition(payload?: { transaction?: Transaction }) {
  if (!payload?.transaction?.docChanged) {
    scheduleRichPosition()
  }
}
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
    scheduleRichPosition()
  }
}
const sourceWidth = ref(40)
const publishOpen = ref(false)
const settingsDraft = reactive({ ...metadata.value })
const toast = useToast()
const sourceComposing = ref(false)
let barObserver: ResizeObserver | undefined
let pointer: number | undefined
let dragLeft = 0
let dragWidth = 1
const paneStyle = computed(() => ({ '--source-width': sourceWidth.value + '%' }))
const sourceLabel = computed(() => (sourceOpen.value ? '退出源码分栏' : '源码'))
async function toggleSource() {
  if (!(await draft.ensureCurrent())) {
    return
  }
  const anchor = modeAnchor ?? positions.capture()
  modeAnchor = undefined
  sourceOpen.value = !sourceOpen.value
  mobilePane.value = 'source'
  tocOpen.value = false
  await nextTick()
  await positions.restore(anchor)
  followPage()
}
async function switchMobilePane(pane: 'source' | 'canvas') {
  if (!(await draft.ensureCurrent())) {
    return
  }
  const anchor = modeAnchor ?? positions.capture(mobilePane.value === 'source')
  modeAnchor = undefined
  mobilePane.value = pane
  await positions.restore(anchor)
}
async function saveDemo() {
  if (!(await draft.ensureCurrent())) {
    toast.add({
      id: 'immersive-save-pending',
      title: '当前输入尚未备份',
      description: issue.value || '请结束当前输入后重试。',
      color: 'warning',
    })
    return
  }
  await nextTick()
  const backedUp = props.persistLocal?.()
  toast.add({
    title: backedUp ? '已备份到本机' : '当前为演示模式',
    description: backedUp
      ? '当前草稿仅保存在本机，未提交服务器。'
      : props.backupStatus || '当前草稿仅保留在页面中。',
  })
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
function sourceKeydown(event: KeyboardEvent) {
  const input = sourceInput.value
  if (
    event.key !== 'Tab' ||
    event.ctrlKey ||
    event.metaKey ||
    event.altKey ||
    event.isComposing ||
    sourceComposing.value ||
    !isEditing.value ||
    !input
  ) {
    return
  }
  event.preventDefault()
  const edit = indentMarkdownSource(
    input.value,
    input.selectionStart,
    input.selectionEnd,
    event.shiftKey,
  )
  if (input.value.slice(edit.from, edit.to) === edit.text) {
    return
  }
  const direction = input.selectionDirection
  input.setSelectionRange(edit.from, edit.to)
  // 原生插入保留浏览器 textarea 撤销栈，并由 input 事件进入同一源码同步流程。
  if (document.execCommand('insertText', false, edit.text)) {
    input.setSelectionRange(edit.anchor, edit.head, direction)
  }
}
function onSourceInput(event: Event) {
  draft.updateSource((event.target as HTMLTextAreaElement).value, sourceComposing.value)
}
function endSourceComposition(event: CompositionEvent) {
  sourceComposing.value = false
  draft.updateSource((event.target as HTMLTextAreaElement).value)
}
async function toggleEditing() {
  if (!(await draft.ensureCurrent())) {
    return
  }
  isEditing.value = !isEditing.value
}
function resizeStart(event: PointerEvent) {
  if (event.button !== 0 || !splitRoot.value) {
    return
  }
  event.preventDefault()
  const rect = splitRoot.value.getBoundingClientRect()
  dragLeft = rect.left
  dragWidth = rect.width
  pointer = event.pointerId
  ;(event.currentTarget as HTMLElement).setPointerCapture(pointer)
}
function resizeMove(event: PointerEvent) {
  if (pointer === event.pointerId) {
    sourceWidth.value = Math.max(25, Math.min(60, ((event.clientX - dragLeft) / dragWidth) * 100))
  }
}
function resizeEnd() {
  pointer = undefined
}
function resizeKeyboard(event: KeyboardEvent) {
  if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
    sourceWidth.value = Math.max(
      25,
      Math.min(60, sourceWidth.value + (event.key === 'ArrowRight' ? 2 : -2)),
    )
  } else if (event.key === 'Enter' || event.key === 'Home') {
    sourceWidth.value = 40
  } else {
    return
  }
  event.preventDefault()
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
function confirmPublishDemo() {
  publishOpen.value = false
  toast.add({ title: '演示结束', description: '没有调用保存或发布接口。' })
}
function applySettings() {
  metadata.value = { ...settingsDraft }
  settingsOpen.value = false
}
watch(settingsOpen, (open) => {
  if (open) {
    Object.assign(settingsDraft, metadata.value)
  }
})
watch(
  () => props.modelValue,
  (value) => {
    if (value !== source.value) {
      draft.updateSource(value)
    }
  },
)
const richEditor = useTemplateRef('richEditor')
const headings = shallowRef<EditorHeading[]>([])
let headingPositions: EditorHeading[] = []
const activeId = ref('')
const contentCollapsedIds = shallowRef(new Set<string>())
const tocCollapsedIds = shallowRef(new Set<string>())
const headingTree = computed(() => editorHeadingTree(headings.value))
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
const markdownOptions = createImmersiveMarkdownOptions()
const extensions = BLOG_EDITOR_EXTENSIONS.map((extension) => {
  if (extension === PreservedCodeBlock) {
    return PreservedCodeBlock.extend({
      addNodeView: () =>
        VueNodeViewRenderer(ImmersiveCode, { stopEvent: stopImmersiveControlEvent }),
    })
  }
  if (extension.name === 'preservedMarkdownBlock') {
    return (extension as TiptapNode).extend({
      addNodeView: () =>
        VueNodeViewRenderer(ImmersivePreserved, { stopEvent: stopImmersiveControlEvent }),
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
      actions: [{ label: '源码定位', onClick: inspectRejection }],
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
async function openPreservedSource(event: Event) {
  const position = (event as CustomEvent<{ position: number }>).detail.position
  if (!sourceOpen.value) {
    await toggleSource()
  }
  positions.navigate(position)
  sourceInput.value?.focus({ preventScroll: true })
}
async function inspectRejection() {
  if (!sourceOpen.value) {
    await toggleSource()
  }
  await positions.restore({
    position: Math.min(rejection.value?.position || 1, editor.value?.state.doc.content.size || 1),
    offset: 24,
    focus: 'source',
  })
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
  ImmersiveMarkdownGuard.configure({ reject: rejectEdit }),
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
    draft.attach(instance ? markRaw(instance) : null)
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
    current.on('selectionUpdate', followEditPosition)
    cleanup(() => {
      current.off('transaction', recoverRejection)
      current.off('transaction', updateHeadings)
      current.off('selectionUpdate', selectHeading)
      current.off('selectionUpdate', followEditPosition)
    })
  },
)
watch(isEditing, draft.updateEditable)
async function navigate(id: string) {
  tocOpen.value = false
  await nextTick()
  const heading = headingPositions.find((item) => item.id === id)
  if (!heading || !editor.value) {
    return
  }
  if (!(await draft.ensureCurrent())) {
    return
  }
  const currentHeading = headingPositions.find((item) => item.id === id)
  if (!currentHeading) {
    return
  }
  positions.navigate(currentHeading.position + 1)
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
  [sourceOpen, canvasPane],
  (_, __, cleanup) => {
    const scrollTarget = canvasPane.value
    scrollTarget?.addEventListener('scroll', followPage, { passive: true })
    cleanup(() => scrollTarget?.removeEventListener('scroll', followPage))
  },
  { immediate: true, flush: 'post' },
)
onMounted(() => {
  document.addEventListener('keydown', workbenchKeydown)
  window.addEventListener('pagehide', persistDraft)
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
function persistDraft() {
  props.persistLocal?.()
}
onBeforeUnmount(() => {
  persistDraft()
  window.removeEventListener('pagehide', persistDraft)
  document.removeEventListener('keydown', workbenchKeydown)
  draft.dispose()
  cancelAnimationFrame(positionFrame)
  positions.dispose()
  barObserver?.disconnect()
  cancelAnimationFrame(frame)
})
</script>
<template>
  <main
    ref="splitRoot"
    class="site-container site-page blog-reading-page immersive-workbench"
    :class="{ 'source-layout': sourceOpen, 'narrow-source': sourceCompact && sourceOpen }"
    :style="paneStyle"
    @pointerdown="positions.cancelRestore"
    @wheel.passive="positions.cancelRestore"
    @keydown="positions.cancelRestore"
  >
    <div ref="overlayHost" class="workbench-overlays" />
    <div ref="actionBar" class="workbench-actions">
      <div class="flex min-w-0 flex-wrap items-center gap-2 text-sm">
        <UIcon name="i-lucide-file-pen-line" class="size-4" />
        <span class="font-medium">沉浸写作</span>
        <UBadge color="neutral" variant="subtle" size="sm">草稿 · 仅本页</UBadge>
        <span class="hidden text-xs text-muted sm:inline">{{
          checking
            ? '正在同步当前输入…'
            : unvalidated
              ? '当前输入等待同步'
              : backupStatus || '未连接服务器，刷新后恢复'
        }}</span>
      </div>
      <div class="flex flex-wrap items-center gap-1">
        <UButton
          icon="i-lucide-save"
          color="neutral"
          variant="ghost"
          aria-label="保存演示草稿"
          @click="saveDemo"
        />
        <UButton
          color="neutral"
          :variant="sourceOpen ? 'soft' : 'ghost'"
          icon="i-lucide-code"
          :aria-pressed="sourceOpen"
          @pointerdown="rememberModePosition"
          @click="toggleSource"
          >{{ sourceLabel }}</UButton
        >
        <UButton
          v-if="compact && isEditing && !sourcePending"
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
      <div
        v-if="sourceOpen && sourceCompact"
        class="flex w-full gap-1"
        role="group"
        aria-label="窄屏源码工作区"
      >
        <UButton
          color="neutral"
          :variant="mobilePane === 'source' ? 'soft' : 'ghost'"
          size="sm"
          @pointerdown="rememberModePosition"
          @click="switchMobilePane('source')"
          >Markdown 源码</UButton
        >
        <UButton
          color="neutral"
          :variant="mobilePane === 'canvas' ? 'soft' : 'ghost'"
          size="sm"
          @pointerdown="rememberModePosition"
          @click="switchMobilePane('canvas')"
          >正文编辑</UButton
        >
      </div>
    </div>
    <div class="workbench-panes">
      <section
        v-show="sourceOpen && (!sourceCompact || mobilePane === 'source')"
        class="source-pane"
        aria-label="Markdown 源码工作区"
      >
        <div class="source-caption">
          <span>Markdown</span
          ><span class="min-w-0 truncate text-xs text-muted" :title="issue" role="status">{{
            issue || '与右侧同一草稿'
          }}</span>
        </div>
        <textarea
          ref="sourceInput"
          wrap="soft"
          :value="source"
          aria-label="Markdown 源码编辑"
          spellcheck="false"
          :readOnly="!isEditing"
          @focus="draft.flushRich"
          @pointerdown="draft.flushRich"
          @click="sourceAction"
          @keyup="sourceAction"
          @keydown="sourceKeydown"
          @input="onSourceInput"
          @compositionstart="sourceComposing = true"
          @compositionend="endSourceComposition"
        />
      </section>
      <div
        v-if="sourceOpen && !sourceCompact"
        class="source-separator"
        role="separator"
        tabindex="0"
        aria-label="调整源码分栏比例"
        aria-orientation="vertical"
        :aria-valuenow="Math.round(sourceWidth)"
        :aria-valuemin="25"
        :aria-valuemax="60"
        @pointerdown="resizeStart"
        @pointermove="resizeMove"
        @pointerup="resizeEnd"
        @pointercancel="resizeEnd"
        @lostpointercapture="resizeEnd"
        @keydown="resizeKeyboard"
        @dblclick="sourceWidth = 40"
      >
        <span />
      </div>
      <div v-show="!sourceOpen || !sourceCompact || mobilePane === 'canvas'" class="canvas-stage">
        <section
          v-show="!sourceOpen || !sourceCompact || mobilePane === 'canvas'"
          ref="canvasPane"
          class="canvas-pane"
          :style="{ '--immersive-canvas-height': canvasHeight + 'px' }"
          aria-label="沉浸正文画布"
          @pointerup="richPointer"
          @immersive-source-request="openPreservedSource"
        >
          <article
            class="blog-reading-layout blog-reading-with-header workbench-article"
            :class="{ 'has-desktop-toc': desktopTocVisible }"
          >
            <header class="blog-reading-title min-w-0">
              <UTextarea
                v-if="isEditing"
                v-model="title"
                autoresize
                :rows="1"
                variant="none"
                aria-label="文章标题"
                class="workbench-title w-full"
                :ui="{
                  base: 'p-0 resize-none font-bold tracking-tight text-highlighted shadow-none ring-0',
                }"
              />
              <h1 v-else class="site-page-title break-words">{{ title }}</h1>
              <p
                class="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 font-mono text-sm text-muted"
              >
                <span>2026-10-17</span
                ><span class="font-sans text-xs">{{ isEditing ? '正在编辑' : '阅读效果' }}</span>
              </p>
              <UTextarea
                v-if="isEditing"
                v-model="summary"
                autoresize
                :rows="1"
                variant="none"
                aria-label="文章摘要"
                class="workbench-summary my-6 w-full"
                :ui="{ base: 'p-0 resize-none text-lg leading-8 text-muted shadow-none ring-0' }"
              />
              <p v-else class="my-6 text-lg leading-8 text-muted">{{ summary }}</p>
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
                v-memo="[isEditing, sourcePending]"
                :model-value="initialDocument"
                content-type="json"
                :markdown="markdownOptions"
                :starter-kit="EDITOR_STARTER_KIT"
                :extensions="editorExtensions"
                :editable="isEditing && !sourcePending"
                :image="false"
                :mention="false"
                :editor-props="{
                  attributes: { 'aria-label': '直接编辑文章正文', spellcheck: 'false' },
                }"
                :ui="{
                  root: 'immersive-editor-root',
                  content: 'min-w-0',
                  base: 'blog-renderer min-h-80 focus:outline-none',
                }"
              >
                <EditorImmersiveTools
                  :editor="currentEditor"
                  :enabled="isEditing && !sourcePending"
                  :toolbar-items="toolbarItems"
                />
              </UEditor>
              <p class="workbench-end text-xs text-muted">
                {{ backupStatus || '演示草稿仅保留在当前页面，未保存到服务器。' }}
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
            <UButton
              type="button"
              color="neutral"
              variant="outline"
              icon="i-lucide-send"
              @click="publishOpen = true"
              >查看发布确认示例</UButton
            >
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
      v-model:open="publishOpen"
      title="发布确认 · 演示"
      description="正式接入时，只有确认操作才会通过现有接口发布。当前没有发送请求。"
    >
      <template #body
        ><p class="text-sm text-muted">
          发布前需要确认文章地址、正文转换和资源引用均有效。本原型始终保持草稿状态。
        </p></template
      >
      <template #footer
        ><UButton color="neutral" variant="ghost" @click="publishOpen = false">返回设置</UButton
        ><UButton @click="confirmPublishDemo">确认演示</UButton></template
      >
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
}
.canvas-stage {
  height: 100%;
}

.source-pane > textarea {
  background-color: var(--site-bg-muted);
  background-image: linear-gradient(
    color-mix(
      in srgb,
      var(--ui-primary) calc(var(--source-highlight-opacity, 0) * 14%),
      transparent
    ),
    color-mix(in srgb, var(--ui-primary) calc(var(--source-highlight-opacity, 0) * 6%), transparent)
  );
  background-repeat: no-repeat;
  background-size: 100% var(--source-range-height, 1.8em);
  background-position: 0 var(--source-line-y, -9999px);
  background-attachment: local;
}
.source-pane > textarea::selection {
  background: color-mix(in srgb, var(--ui-primary) 22%, transparent);
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
.workbench-title :deep(textarea) {
  font-size: clamp(1.875rem, 3.2vw, 3rem);
  line-height: 1.25;
  overflow: hidden;
  field-sizing: content;
  height: auto;
}
.workbench-summary :deep(textarea) {
  font-size: 1.125rem;
  overflow: hidden;
}
.workbench-title :deep(textarea:focus-visible),
.workbench-summary :deep(textarea:focus-visible) {
  outline: 1px solid color-mix(in srgb, var(--ui-primary) 40%, transparent);
  outline-offset: 5px;
  border-radius: 0.25rem;
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
.draft-issue {
  position: sticky;
  top: 0;
  z-index: 3;
  padding: 0.6rem 0.85rem;
  border-left: 2px solid var(--ui-warning);
  background: var(--ui-bg);
  color: var(--ui-text-muted);
  font-size: 0.8125rem;
}
.source-layout .workbench-actions {
  margin-bottom: 0;
}
.source-layout .workbench-panes {
  display: grid;
  grid-template-columns: minmax(0, var(--source-width)) 0.75rem minmax(0, 1fr);
  min-height: 0;
}
.source-pane {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--ui-border);
  border-top: 0;
  background: var(--ui-bg);
}
.source-caption {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.75rem 1rem;
  border-bottom: 1px solid var(--ui-border);
  font-size: 0.8125rem;
}
.source-pane > textarea {
  flex: 1;
  min-height: 0;
  width: 100%;
  resize: none;
  overflow: auto;
  border: 0;
  outline-offset: -2px;
  padding: 1rem;
  color: var(--ui-text);
  font: 0.875rem/1.8 var(--font-mono);
  tab-size: 2;
  font-variant-ligatures: none;
  font-kerning: none;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.source-separator {
  display: grid;
  place-items: center;
  cursor: col-resize;
  touch-action: none;
}
.source-separator span {
  width: 2px;
  height: 3rem;
  background: var(--ui-border-accented);
  border-radius: 1rem;
}
.source-separator:hover span,
.source-separator:focus-visible span {
  background: var(--ui-primary);
}
/* 为完整的文档加号和块手柄预留画布内侧空间，不能让加号落进源码栏后被裁切。 */
.source-layout .workbench-body :deep(.tiptap) {
  padding-inline-start: 3.5rem;
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

.narrow-source .workbench-panes {
  grid-template-columns: minmax(0, 1fr);
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
.canvas-pane,
.source-pane > textarea {
  scrollbar-width: thin;
  scrollbar-color: color-mix(in srgb, var(--ui-text-muted) 28%, transparent) transparent;
  scrollbar-gutter: stable;
}
.canvas-pane:hover,
.source-pane > textarea:hover {
  scrollbar-color: color-mix(in srgb, var(--ui-text-muted) 50%, transparent) transparent;
}
.canvas-pane::-webkit-scrollbar,
.source-pane > textarea::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
.canvas-pane::-webkit-scrollbar-thumb,
.source-pane > textarea::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--ui-text-muted) 35%, transparent);
  border-radius: 1rem;
}
</style>
