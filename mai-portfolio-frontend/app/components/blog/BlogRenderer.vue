<script setup lang="ts">
import DocumentSearch from './DocumentSearch.vue'
import { createReadingSearch } from '../../utils/blog/readingSearch'
import BlogCodeBlock from './BlogCodeBlock.vue'
import BlogImage from './BlogImage.vue'
import BlogLink from './BlogLink.vue'
import BlogToc from './BlogToc.vue'
import BlogTocResizeHandle from './BlogTocResizeHandle.vue'
import BlogChapter from './BlogChapter.vue'
import BlogMath from './BlogMath.vue'
import BlogMathML from './BlogMathML.vue'
import BlogMermaid from './BlogMermaid.vue'
import { defineAsyncComponent, hydrateOnIdle, hydrateOnInteraction } from 'vue'
import {
  ProseP,
  ProseH1,
  ProseH2,
  ProseH3,
  ProseH4,
  ProseStrong,
  ProseEm,
  ProseOl,
  ProseUl,
  ProseLi,
  ProseBlockquote,
  ProseHr,
  ProseTable,
  ProseThead,
  ProseTbody,
  ProseTr,
  ProseTh,
  ProseTd,
  ProseCode,
} from '#components'
import { BLOG_CHAPTER_STATE, buildBlogChapters, chapterAncestors } from '../../utils/blog/chapters'
import { BLOG_RESOURCES } from '../../utils/blog/resources'
import { parseBlogMarkdownInWorker } from '../../utils/markdown/parseClient'
import type { DocumentResource } from '../../api/blogs'
import type { TocLink } from 'comark/plugins/toc'
const props = withDefaults(
  defineProps<{
    markdown: string
    resources?: DocumentResource[]
    debounce?: number
    showToc?: boolean
    progressive?: boolean
    cacheKey?: string
  }>(),
  { debounce: 150, progressive: false, resources: () => [], showToc: false, cacheKey: undefined },
)
const emit = defineEmits<{ toc: [items: TocLink[]] }>()
// 1. 使用稳定版本键复用 SSR payload；资源投影单独注入，不写入正文 AST。
const instanceId = useId()
const isInitiallyHydrating = Boolean(useNuxtApp().isHydrating)
// 异步页面内 Vue useId 的 SSR/客户端前缀可能不同；业务阅读使用稳定版本键。
const readerKey = props.cacheKey ?? instanceId
const initialMarkdown = props.markdown
provide(BLOG_RESOURCES, toRef(props, 'resources'))
const bodyRoot = useTemplateRef<HTMLElement>('bodyRoot')
// 所有叶章节共用一个尺寸观察器。使用浏览器已完成的布局结果，不逐帧强制测量。
let bodyResizeObserver: ResizeObserver | undefined
const measuredChapterBodies = new Map<
  HTMLElement,
  { height: number; applied: number; measured: (height: number) => void }
>()
let containmentFrameId = 0,
  measuredRootWidth = 0
