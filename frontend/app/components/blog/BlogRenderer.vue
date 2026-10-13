<script setup lang="ts">
import BlogCodeBlock from './BlogCodeBlock.vue'
import BlogImage from './BlogImage.vue'
import BlogLink from './BlogLink.vue'
import BlogToc from './BlogToc.vue'
import BlogChapter from './BlogChapter.vue'
import BlogMath from './BlogMath.vue'
import BlogMathML from './BlogMathML.vue'
import BlogMermaid from './BlogMermaid.vue'
import { defineAsyncComponent, hydrateOnIdle, hydrateOnInteraction } from 'vue'
import { ProseP, ProseH1, ProseH2, ProseH3, ProseH4, ProseStrong, ProseEm, ProseOl, ProseUl, ProseLi, ProseBlockquote, ProseHr, ProseTable, ProseThead, ProseTbody, ProseTr, ProseTh, ProseTd, ProseCode } from '#components'
import { BLOG_CHAPTER_STATE, buildBlogChapters, chapterAncestors } from '../../utils/blogChapters'
import { BLOG_RESOURCES } from '../../utils/blogResources'
import { parseBlogMarkdownInWorker } from '../../utils/blogMarkdownWorker'
import type { DocumentResource } from '../../api/blogs'
import type { TocLink } from 'comark/plugins/toc'
const props = withDefaults(defineProps<{ markdown: string; resources?: DocumentResource[]; debounce?: number; showToc?: boolean; cacheKey?: string }>(), { debounce: 150, resources: () => [], showToc: false })
const emit = defineEmits<{ toc: [items: TocLink[]] }>()
const instanceId = useId()
const hydrating = Boolean(useNuxtApp().isHydrating)
// 异步页面内 Vue useId 的 SSR/客户端前缀可能不同；业务阅读使用稳定版本键。
const key = props.cacheKey ?? instanceId
const initialMarkdown = props.markdown
provide(BLOG_RESOURCES, toRef(props, 'resources'))
const bodyRoot = useTemplateRef<HTMLElement>('bodyRoot')
// 所有叶章节共用一个尺寸观察器。使用浏览器已完成的布局结果，不逐帧强制测量。
let bodyObserver: ResizeObserver | undefined
const measuredBodies = new Map<HTMLElement, { height: number; applied: number; measured: (height: number) => void }>()
let containmentFrame = 0, measuredRootWidth = 0
function applyBodySizes() {
  containmentFrame = 0
  // 每节准备好即可使用实测高度，不能等全篇完成才跳过离屏内容的反复布局。
  for (const [element, body] of measuredBodies) if (element.dataset.readingPending !== 'true' && element.dataset.readingFolded !== 'true' && body.height > 0 && Math.abs(body.height - body.applied) > .5) { body.measured(body.height); body.applied = body.height }
}
function observeBody(element: HTMLElement, measured: (height: number) => void) {
  if (!props.showToc || props.markdown.length < 20000 || !CSS.supports('content-visibility', 'auto')) return () => {}
  const firstObserver = !bodyObserver
  bodyObserver ??= new ResizeObserver(entries => {
    let widthChanged = false, heightUpdated = false
    for (const entry of entries) {
      if (entry.target === bodyRoot.value) {
        const width = entry.borderBoxSize[0]?.inlineSize ?? entry.contentRect.width
        if (width > 0 && measuredRootWidth > 0 && Math.abs(width - measuredRootWidth) > .5) {
          // 也清除折叠后代的旧宽度缓存；它们展开后才恢复跳过，不能复用旧高度。
          for (const body of measuredBodies.values()) if (body.applied > 0) { body.measured(0); body.applied = 0 }
          widthChanged = true
        }
        if (width > 0) measuredRootWidth = width
        continue
      }
      const height = entry.borderBoxSize[0]?.blockSize ?? entry.contentRect.height
      const body = measuredBodies.get(entry.target as HTMLElement)
      if (!body) continue
      if (height > 0) { body.height = height; heightUpdated = true }
    }
    if (widthChanged) {
      cancelAnimationFrame(containmentFrame)
      // 下一帧的 ResizeObserver 收集自然排版结果，再恢复跳过；不解析 AST 或更新 Vue 子树。
      containmentFrame = requestAnimationFrame(() => { containmentFrame = requestAnimationFrame(applyBodySizes) })
    } else if (heightUpdated && !containmentFrame) containmentFrame = requestAnimationFrame(applyBodySizes)
  })
  if (firstObserver && bodyRoot.value) bodyObserver.observe(bodyRoot.value)
  measuredBodies.set(element, { height: 0, applied: 0, measured }); bodyObserver.observe(element)
  return () => { bodyObserver?.unobserve(element); measuredBodies.delete(element) }
}
const contentCollapsedIds = shallowRef(new Set<string>())
const readySections = shallowRef<Set<string> | null>(null)
const remainingHydration = shallowRef<Set<string> | null>(null)
const readingReady = computed(() => readySections.value === null && !remainingHydration.value?.size)
watch(readingReady, ready => { if (import.meta.client && ready && !containmentFrame) containmentFrame = requestAnimationFrame(applyBodySizes) })
let mountFrame = 0, finishMount: (() => void) | undefined, mountedContent = Promise.resolve()
let initialPreparation = true
const chapters = computed(() => markRaw(buildBlogChapters(document.value?.nodes || [])))
function prepareContent() {
  cancelAnimationFrameSafe(); finishMount?.()
  const deferHydration = initialPreparation && hydrating
  initialPreparation = false
  // SSR 保留完整正文。支持脚本的浏览器先跳过待准备章节的布局，水合后逐帧显示。
  // CSR 同时分散首次组件挂载；准备完毕后折叠只切换可见性，不卸载内容。
  if (!props.showToc || props.markdown.length < 20000) { readySections.value = null; remainingHydration.value = null; mountedContent = Promise.resolve(); return }
  readySections.value = new Set()
  remainingHydration.value = import.meta.server || deferHydration ? new Set(chapters.value.byId.keys()) : null
  mountedContent = new Promise(resolve => { finishMount = resolve })
  if (import.meta.client && !deferHydration) mountContent()
}
function mountContent() {
  const ids = [...chapters.value.byId.keys()]
  let index = 0
  function mountNext() {
    const ready = new Set(readySections.value || [])
    // 限制每帧两个章节，包括标题/按钮；平衡集中挂载和逐节全页布局成本。
    for (let count = 0; count < 2 && index < ids.length; count++) ready.add(ids[index++]!)
    readySections.value = ready
    if (index < ids.length) mountFrame = requestAnimationFrame(mountNext)
    else { readySections.value = null; mountFrame = 0; finishPreparing() }
  }
  mountFrame = requestAnimationFrame(mountNext)
}
onMounted(() => { if (readySections.value !== null && !mountFrame) mountContent() })
function finishPreparing() { if (readingReady.value) { finishMount?.(); finishMount = undefined } }
function hydratedChapter(id: string) {
  if (!remainingHydration.value) return
  const next = new Set(remainingHydration.value); next.delete(id); remainingHydration.value = next
  finishPreparing()
}
function cancelAnimationFrameSafe() { if (import.meta.client) cancelAnimationFrame(mountFrame); mountFrame = 0 }
function toggleChapter(id: string) {
  const next = new Set(contentCollapsedIds.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  contentCollapsedIds.value = next
}
async function reveal(id: string) {
  await mountedContent
  const next = new Set(contentCollapsedIds.value)
  for (const ancestor of chapterAncestors(id, chapters.value.byId)) next.delete(ancestor)
  contentCollapsedIds.value = next
  await nextTick()
}
provide(BLOG_CHAPTER_STATE, { idPrefix: key, collapsed: contentCollapsedIds, ready: readySections, serverRendered: import.meta.server || hydrating, hydrated: hydratedChapter, toggle: toggleChapter, observeBody })
async function parse(markdown: string) {
  if (import.meta.server) {
    const document = await (await import('../../utils/markdown')).parseBlogMarkdown(markdown)
    if (!document) throw new Error('正文解析失败。')
    return document
  }
  return parseBlogMarkdownInWorker(markdown)
}
const { data: initial, error: initialError, refresh } = await useAsyncData('blog-renderer-' + key, () => parse(props.markdown))
const document = shallowRef(initial.value ? markRaw(toRaw(initial.value)) : initial.value)
const error = ref(initialError.value ? '正文暂时无法解析，请重新读取。' : '')
// 完整 SSR 长文也需要把水合分散到空闲时段；操作标题时立即水合该章节。
// 使用 Vue 官方策略，不重复实现 DOM 水合或移动 Vue 管理的节点。
const ReadingChapter = props.showToc && initialMarkdown.length >= 20000
  ? Object.assign(defineAsyncComponent({ loader: () => Promise.resolve(BlogChapter), hydrate: (hydrate, elements) => {
    let done = false, idle = () => {}, interaction = () => {}
    const once = () => { if (done) return; done = true; idle(); interaction(); hydrate() }
    idle = hydrateOnIdle(2000)(once, elements) || idle
    interaction = hydrateOnInteraction(['click', 'keydown'])(once, elements) || interaction
    return () => { idle(); interaction() }
  } }), { props: BlogChapter.props })
  : BlogChapter
// 明确复用官方 Prose，避免长文每个正文节点各套一个异步组件并分批挂载/重排。
const components = markRaw({ p: ProseP, h1: ProseH1, h2: ProseH2, h3: ProseH3, h4: ProseH4,
  strong: ProseStrong, em: ProseEm, ol: ProseOl, ul: ProseUl, li: ProseLi, blockquote: ProseBlockquote, hr: ProseHr,
  table: ProseTable, thead: ProseThead, tbody: ProseTbody, tr: ProseTr, th: ProseTh, td: ProseTd, code: ProseCode,
  pre: BlogCodeBlock, img: BlogImage, a: BlogLink, 'blog-chapter': ReadingChapter, 'blog-math': BlogMath, math: BlogMathML, mermaid: BlogMermaid })
const toc = computed(() => chapters.value.chapters)
const chapterDocument = computed(() => document.value ? { ...document.value, nodes: chapters.value.nodes } : null)
let timer: ReturnType<typeof setTimeout>, revision = 0, parsedMarkdown = initialMarkdown
watch(document, () => { contentCollapsedIds.value = new Set(); prepareContent(); emit('toc', toc.value) }, { immediate: true })
watch(() => props.markdown, markdown => {
  clearTimeout(timer)
  const current = ++revision
  if (markdown === parsedMarkdown) return
  timer = setTimeout(async () => {
    try {
      const next = await parse(markdown)
      if (current === revision) { document.value = markRaw(next); parsedMarkdown = markdown; error.value = '' }
    } catch { if (current === revision) error.value = '预览暂时无法更新，Markdown 草稿仍保留。' }
  }, props.debounce)
}, { immediate: true })
async function retry() {
  await refresh()
  document.value = initial.value ? markRaw(toRaw(initial.value)) : initial.value
  error.value = initialError.value ? '正文暂时无法解析，请重新读取。' : ''
}
onBeforeUnmount(() => { revision++; clearTimeout(timer); cancelAnimationFrameSafe(); cancelAnimationFrame(containmentFrame); finishMount?.(); bodyObserver?.disconnect(); measuredBodies.clear(); clearNuxtData('blog-renderer-' + key) })
</script>

<template>
  <div :class="{ 'blog-reader': showToc, 'blog-reading-layout': showToc && toc.length, 'blog-reading-with-header': Boolean($slots.header) }">
    <header v-if="$slots.header" class="blog-reading-title min-w-0"><slot name="header" /></header>
    <div ref="bodyRoot" class="blog-renderer markdown-preview min-w-0" :data-reading-ready="readingReady" :aria-busy="!readingReady">
      <p v-if="!readingReady" role="status" class="text-sm text-muted">正在准备长文内容…</p>
      <div v-if="error" role="status" class="mb-4 text-sm text-error">{{ error }} <UButton color="neutral" variant="link" @click="retry">重新解析</UButton></div>
      <Markdown v-if="chapterDocument" :value="chapterDocument" :components="components" />
    </div>
    <aside v-if="showToc && toc.length" class="blog-reading-toc"><BlogToc :items="toc" :root="bodyRoot" :reveal="reveal" :content-collapsed-ids="contentCollapsedIds" :content-ready="readingReady" /></aside>
  </div>
</template>
