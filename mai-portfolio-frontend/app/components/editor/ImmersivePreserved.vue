<script setup lang="ts">
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/vue-3'
const props = defineProps<NodeViewProps>()
function openSource(event: MouseEvent) {
  const position = props.getPos()
  if (typeof position === 'number') {
    ;(event.currentTarget as HTMLElement).dispatchEvent(
      new CustomEvent('immersive-source-request', { bubbles: true, detail: { position } }),
    )
  }
}
</script>
<template>
  <NodeViewWrapper class="my-4" contenteditable="false" data-preserved-block>
    <div class="mb-2 flex items-center justify-between text-xs text-muted" data-editor-controls>
      <span>保留原始 Markdown · 此块请用源码编辑</span
      ><UButton variant="ghost" color="neutral" size="xs" icon="i-lucide-code" @click="openSource"
        >源码编辑</UButton
      >
    </div>
    <BlogRenderer :markdown="String(node.attrs.source || '')" />
  </NodeViewWrapper>
</template>
