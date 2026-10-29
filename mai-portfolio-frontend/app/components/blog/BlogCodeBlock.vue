<script setup lang="ts">
import type { ElementNode, Node } from 'comark'
import { h, type StyleValue, type VNodeChild } from 'vue'
import { textContent } from 'comark/utils'
import { useClipboard } from '@vueuse/core'
import { CODE_THEMES, CODE_FOLD, codeLabel, type CodeTheme } from '../../constants/codeThemes'
import { highlightCodeInWorker } from '../../utils/markdown/code/codeHighlightClient'
defineOptions({ inheritAttrs: false })
const props = defineProps<{
  // Comark 注入的属性名是固定协议，保留与正文 AST 的映射。
  // eslint-disable-next-line vue/prop-name-casing
  __node?: ElementNode
  code?: string
  language?: string
  filename?: string
  style?: StyleValue
}>()
const theme = ref<CodeTheme>('follow')
const portalTarget = useTemplateRef<HTMLElement>('portalTarget')
const label = computed(() => codeLabel(props.filename, props.language))
const source = computed(() => props.code ?? (props.__node ? textContent(props.__node) : ''))
const expanded = ref(false)
const isLongCodeBlock = computed(() => source.value.split(/\r?\n/).length > CODE_FOLD.threshold)
const codeViewport = useTemplateRef<HTMLElement>('codeViewport')
const highlighted = shallowRef<ElementNode>()
const coloredNodes = shallowRef<Node[]>([])
const remainingSource = ref(source.value)
let controller: AbortController | undefined
let frame = 0
let painting = false
let mounted = false
let nextNode = 0
let offset = 0
let prepared: Node[] = []
function renderToken(node: Node): VNodeChild {
  if (typeof node === 'string') {
    return node
  }
  // 只渲染高亮器生成的 span 和文本，既不解释 Markdown，也不使用 HTML 注入。
  return h('span', node[1], (node.slice(2) as Node[]).map(renderToken))
}
const CodeContent = () =>
  h('code', {}, [...coloredNodes.value.map(renderToken), remainingSource.value])
