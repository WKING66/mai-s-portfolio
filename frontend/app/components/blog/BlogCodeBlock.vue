<script setup lang="ts">
import type { ElementNode } from 'comark'
import type { StyleValue } from 'vue'
import { textContent } from 'comark/utils'
import { useClipboard } from '@vueuse/core'
import { CODE_THEMES, CODE_FOLD, codeLabel, type CodeTheme } from '../../constants/codeThemes'
defineOptions({ inheritAttrs: false })
const props = defineProps<{ __node?: ElementNode; code?: string; language?: string; filename?: string; style?: StyleValue }>()
const theme = ref<CodeTheme>('follow')
const portalTarget = useTemplateRef<HTMLElement>('portalTarget')
const label = computed(() => codeLabel(props.filename, props.language))
const source = computed(() => props.code ?? (props.__node ? textContent(props.__node) : ''))
const expanded = ref(false)
const long = computed(() => source.value.split(/\r?\n/).length > CODE_FOLD.threshold)
const codeViewport = useTemplateRef<HTMLElement>('codeViewport')
async function toggleCode() {
  const top = codeViewport.value?.getBoundingClientRect().top ?? 0
  expanded.value = !expanded.value
  await nextTick()
  if (!expanded.value && top < 96 && codeViewport.value)
    window.scrollTo({ top: codeViewport.value.getBoundingClientRect().top + scrollY - 96, behavior: 'instant' })
}
const { copy, copied } = useClipboard({ legacy: true })
</script>

<template>
  <div class="blog-code showcase-code my-6 rounded-xl border border-default" :data-code-theme="theme" :data-highlight="props.__node?.[1]['data-highlight']" :style="props.style">
    <!-- 直接使用 DOM 引用，避免异步 Markdown 树的 SSR / 客户端 ID 不一致。 -->
    <div ref="portalTarget" data-code-portal />
    <div class="blog-code-toolbar flex min-w-0 flex-wrap items-center gap-2 rounded-t-xl border-b border-default px-3 py-2">
      <UIcon name="i-lucide-file-code" class="size-4 shrink-0" />
      <span class="min-w-[min(8rem,100%)] flex-1 truncate text-sm" :title="label">{{ label }}</span>
      <USelect v-model="theme" :items="[...CODE_THEMES]" :portal="portalTarget ?? false" size="xs" :aria-label="label + '代码主题'" class="w-32 max-w-[calc(100%-2.5rem)] shrink-0" />
      <UButton :icon="copied ? 'i-lucide-copy-check' : 'i-lucide-copy'" color="neutral" variant="outline" size="xs" :aria-label="'复制 ' + label" @click="copy(source)" />
    </div>
    <div ref="codeViewport" class="blog-code-viewport" :data-code-collapsed="long && !expanded" :style="{ '--code-preview-lines': CODE_FOLD.previewLines }">
      <ProsePre :code="source" :copy="false" :hide-header="true" tabindex="0" :aria-label="label + '代码内容'" :ui="{ root: 'my-0 overflow-hidden rounded-b-xl', base: 'border-0 rounded-none bg-transparent m-0', copy: 'opacity-100' }"><slot /></ProsePre>
    </div>
    <div v-if="long" class="blog-code-fold"><UButton color="neutral" variant="ghost" size="xs" :aria-expanded="expanded" @click="toggleCode">{{ expanded ? '收起代码' : '展开全部代码' }}</UButton></div>
  </div>
</template>
