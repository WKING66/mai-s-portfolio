<script setup lang="ts">
import { BLOG_RESOURCES, resolveBlogResource } from '../../utils/blogResources'
defineOptions({ inheritAttrs: false })
const props = defineProps<{ href?: string; title?: string }>()
const resources = inject(BLOG_RESOURCES, ref([]))
const destination = computed(() => resolveBlogResource(props.href, resources.value))
</script>
<template>
  <ProseA v-if="destination" :href="destination" :title="title" rel="noopener noreferrer"><slot /></ProseA>
  <span v-else class="text-muted"><slot /><span class="ml-1 text-xs">（链接或资源不可用）</span></span>
</template>
