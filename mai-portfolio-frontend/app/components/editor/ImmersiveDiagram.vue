<script setup lang="ts">
import {
  renderBlogMermaid,
  cloneMermaidSvg,
  type MermaidPalette,
} from '../../utils/markdown/blogMermaid'
import BlogMermaidCanvas from '../blog/BlogMermaidCanvas.vue'
const props = defineProps<{ content: string }>()
const root = useTemplateRef<HTMLElement>('root')
const { theme } = useSiteTheme()
const svg = shallowRef('')
const error = ref('')
const loading = ref(false)
const fullscreen = ref(false)
const id = 'editor-mermaid-' + Math.random().toString(36).slice(2)
const fullscreenSvg = computed(() =>
  fullscreen.value && svg.value ? cloneMermaidSvg(svg.value, id + '-full') : '',
)
let visibility: ResizeObserver | undefined
let attempted = ''
const renderKey = () => props.content + '\0' + theme.value
let timer: ReturnType<typeof setTimeout> | undefined
let revision = 0
let disposed = false
async function renderDiagram() {
  if (!root.value || !root.value.clientWidth || disposed) {
    return
  }
  attempted = renderKey()
  const current = ++revision
  loading.value = true
  const styles = getComputedStyle(root.value)
  const palette: MermaidPalette = {
    background: styles.getPropertyValue('--site-bg').trim(),
    surface: styles.getPropertyValue('--site-surface').trim(),
    text: styles.getPropertyValue('--site-text').trim(),
    primary: styles.getPropertyValue('--site-accent').trim(),
    border: styles.getPropertyValue('--site-line').trim(),
  }
  try {
    const next = await renderBlogMermaid(props.content, palette, id)
    if (current === revision && !disposed) {
      svg.value = next
      error.value = ''
    }
  } catch (cause) {
    if (current === revision && !disposed) {
      // 输入暂时无效时保留上一次安全 SVG；不覆盖源文本，也不清空整篇文章。
      error.value = cause instanceof Error ? cause.message : '图表尚未完成。'
    }
  } finally {
    if (current === revision && !disposed) {
      loading.value = false
    }
  }
}
function schedule() {
  revision++
  clearTimeout(timer)
  timer = setTimeout(renderDiagram, 350)
}
watch(() => props.content, schedule)
watch(theme, schedule)
onMounted(() => {
  // Mermaid 会实测文字；在隐藏标签中渲染会产生错误的 SVG 几何尺寸。
  visibility = new ResizeObserver(() => {
    if (root.value?.clientWidth && attempted !== renderKey()) {
      schedule()
    }
  })
  if (root.value) {
    visibility.observe(root.value)
  }
  schedule()
})
onBeforeUnmount(() => {
  visibility?.disconnect()
  disposed = true
  revision++
  clearTimeout(timer)
})
defineExpose({
  openFullscreen: () => {
    fullscreen.value = true
  },
})
</script>
<template>
  <div ref="root" class="immersive-diagram" contenteditable="false" :aria-busy="loading">
    <p v-if="error" class="mermaid-diagnostic px-4 py-2 text-sm text-warning" role="status">
      {{ error }}{{ svg ? '（保留上一次有效图表）' : ' 请继续编辑源码。' }}
    </p>
    <BlogMermaidCanvas v-if="svg" :svg="svg" @fullscreen="fullscreen = true" />
    <p v-else-if="!error" class="p-4 text-sm text-muted">正在准备图表…</p>
    <UModal
      v-model:open="fullscreen"
      fullscreen
      title="Mermaid 图表"
      :ui="{ body: 'flex min-h-0 flex-col p-0 sm:p-0', content: 'blog-mermaid-modal' }"
    >
      <template #body
        ><BlogMermaidCanvas v-if="fullscreenSvg" :svg="fullscreenSvg" fullscreen
      /></template>
    </UModal>
  </div>
</template>
<style scoped>
.mermaid-diagnostic {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-family: var(--font-mono);
  font-size: 0.8125rem;
}
</style>
