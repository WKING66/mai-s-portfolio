<script setup lang="ts">
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/vue-3'
import BlogImage from './BlogImage.vue'

defineProps<NodeViewProps>()
</script>

<template>
  <!-- TipTap 管理节点视图；仅展示授权资源投影，不把图片 URL 写入节点或 Markdown。 -->
  <NodeViewWrapper
    as="span"
    class="editor-image"
    :class="{ 'is-selected': selected }"
    :data-editor-image-ref="node.attrs.src"
    :data-editor-image-alt="node.attrs.alt"
    :title="node.attrs.title"
    contenteditable="false"
  >
    <BlogImage :src="node.attrs.src" :alt="node.attrs.alt" :title="node.attrs.title" />
  </NodeViewWrapper>
</template>

<style scoped>
.editor-image {
  display: inline-block;
  max-width: 100%;
  vertical-align: middle;
}
.is-selected {
  outline: 2px solid var(--ui-primary);
  outline-offset: 2px;
}
</style>
