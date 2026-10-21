<script setup lang="ts">
import { NodeViewWrapper, type NodeViewProps } from '@tiptap/vue-3'
import BlogImage from '../blog/BlogImage.vue'
import { BLOG_RESOURCES } from '../../utils/blogResources'
import { CANVAS_RESOURCE_PREVIEW } from '../../utils/editor/canvasResources'
const props = defineProps<NodeViewProps>()
const resources = inject(BLOG_RESOURCES, ref([]))
const localPreview = inject(CANVAS_RESOURCE_PREVIEW, undefined)
const previewSource = computed(() => localPreview?.(String(props.node.attrs.src)))
// 仅开发测试清单内的本地图片可使用只读地址；编辑器与 Markdown 始终保存原始引用。
const originalSource = computed(() => {
  try {
    return decodeURI(props.node.attrs.src)
  } catch {
    return props.node.attrs.src
  }
})
const displaySource = computed(
  () =>
    resources.value.find(
      (item) =>
        item.originalUri === originalSource.value &&
        item.status === 'READY' &&
        item.type === 'IMAGE' &&
        item.resourceUrl?.startsWith('/__poc/editor-fixture-resource'),
    )?.resourceUrl ?? props.node.attrs.src,
)
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
    <BlogImage v-else :src="displaySource" :alt="node.attrs.alt" :title="node.attrs.title" />
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
