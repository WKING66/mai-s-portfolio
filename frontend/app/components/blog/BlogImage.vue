<script setup lang="ts">
import { BLOG_RESOURCES, resolveBlogResource } from '../../utils/blogResources'
defineOptions({ inheritAttrs: false })
const props = defineProps<{ src?: string; alt?: string; title?: string }>()
const resources = inject(BLOG_RESOURCES, ref([]))
const source = computed(() => resolveBlogResource(props.src, resources.value, true))
const loaded = ref(false)
const frame = useTemplateRef<HTMLElement>('frame')
watch(() => props.src, () => { loaded.value = false })
onMounted(() => { if (frame.value?.querySelector('img')?.complete) loaded.value = true })
onBeforeUnmount(() => {
  // 离开长文章时取消尚未完成的原生图片读取。Edge 的 Pending activities
  // 会通过 load/error 回调保留已卸载的整篇组件树，直到慢请求结束。
  // 只清理网络来源属性，不移动或删除 Vue 管理的节点，不修改 Markdown 引用。
  const image = frame.value?.querySelector('img')
  if (image && !image.complete) { image.removeAttribute('srcset'); image.removeAttribute('src') }
})
</script>
<template>
  <!-- 单张图片不注册全局滚动/键盘监听；长文章的图片使用原生延迟加载。 -->
  <!-- span 保持 Markdown 段落内合法结构；预留加载高度防止 0 高图片被全部当作首屏。 -->
  <span v-if="source" ref="frame" class="blog-image-frame" :data-loading="!loaded">
    <ProseImg :src="source" :alt="alt || ''" :title="title" :zoom="false" loading="lazy" decoding="async" @load="loaded = true" @error="loaded = true" />
  </span>
  <span v-else role="status" class="my-4 block rounded-lg border border-default bg-muted p-4 text-sm text-muted">{{ alt || '图片' }} · 资源暂不可用</span>
</template>
