<script setup lang="ts">
import { useClipboard } from '@vueuse/core'
import { renderBlogMermaid, cloneMermaidSvg, type MermaidPalette } from '../../utils/blogMermaid'
import BlogMermaidCanvas from './BlogMermaidCanvas.vue'
defineOptions({ inheritAttrs: false })
const props = defineProps<{ content: string }>()
const { theme } = useSiteTheme()
const diagramRoot = useTemplateRef<HTMLElement>('root')
const instanceId = 'blog-mermaid-view-' + useId().replace(/[^\w-]/g, '-')
const svg = shallowRef(''),
  error = ref(''),
  loading = ref(false),
  isActivated = ref(false),
  showSource = ref(false)
const fullscreen = ref(false),
  fullscreenStyle = shallowRef<Record<string, string>>({})
const fullscreenContent = computed(() => ({
  style: fullscreenStyle.value,
  onCloseAutoFocus: restoreFocus,
}))
const inlineCanvas = useTemplateRef<InstanceType<typeof BlogMermaidCanvas>>('inlineCanvas')
// 全屏复用安全生成物，单独隔离 ID；不再次解析 Mermaid，不与正文 SVG 冲突。
const fullscreenSvg = computed(() =>
  fullscreen.value && svg.value ? cloneMermaidSvg(svg.value, instanceId + '-fullscreen') : '',
)
function openFullscreen() {
  inlineCanvas.value?.focus()
  fullscreen.value = true
}
function restoreFocus(event: Event) {
  event.preventDefault()
  inlineCanvas.value?.focus()
}
const { copy, copied } = useClipboard({ legacy: true })
let renderRevision = 0,
  isDisposed = false,
  visibilityObserver: IntersectionObserver | undefined
// 1. 只有接近阅读区后才加载图表；读取局部主题，避免 Portal 脱离正文后配色失效。
async function update() {
  if (!isActivated.value || !diagramRoot.value || isDisposed) {
    return
  }
  const current = ++renderRevision
  loading.value = true
  error.value = ''
  const themeStyles = getComputedStyle(diagramRoot.value)
  fullscreenStyle.value = Object.fromEntries(
    [
      'bg',
      'bg-elevated',
      'bg-muted',
      'bg-accented',
      'text',
      'text-muted',
      'text-toned',
      'text-highlighted',
      'border',
      'border-muted',
      'border-accented',
      'primary',
    ].map((name) => ['--ui-' + name, themeStyles.getPropertyValue('--ui-' + name).trim()]),
  )
  const palette: MermaidPalette = {
    background: themeStyles.getPropertyValue('--site-bg').trim(),
    surface: themeStyles.getPropertyValue('--site-surface').trim(),
    text: themeStyles.getPropertyValue('--site-text').trim(),
    primary: themeStyles.getPropertyValue('--site-accent').trim(),
    border: themeStyles.getPropertyValue('--site-line').trim(),
  }
  // 2. 安全渲染复用缓存；版本变化或卸载后丢弃旧响应，失败保留原始源码。
  try {
    const result = await renderBlogMermaid(props.content, palette, instanceId)
    if (current === renderRevision && !isDisposed) {
      svg.value = result
    }
  } catch (cause) {
    if (current === renderRevision && !isDisposed) {
      svg.value = ''
      error.value =
        cause instanceof Error ? cause.message.split('\n')[0]! : '图表无法渲染，请查看源码。'
    }
  } finally {
    if (current === renderRevision && !isDisposed) {
      loading.value = false
    }
  }
}
watch(theme, update)
watch(
  () => props.content,
  () => {
    renderRevision++
    svg.value = ''
    fullscreen.value = false
    void update()
  },
)
// 3. 首次可见后停止观察，普通滚动和章节折叠不会反复解析图表。
onMounted(() => {
  visibilityObserver = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        isActivated.value = true
        visibilityObserver?.disconnect()
        void update()
      }
    },
    { rootMargin: '200px' },
  )
  if (diagramRoot.value) {
    visibilityObserver.observe(diagramRoot.value)
  }
})
onBeforeUnmount(() => {
  isDisposed = true
  renderRevision++
  visibilityObserver?.disconnect()
})
</script>

<template>
  <figure
    ref="root"
    class="blog-mermaid my-6 min-w-0 overflow-hidden rounded-xl border border-default"
    :aria-busy="loading"
    aria-label="Mermaid 图表"
  >
    <figcaption class="flex flex-wrap items-center gap-2 border-b border-default px-3 py-2 text-sm">
      <span class="mr-auto font-medium">Mermaid</span>
      <UButton
        color="neutral"
        variant="ghost"
        size="xs"
        :aria-expanded="showSource"
        @click="showSource = !showSource"
        >{{ showSource ? '收起源码' : '查看源码' }}</UButton
      >
      <UButton
        v-if="svg"
        color="neutral"
        variant="ghost"
        size="xs"
        icon="i-lucide-maximize"
        aria-label="全屏查看图表"
        @click="openFullscreen"
      />
      <UButton
        :icon="copied ? 'i-lucide-copy-check' : 'i-lucide-copy'"
        color="neutral"
        variant="ghost"
        size="xs"
        aria-label="复制 Mermaid 源码"
        @click="copy(content)"
      />
    </figcaption>
    <pre
      v-if="showSource || error"
      class="blog-mermaid-source m-0 max-w-full overflow-x-auto border-b border-default p-4 text-xs"
    ><code>{{ content }}</code></pre>
    <BlogMermaidCanvas v-if="svg" ref="inlineCanvas" :svg="svg" @fullscreen="openFullscreen" />
    <p v-else class="blog-mermaid-placeholder px-4 py-8 text-sm text-muted" role="status">
      {{ error || (loading ? '正在绘制图表…' : '图表将在接近阅读位置时加载。') }}
    </p>
    <UModal
      v-model:open="fullscreen"
      fullscreen
      :transition="false"
      title="Mermaid 图表"
      :content="fullscreenContent"
      :ui="{
        overlay: 'z-50',
        content: 'blog-mermaid-modal z-50 rounded-none shadow-none',
        body: 'flex min-h-0 flex-col overflow-hidden p-0 sm:p-0',
      }"
    >
      <template #body
        ><BlogMermaidCanvas v-if="fullscreenSvg" :svg="fullscreenSvg" fullscreen
      /></template>
    </UModal>
  </figure>
</template>