function selectionInCode() {
  const selection = window.getSelection()
  return Boolean(
    selection &&
    !selection.isCollapsed &&
    selection.rangeCount &&
    codeViewport.value &&
    selection.getRangeAt(0).intersectsNode(codeViewport.value),
  )
}
function schedulePaint() {
  if (!frame && nextNode < prepared.length) {
    frame = requestAnimationFrame(paintNext)
  }
}
function paintNext() {
  frame = 0
  // 阅读者正在拖选/复制时保留文本 DOM，选区释放后再继续补颜色。
  if (painting || selectionInCode()) {
    return
  }
  const end = Math.min(nextNode + 200, prepared.length)
  for (; nextNode < end; nextNode++) {
    offset += textContent(prepared[nextNode]!).length
  }
  coloredNodes.value = prepared.slice(0, nextNode)
  remainingSource.value = source.value.slice(offset)
  schedulePaint()
}
function pointerDown() {
  painting = true
  if (isLongCodeBlock.value) {
    expanded.value = true
  }
}
function pointerUp() {
  painting = false
  schedulePaint()
}
async function prepareHighlight() {
  controller?.abort()
  cancelAnimationFrame(frame)
  frame = 0
  nextNode = offset = 0
  prepared = []
  highlighted.value = undefined
  coloredNodes.value = []
  remainingSource.value = source.value
  const current = new AbortController()
  controller = current
  try {
    // 首帧完整纯文本可见后才请求后台高亮，不延迟正文的首次交付。
    await new Promise<void>((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
    )
    if (current.signal.aborted) {
      return
    }
    const result = await highlightCodeInWorker(source.value, props.language || '', current.signal)
    if (current.signal.aborted) {
      return
    }
    highlighted.value = markRaw(result)
    const code = result[2] as ElementNode
    // 行容器不携带颜色；展开成 token 批次，单个很长的行也不会一次挂载全部颜色节点。
    prepared = (code.slice(2) as Node[]).flatMap((node) =>
      Array.isArray(node) &&
      node[0] === 'span' &&
      String(node[1].className) === 'line' &&
      Object.keys(node[1]).length === 1
        ? (node.slice(2) as Node[])
        : [node],
    )
    schedulePaint()
  } catch {
    /* 下载或分词失败仍保留已可见的完整源码。 */
  }
}
watch([source, () => props.language], () => {
  if (mounted) {
    void prepareHighlight()
  }
})
onMounted(() => {
  mounted = true
  document.addEventListener('selectionchange', schedulePaint)
  document.addEventListener('pointerup', pointerUp)
  void prepareHighlight()
})
onBeforeUnmount(() => {
  controller?.abort()
  cancelAnimationFrame(frame)
  document.removeEventListener('selectionchange', schedulePaint)
  document.removeEventListener('pointerup', pointerUp)
})
// 只切换代码视口高度，保留完整源码和高亮节点；收起时按原阅读位置做必要校正。
async function toggleCode() {
  const top = codeViewport.value?.getBoundingClientRect().top ?? 0
  expanded.value = !expanded.value
  await nextTick()
  if (!expanded.value && top < 96 && codeViewport.value) {
    window.scrollTo({
      top: codeViewport.value.getBoundingClientRect().top + scrollY - 96,
      behavior: 'instant',
    })
  }
}
const { copy, copied } = useClipboard({ legacy: true })
</script>

<template>
  <div
    class="blog-code my-6 rounded-xl border border-default"
    :data-code-theme="theme"
    :data-highlight="highlighted ? highlighted[1]['data-highlight'] || 'ready' : 'pending'"
    :style="[props.style, highlighted?.[1].style as StyleValue]"
  >
    <!-- 直接使用 DOM 引用，避免异步 Markdown 树的 SSR / 客户端 ID 不一致。 -->
    <div ref="portalTarget" data-code-portal />
    <div
      class="blog-code-toolbar flex min-w-0 flex-wrap items-center gap-2 rounded-t-xl border-b border-default px-3 py-2"
    >
      <UIcon name="i-lucide-file-code" class="size-4 shrink-0" />
      <span class="min-w-[min(8rem,100%)] flex-1 truncate text-sm" :title="label">{{ label }}</span>
      <USelect
        v-model="theme"
        :items="[...CODE_THEMES]"
        :portal="portalTarget ?? false"
        size="xs"
        :aria-label="label + '代码主题'"
        class="w-32 max-w-[calc(100%-2.5rem)] shrink-0"
      />
      <UButton
        v-if="isLongCodeBlock"
        color="neutral"
        variant="ghost"
        size="xs"
        :icon="expanded ? 'i-lucide-chevron-up' : 'i-lucide-chevron-down'"
        :aria-expanded="expanded"
        @click="toggleCode"
        >{{ expanded ? '收起代码' : '展开全部代码' }}</UButton
      >
      <UButton
        :icon="copied ? 'i-lucide-copy-check' : 'i-lucide-copy'"
        color="neutral"
        variant="outline"
        size="xs"
        :aria-label="'复制 ' + label"
        @click="copy(source)"
      />
    </div>
    <div
      ref="codeViewport"
      class="blog-code-viewport"
      :data-code-collapsed="isLongCodeBlock && !expanded"
      :style="{ '--code-preview-lines': CODE_FOLD.previewLines }"
      @pointerdown="pointerDown"
    >
      <ProsePre
        :code="source"
        :copy="false"
        :hide-header="true"
        tabindex="0"
        :aria-label="label + '代码内容'"
        :ui="{
          root: 'my-0 overflow-hidden rounded-b-xl',
          base: 'border-0 rounded-none bg-transparent m-0',
          copy: 'opacity-100',
        }"
        ><CodeContent
      /></ProsePre>
    </div>
  </div>
</template>