function applyBodySizes() {
  containmentFrameId = 0
  // 每节准备好即可使用实测高度，不能等全篇完成才跳过离屏内容的反复布局。
  for (const [element, body] of measuredChapterBodies) {
    if (
      element.dataset.readingPending !== 'true' &&
      element.dataset.readingFolded !== 'true' &&
      body.height > 0 &&
      Math.abs(body.height - body.applied) > 0.5
    ) {
      body.measured(body.height)
      body.applied = body.height
    }
  }
}
function observeBody(element: HTMLElement, measured: (height: number) => void) {
  if (
    (!props.showToc && !props.progressive) ||
    props.markdown.length < 20000 ||
    !CSS.supports('content-visibility', 'auto')
  ) {
    return () => {}
  }
  const firstObserver = !bodyResizeObserver
  bodyResizeObserver ??= new ResizeObserver((entries) => {
    let widthChanged = false,
      heightUpdated = false
    for (const entry of entries) {
      if (entry.target === bodyRoot.value) {
        const width = entry.borderBoxSize[0]?.inlineSize ?? entry.contentRect.width
        if (width > 0 && measuredRootWidth > 0 && Math.abs(width - measuredRootWidth) > 0.5) {
          // 也清除折叠后代的旧宽度缓存；它们展开后才恢复跳过，不能复用旧高度。
          for (const body of measuredChapterBodies.values()) {
            if (body.applied > 0) {
              body.measured(0)
              body.applied = 0
            }
          }
          widthChanged = true
        }
        if (width > 0) {
          measuredRootWidth = width
        }
        continue
      }
      const height = entry.borderBoxSize[0]?.blockSize ?? entry.contentRect.height
      const body = measuredChapterBodies.get(entry.target as HTMLElement)
      if (!body) {
        continue
      }
      if (height > 0) {
        body.height = height
        heightUpdated = true
      }
    }
    if (widthChanged) {
      cancelAnimationFrame(containmentFrameId)
      // 下一帧的 ResizeObserver 收集自然排版结果，再恢复跳过；不解析 AST 或更新 Vue 子树。
      containmentFrameId = requestAnimationFrame(() => {
        containmentFrameId = requestAnimationFrame(applyBodySizes)
      })
    } else if (heightUpdated && !containmentFrameId) {
      containmentFrameId = requestAnimationFrame(applyBodySizes)
    }
  })
  if (firstObserver && bodyRoot.value) {
    bodyResizeObserver.observe(bodyRoot.value)
  }
  measuredChapterBodies.set(element, { height: 0, applied: 0, measured })
  bodyResizeObserver.observe(element)
  return () => {
    bodyResizeObserver?.unobserve(element)
    measuredChapterBodies.delete(element)
  }
}
const contentCollapsedIds = shallowRef(new Set<string>())
const readySections = shallowRef<Set<string> | null>(null)
const remainingHydration = shallowRef<Set<string> | null>(null)
const readingReady = computed(() => readySections.value === null && !remainingHydration.value?.size)
watch(readingReady, (ready) => {
  if (import.meta.client && ready && !containmentFrameId) {
    containmentFrameId = requestAnimationFrame(applyBodySizes)
  }
})
let mountFrameId = 0,
  resolveContentMount: (() => void) | undefined,
  contentMountPromise = Promise.resolve()
