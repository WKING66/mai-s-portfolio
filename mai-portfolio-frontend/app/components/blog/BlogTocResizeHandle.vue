<script setup lang="ts">
const handle = useTemplateRef<HTMLElement>('handle')
const percentage = ref(30)
const bounds = shallowRef({ minimum: 20, maximum: 50 })
let layout: HTMLElement | null = null
let observer: ResizeObserver | undefined
let width = 0
let frame = 0
let pointer: number | null = null
let left = 0

// 1. 只改未被 Vue 绑定的布局变量；拖动不会更新阅读器、AST、目录项或代码块的状态。
function setWidth(value: number) {
  percentage.value = Math.max(bounds.value.minimum, Math.min(bounds.value.maximum, value))
  if (!frame) {
    frame = requestAnimationFrame(() => {
      frame = 0
      layout?.style.setProperty('--blog-toc-width', percentage.value + '%')
    })
  }
}
function finish() {
  if (pointer !== null && handle.value?.hasPointerCapture(pointer)) {
    handle.value.releasePointerCapture(pointer)
  }
  pointer = null
  layout?.removeAttribute('data-toc-resizing')
}
function start(event: PointerEvent) {
  if (event.button !== 0 || !layout || !handle.value) {
    return
  }
  event.preventDefault()
  // 每次手势只读取一次实际边界，移动过程中不反复强制计算布局。
  const rect = layout.getBoundingClientRect()
  left = rect.left
  width = rect.width
  pointer = event.pointerId
  handle.value.setPointerCapture(pointer)
  layout.setAttribute('data-toc-resizing', '')
}
function move(event: PointerEvent) {
  if (event.pointerId === pointer && width > 0) {
    setWidth(((event.clientX - left) / width) * 100)
  }
}
function keyboard(event: KeyboardEvent) {
  const step = event.shiftKey ? 10 : 2
  if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
    setWidth(percentage.value + (event.key === 'ArrowRight' ? step : -step))
  } else if (event.key === 'Home') {
    setWidth(bounds.value.minimum)
  } else if (event.key === 'End') {
    setWidth(bounds.value.maximum)
  } else if (event.key === 'Enter') {
    setWidth(30)
  } else {
    return
  }
  event.preventDefault()
  event.stopPropagation()
}

// 2. 用容器尺寸限定目录与正文的可用宽度；小屏仍由原有目录抽屉接管。
onMounted(() => {
  layout = handle.value?.closest<HTMLElement>('.blog-reading-layout') ?? null
  if (!layout) {
    return
  }
  observer = new ResizeObserver(([entry]) => {
    if (!entry || entry.contentRect.width === width || entry.contentRect.width <= 0) {
      return
    }
    finish()
    width = entry.contentRect.width
    const rem = Number.parseFloat(getComputedStyle(document.documentElement).fontSize) || 16
    bounds.value = {
      minimum: Math.min(30, ((16 * rem) / width) * 100),
      maximum: Math.max(30, Math.min(50, (1 - (28 * rem) / width) * 100)),
    }
    setWidth(percentage.value)
  })
  observer.observe(layout)
})
onBeforeUnmount(() => {
  finish()
  cancelAnimationFrame(frame)
  observer?.disconnect()
  layout?.style.removeProperty('--blog-toc-width')
})
</script>

<template>
  <div
    ref="handle"
    class="blog-toc-resize-handle"
    role="separator"
    tabindex="0"
    aria-label="调整目录宽度"
    aria-orientation="vertical"
    :aria-valuemin="Math.round(bounds.minimum)"
    :aria-valuemax="Math.round(bounds.maximum)"
    :aria-valuenow="Math.round(percentage)"
    :aria-valuetext="'目录宽度 ' + Math.round(percentage) + '%'"
    @pointerdown="start"
    @pointermove="move"
    @pointerup="finish"
    @pointercancel="finish"
    @lostpointercapture="finish"
    @keydown="keyboard"
    @dblclick="setWidth(30)"
  >
    <span aria-hidden="true" />
  </div>
</template>
