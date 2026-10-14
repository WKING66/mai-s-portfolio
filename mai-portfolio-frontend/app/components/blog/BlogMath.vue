<script lang="ts">
import { computed, createStaticVNode, defineComponent, h } from 'vue'

export default defineComponent({
  props: { block: Boolean, html: { type: String, default: undefined } },
  setup(props, { slots }) {
    // html 只能由 compactBlogMath 生成；KaTeX trust:false + 标签/属性白名单。
    // Vue 官方静态节点保留完整 SSR/MathML，水合与卸载不再遍历数千个公式 VNode。
    const staticMath = computed(() => (props.html ? createStaticVNode(props.html, 1) : null))
    return () =>
      h(
        'span',
        { class: ['blog-math', props.block && 'blog-math-block'] },
        staticMath.value ? [staticMath.value] : slots.default?.(),
      )
  },
})
</script>