let isInitialPreparation = true
const chapters = computed(() => markRaw(buildBlogChapters(parsedDocument.value?.nodes || [])))
// 2. 内容版本变化才准备章节；折叠状态独立于章节树，不触发重新解析。
function prepareContent() {
  cancelAnimationFrameSafe()
  resolveContentMount?.()
  const deferHydration = isInitialPreparation && isInitiallyHydrating
  isInitialPreparation = false
  // SSR 保留完整正文。支持脚本的浏览器先跳过待准备章节的布局，水合后逐帧显示。
  // CSR 同时分散首次组件挂载；准备完毕后折叠只切换可见性，不卸载内容。
  if ((!props.showToc && !props.progressive) || props.markdown.length < 20000) {
    readySections.value = null
    remainingHydration.value = null
    contentMountPromise = Promise.resolve()
    return
  }
  readySections.value = new Set()
  remainingHydration.value =
    import.meta.server || deferHydration ? new Set(chapters.value.byId.keys()) : null
  contentMountPromise = new Promise((resolve) => {
    resolveContentMount = resolve
  })
  if (import.meta.client && !deferHydration) {
    mountContent()
  }
}
function mountContent() {
  const ids = [...chapters.value.byId.keys()]
  let index = 0
  function mountNext() {
    const ready = new Set(readySections.value || [])
    // 限制每帧两个章节，包括标题/按钮；平衡集中挂载和逐节全页布局成本。
    for (let count = 0; count < 2 && index < ids.length; count++) {
      ready.add(ids[index++]!)
    }
    readySections.value = ready
    if (index < ids.length) {
      mountFrameId = requestAnimationFrame(mountNext)
    } else {
      readySections.value = null
      mountFrameId = 0
      finishPreparing()
    }
  }
  mountFrameId = requestAnimationFrame(mountNext)
}
onMounted(() => {
  if (readySections.value !== null && !mountFrameId) {
    mountContent()
  }
})
function finishPreparing() {
  if (readingReady.value) {
    resolveContentMount?.()
    resolveContentMount = undefined
  }
}
function hydratedChapter(id: string) {
  if (!remainingHydration.value) {
    return
  }
  const next = new Set(remainingHydration.value)
  next.delete(id)
  remainingHydration.value = next
  finishPreparing()
}
function cancelAnimationFrameSafe() {
  if (import.meta.client) {
    cancelAnimationFrame(mountFrameId)
  }
  mountFrameId = 0
}
function toggleChapter(id: string) {
  const next = new Set(contentCollapsedIds.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  contentCollapsedIds.value = next
}
// 3. 目录定位先等待渐进挂载完成，只展开目标的祖先，再交给目录组件定位。
async function reveal(id: string) {
  await contentMountPromise
  const next = new Set(contentCollapsedIds.value)
  for (const ancestor of chapterAncestors(id, chapters.value.byId)) {
    next.delete(ancestor)
  }
  contentCollapsedIds.value = next
  await nextTick()
}
provide(BLOG_CHAPTER_STATE, {
  idPrefix: readerKey,
  collapsed: contentCollapsedIds,
  ready: readySections,
  serverRendered: import.meta.server || isInitiallyHydrating,
  hydrated: hydratedChapter,
  toggle: toggleChapter,
  observeBody,
})
// 4. SSR 生成完整正文，客户端后续解析交给 Worker，避免阻塞阅读主线程。
async function parse(markdown: string) {
  if (import.meta.server) {
    const parsedDocument = await (
      await import('../../utils/markdown/parser')
    ).parseBlogMarkdown(markdown)
    if (!parsedDocument) {
      throw new Error('正文解析失败。')
    }
    return parsedDocument
  }
  return parseBlogMarkdownInWorker(markdown)
}
const {
  data: initialDocument,
  error: initialError,
  refresh,
} = await useAsyncData('blog-renderer-' + readerKey, () => parse(props.markdown))
const parsedDocument = shallowRef(
  initialDocument.value ? markRaw(toRaw(initialDocument.value)) : initialDocument.value,
)
const error = ref(initialError.value ? '正文暂时无法解析，请重新读取。' : '')
// 完整 SSR 长文也需要把水合分散到空闲时段；操作标题时立即水合该章节。
// 使用 Vue 官方策略，不重复实现 DOM 水合或移动 Vue 管理的节点。
const ReadingChapter =
  (props.showToc || props.progressive) && initialMarkdown.length >= 20000
    ? Object.assign(
        defineAsyncComponent({
          loader: () => Promise.resolve(BlogChapter),
          hydrate: (hydrate, elements) => {
            let done = false,
              idle = () => {},
              interaction = () => {}
            const once = () => {
              if (done) {
                return
              }
              done = true
              idle()
              interaction()
              hydrate()
            }
            idle = hydrateOnIdle(2000)(once, elements) || idle
            interaction = hydrateOnInteraction(['click', 'keydown'])(once, elements) || interaction
            return () => {
              idle()
              interaction()
            }
          },
        }),
        { props: BlogChapter.props },
      )
    : BlogChapter
// 明确复用官方 Prose，避免长文每个正文节点各套一个异步组件并分批挂载/重排。
const components = markRaw({
  p: ProseP,
  h1: ProseH1,
  h2: ProseH2,
  h3: ProseH3,
  h4: ProseH4,
  strong: ProseStrong,
  em: ProseEm,
  ol: ProseOl,
  ul: ProseUl,
  li: ProseLi,
  blockquote: ProseBlockquote,
  hr: ProseHr,
  table: ProseTable,
  thead: ProseThead,
  tbody: ProseTbody,
  tr: ProseTr,
  th: ProseTh,
  td: ProseTd,
  code: ProseCode,
  pre: BlogCodeBlock,
  img: BlogImage,
  a: BlogLink,
  'blog-chapter': ReadingChapter,
  'blog-math': BlogMath,
  math: BlogMathML,
  mermaid: BlogMermaid,
})
const toc = computed(() => chapters.value.chapters)
const chapterDocument = computed(() =>
  parsedDocument.value ? { ...parsedDocument.value, nodes: chapters.value.nodes } : null,
)
let parseTimer: ReturnType<typeof setTimeout>,
  parseRevision = 0,
  lastParsedMarkdown = initialMarkdown
watch(
  parsedDocument,
  () => {
    contentCollapsedIds.value = new Set()
    prepareContent()
    emit('toc', toc.value)
  },
  { immediate: true },
)
// 5. 编辑预览防抖并校验版本号，旧请求不能覆盖新草稿；失败保留上一份可读正文。
watch(
  () => props.markdown,
  (markdown) => {
    clearTimeout(parseTimer)
    const current = ++parseRevision
    if (markdown === lastParsedMarkdown) {
      return
    }
    parseTimer = setTimeout(async () => {
      try {
        const next = await parse(markdown)
        if (current === parseRevision) {
          parsedDocument.value = markRaw(next)
          lastParsedMarkdown = markdown
          error.value = ''
        }
      } catch {
        if (current === parseRevision) {
          error.value = '预览暂时无法更新，Markdown 草稿仍保留。'
        }
      }
    }, props.debounce)
  },
  { immediate: true },
)
async function retry() {
  await refresh()
  parsedDocument.value = initialDocument.value
    ? markRaw(toRaw(initialDocument.value))
    : initialDocument.value
  error.value = initialError.value ? '正文暂时无法解析，请重新读取。' : ''
}
const documentSearch = createReadingSearch({
  root: () => bodyRoot.value,
  reveal: async (element) => {
    await contentMountPromise
    const next = new Set(contentCollapsedIds.value)
    let chapter = element.closest('[data-chapter]')
    while (chapter) {
      next.delete(chapter.getAttribute('data-chapter')!)
      chapter = chapter.parentElement?.closest('[data-chapter]') ?? null
    }
    if (next.size !== contentCollapsedIds.value.size) {
      contentCollapsedIds.value = next
    }
    element
      .closest('.blog-code-viewport')
      ?.dispatchEvent(new Event('document-search-reveal', { bubbles: true }))
    await nextTick()
  },
})
// 6. 离开阅读器时使异步响应失效，释放观察器、定时器和当前 payload 缓存。
onBeforeUnmount(() => {
  parseRevision++
  clearTimeout(parseTimer)
  cancelAnimationFrameSafe()
  cancelAnimationFrame(containmentFrameId)
  resolveContentMount?.()
  bodyResizeObserver?.disconnect()
  measuredChapterBodies.clear()
  clearNuxtData('blog-renderer-' + readerKey)
})
</script>

<template>
  <div
    :class="{
      'blog-reader': showToc,
      'blog-reading-layout': showToc && toc.length,
      'blog-reading-with-header': Boolean($slots.header),
    }"
  >
    <header v-if="$slots.header" class="blog-reading-title min-w-0"><slot name="header" /></header>
    <div
      ref="bodyRoot"
      class="blog-renderer markdown-preview min-w-0"
      :data-reading-ready="readingReady"
      :aria-busy="!readingReady"
    >
      <div v-if="showToc" class="blog-reading-find" data-document-search-ignore>
        <DocumentSearch :adapter="documentSearch" :disabled="!readingReady" />
      </div>
      <p v-if="!readingReady" data-document-search-ignore role="status" class="text-sm text-muted">
        正在准备长文内容…
      </p>
      <div v-if="error" data-document-search-ignore role="status" class="mb-4 text-sm text-error">
        {{ error }} <UButton color="neutral" variant="link" @click="retry">重新解析</UButton>
      </div>
      <Markdown v-if="chapterDocument" :value="chapterDocument" :components="components" />
    </div>
    <aside v-if="showToc && toc.length" class="blog-reading-toc">
      <BlogTocResizeHandle />
      <BlogToc
        :items="toc"
        :root="bodyRoot"
        :reveal="reveal"
        :content-collapsed-ids="contentCollapsedIds"
        :content-ready="readingReady"
      />
    </aside>
  </div>
</template>
