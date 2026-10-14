<script setup lang="ts">
import { useClipboard } from '@vueuse/core'
import { renderBlogMermaid, cloneMermaidSvg, type MermaidPalette } from '../../utils/blogMermaid'
import BlogMermaidCanvas from './BlogMermaidCanvas.vue'
defineOptions({ inheritAttrs: false })
const props = defineProps<{ content: string }>()
const { theme } = useSiteTheme()
const root = useTemplateRef<HTMLElement>('root')
const instanceId = 'blog-mermaid-view-' + useId().replace(/[^\w-]/g, '-')
const svg = shallowRef(''), error = ref(''), loading = ref(false), activated = ref(false), showSource = ref(false)
const fullscreen = ref(false), fullscreenStyle = shallowRef<Record<string, string>>({})
const fullscreenContent = computed(() => ({ style: fullscreenStyle.value, onCloseAutoFocus: restoreFocus }))
const inlineCanvas = useTemplateRef<InstanceType<typeof BlogMermaidCanvas>>('inlineCanvas')
// 全屏复用安全生成物，单独隔离 ID；不再次解析 Mermaid，不与正文 SVG 冲突。
const fullscreenSvg = computed(() => fullscreen.value && svg.value ? cloneMermaidSvg(svg.value, instanceId + '-fullscreen') : '')
function openFullscreen() { inlineCanvas.value?.focus(); fullscreen.value = true }
function restoreFocus(event: Event) { event.preventDefault(); inlineCanvas.value?.focus() }
const { copy, copied } = useClipboard({ legacy: true })
let revision = 0, disposed = false, visibility: IntersectionObserver | undefined
async function update() {
  if (!activated.value || !root.value || disposed) return
  const current = ++revision
  loading.value = true; error.value = ''
  const css = getComputedStyle(root.value)
  fullscreenStyle.value = Object.fromEntries(['bg', 'bg-elevated', 'bg-muted', 'bg-accented', 'text', 'text-muted', 'text-toned', 'text-highlighted', 'border', 'border-muted', 'border-accented', 'primary'].map(name => ['--ui-' + name, css.getPropertyValue('--ui-' + name).trim()]))
  const palette: MermaidPalette = { background: css.getPropertyValue('--site-bg').trim(), surface: css.getPropertyValue('--site-surface').trim(), text: css.getPropertyValue('--site-text').trim(), primary: css.getPropertyValue('--site-accent').trim(), border: css.getPropertyValue('--site-line').trim() }
  try { const result = await renderBlogMermaid(props.content, palette, instanceId); if (current === revision && !disposed) svg.value = result }
  catch (cause) { if (current === revision && !disposed) { svg.value = ''; error.value = cause instanceof Error ? cause.message.split('\n')[0]! : '图表无法渲染，请查看源码。' } }
  finally { if (current === revision && !disposed) loading.value = false }
}
watch(theme, update)
watch(() => props.content, () => { revision++; svg.value = ''; fullscreen.value = false; void update() })
onMounted(() => {
  visibility = new IntersectionObserver(entries => { if (entries.some(entry => entry.isIntersecting)) { activated.value = true; visibility?.disconnect(); void update() } }, { rootMargin: '200px' })
  if (root.value) visibility.observe(root.value)
})
onBeforeUnmount(() => { disposed = true; revision++; visibility?.disconnect() })
</script>

<template>
  <figure ref="root" class="blog-mermaid my-6 min-w-0 overflow-hidden rounded-xl border border-default" :aria-busy="loading" aria-label="Mermaid 图表">
    <figcaption class="flex flex-wrap items-center gap-2 border-b border-default px-3 py-2 text-sm">
      <span class="mr-auto font-medium">Mermaid</span>
      <UButton color="neutral" variant="ghost" size="xs" :aria-expanded="showSource" @click="showSource = !showSource">{{ showSource ? '收起源码' : '查看源码' }}</UButton>
      <UButton v-if="svg" color="neutral" variant="ghost" size="xs" icon="i-lucide-maximize" aria-label="全屏查看图表" @click="openFullscreen" />
      <UButton :icon="copied ? 'i-lucide-copy-check' : 'i-lucide-copy'" color="neutral" variant="ghost" size="xs" aria-label="复制 Mermaid 源码" @click="copy(content)" />
    </figcaption>
    <pre v-if="showSource || error" class="blog-mermaid-source m-0 max-w-full overflow-x-auto border-b border-default p-4 text-xs"><code>{{ content }}</code></pre>
    <BlogMermaidCanvas v-if="svg" ref="inlineCanvas" :svg="svg" @fullscreen="openFullscreen" />
    <p v-else class="blog-mermaid-placeholder px-4 py-8 text-sm text-muted" role="status">{{ error || (loading ? '正在绘制图表…' : '图表将在接近阅读位置时加载。') }}</p>
    <UModal v-model:open="fullscreen" fullscreen :transition="false" title="Mermaid 图表" :content="fullscreenContent" :ui="{ overlay: 'z-50', content: 'blog-mermaid-modal z-50 rounded-none shadow-none', body: 'flex min-h-0 flex-col overflow-hidden p-0 sm:p-0' }">
      <template #body><BlogMermaidCanvas v-if="fullscreenSvg" :svg="fullscreenSvg" fullscreen /></template>
    </UModal>
  </figure>
</template>
