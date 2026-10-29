<script setup lang="ts">
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/vue-3'
import BlogImage from '../blog/BlogImage.vue'
import { CANVAS_RESOURCE_PREVIEW } from '../../utils/editor/session/canvasResources'
const props = defineProps<NodeViewProps>()
const localPreview = inject(CANVAS_RESOURCE_PREVIEW, undefined)
const previewSource = computed(() => localPreview?.(String(props.node.attrs.src)))
</script>
<template>
  <NodeViewWrapper
    as="span"
    class="editor-image"
    :class="{ 'is-selected': selected }"
    :data-editor-image-ref="node.attrs.src"
    :data-editor-image-alt="node.attrs.alt"
    :title="node.attrs.title"
    contenteditable="false"
  >
    <img
      v-if="previewSource"
      :src="previewSource"
      :alt="node.attrs.alt || ''"
      :title="node.attrs.title"
      class="max-w-full"
      loading="lazy"
      decoding="async"
    />
    <BlogImage v-else :src="node.attrs.src" :alt="node.attrs.alt" :title="node.attrs.title" />
  </NodeViewWrapper>
</template>
<style scoped>
.editor-image {
  display: inline-block;
  /* ProseImg 的宽度为 100%；SVG 无固有尺寸时不能让外层 shrink-to-fit 收缩到 0。 */
  width: 100%;
  max-width: 100%;
  vertical-align: middle;
}
.is-selected {
  outline: 2px solid var(--ui-primary);
  outline-offset: 2px;
}
</style>
