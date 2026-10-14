<script setup lang="ts">
const props = withDefaults(defineProps<{ svg: string; fullscreen?: boolean }>(), {
  fullscreen: false,
})
const emit = defineEmits<{ fullscreen: [] }>()
const viewport = useTemplateRef<HTMLElement>('viewport')
const plane = useTemplateRef<HTMLElement>('plane')
const limits = {
  step: 1.2,
  wheelSensitivity: 0.002,
  padding: 16,
  minimumFitRatio: 0.2,
  maximumFitRatio: 8,
  maximumNaturalScale: 4,
  keyboardPan: 40,
  dragThreshold: 5,
}
const viewportSize = shallowRef({ width: 0, height: 0 }),
  diagramSize = shallowRef({ width: 1, height: 1 })
const camera = shallowRef({ x: 0, y: 0, scale: 1 })
const isAutoFitEnabled = ref(true),
  isDragging = ref(false)
const fitScale = computed(() =>
  Math.min(
    Math.max(1, viewportSize.value.width - limits.padding * 2) / diagramSize.value.width,
    Math.max(1, viewportSize.value.height - limits.padding * 2) / diagramSize.value.height,
  ),
)
const minimumScale = computed(() => fitScale.value * limits.minimumFitRatio),
  maximumScale = computed(() =>
    Math.max(fitScale.value * limits.maximumFitRatio, limits.maximumNaturalScale),
  )
const planeStyle = computed(() => ({
  width: diagramSize.value.width + 'px',
  height: diagramSize.value.height + 'px',
  transform: `translate(${camera.value.x}px, ${camera.value.y}px) scale(${camera.value.scale})`,
}))
let viewportObserver: ResizeObserver | undefined,
  pointerDrag: { pointer: number; x: number; y: number; moved: boolean } | undefined
let suppressClick = false
let touchOrigin: { x: number; y: number; moved: boolean } | undefined
let pinchGesture:
  | {
      ids: number[]
      x: number
      y: number
      distance: number
      left: number
      top: number
      camera: typeof camera.value
    }
  | undefined
let isTouchBlocked = false
function fit() {
  isAutoFitEnabled.value = true
  const scale = fitScale.value
  camera.value = {
    scale,
    x: (viewportSize.value.width - diagramSize.value.width * scale) / 2,
    y: (viewportSize.value.height - diagramSize.value.height * scale) / 2,
  }
}
function clampScale(scale: number) {
  return Math.max(minimumScale.value, Math.min(maximumScale.value, scale))
}
// 1. 以鼠标或手势中心缩放；只更新相机 transform，不重新解析图表。
function zoomBy(
  factor: number,
  x = viewportSize.value.width / 2,
  y = viewportSize.value.height / 2,
) {
  const current = camera.value,
    scale = clampScale(current.scale * factor)
  isAutoFitEnabled.value = false
  camera.value = {
    scale,
    x: x - ((x - current.x) * scale) / current.scale,
    y: y - ((y - current.y) * scale) / current.scale,
  }
}
function wheel(event: WheelEvent) {
  // 普通滚轮继续滚动文章，局部缩放不改变网页比例。
  if (!event.ctrlKey) {
    return
  }
  event.preventDefault()
  event.stopPropagation()
  const rect = viewport.value!.getBoundingClientRect()
  let deltaUnit = 1
  if (event.deltaMode === 1) {
    deltaUnit = 16
  } else if (event.deltaMode === 2) {
    deltaUnit = viewportSize.value.height
  }
  const delta = event.deltaY * deltaUnit
  zoomBy(
    Math.exp(-Math.max(-100, Math.min(100, delta)) * limits.wheelSensitivity),
    event.clientX - rect.left,
    event.clientY - rect.top,
  )
}
// 2. 指针捕获保证鼠标拖拽连续，移动超过阈值后抑制点击全屏。
function startDrag(event: PointerEvent) {
  if (event.pointerType === 'touch' || event.button !== 0 || !event.isPrimary) {
    return
  }
  viewport.value?.focus({ preventScroll: true })
  viewport.value?.setPointerCapture(event.pointerId)
  suppressClick = false
  pointerDrag = { pointer: event.pointerId, x: event.clientX, y: event.clientY, moved: false }
}
function move(event: PointerEvent) {
  if (!pointerDrag || pointerDrag.pointer !== event.pointerId) {
    return
  }
  const dx = event.clientX - pointerDrag.x,
    dy = event.clientY - pointerDrag.y
  if (!pointerDrag.moved && Math.hypot(dx, dy) < limits.dragThreshold) {
    return
  }
  pointerDrag.moved = true
  isDragging.value = true
  isAutoFitEnabled.value = false
  camera.value = { ...camera.value, x: camera.value.x + dx, y: camera.value.y + dy }
  pointerDrag.x = event.clientX
  pointerDrag.y = event.clientY
}
function endDrag(event: PointerEvent) {
  if (pointerDrag?.pointer !== event.pointerId) {
    return
  }
  suppressClick = pointerDrag.moved || event.type === 'pointercancel'
  pointerDrag = undefined
  isDragging.value = false
  if (viewport.value?.hasPointerCapture(event.pointerId)) {
    viewport.value.releasePointerCapture(event.pointerId)
  }
}
function clickDiagram() {
  if (suppressClick) {
    suppressClick = false
    return
  }
  if (!props.fullscreen) {
    emit('fullscreen')
  }
}
// 触摸独立处理：双指使用距离比和手势中心，单指直接拖拽图表，正文和全屏使用相同手势。
function touchStart(event: TouchEvent) {
  const touches = event.targetTouches
  if (touches.length === 1) {
    suppressClick = false
    isTouchBlocked = false
    touchOrigin = { x: touches[0]!.clientX, y: touches[0]!.clientY, moved: false }
  } else if (touches.length === 2 && !isTouchBlocked) {
    const first = touches[0]!,
      second = touches[1]!,
      rect = viewport.value!.getBoundingClientRect()
    pinchGesture = {
      ids: [first.identifier, second.identifier],
      x: (first.clientX + second.clientX) / 2 - rect.left,
      y: (first.clientY + second.clientY) / 2 - rect.top,
      distance: Math.max(
        1,
        Math.hypot(first.clientX - second.clientX, first.clientY - second.clientY),
      ),
      left: rect.left,
      top: rect.top,
      camera: { ...camera.value },
    }
    touchOrigin = undefined
    suppressClick = true
    event.preventDefault()
  } else if (touches.length > 2) {
    isTouchBlocked = true
    pinchGesture = undefined
    suppressClick = true
    event.preventDefault()
  }
}
function touchMove(event: TouchEvent) {
  const touches = event.targetTouches
  if (isTouchBlocked) {
    event.preventDefault()
    return
  }
  if (pinchGesture && touches.length === 2) {
    const first = Array.from(touches).find((touch) => touch.identifier === pinchGesture!.ids[0]),
      second = Array.from(touches).find((touch) => touch.identifier === pinchGesture!.ids[1])
    if (!first || !second) {
      return
    }
    event.preventDefault()
    isAutoFitEnabled.value = false
    const scale = clampScale(
      (pinchGesture.camera.scale *
        Math.hypot(first.clientX - second.clientX, first.clientY - second.clientY)) /
        pinchGesture.distance,
    )
    camera.value = {
      scale,
      x:
        (first.clientX + second.clientX) / 2 -
        pinchGesture.left -
        ((pinchGesture.x - pinchGesture.camera.x) * scale) / pinchGesture.camera.scale,
      y:
        (first.clientY + second.clientY) / 2 -
        pinchGesture.top -
        ((pinchGesture.y - pinchGesture.camera.y) * scale) / pinchGesture.camera.scale,
    }
  } else if (touchOrigin && touches.length === 1) {
    const touch = touches[0]!,
      dx = touch.clientX - touchOrigin.x,
      dy = touch.clientY - touchOrigin.y
    if (!touchOrigin.moved && Math.hypot(dx, dy) < limits.dragThreshold) {
      return
    }
    touchOrigin.moved = true
    suppressClick = true
    event.preventDefault()
    isDragging.value = true
    isAutoFitEnabled.value = false
    camera.value = { ...camera.value, x: camera.value.x + dx, y: camera.value.y + dy }
    touchOrigin.x = touch.clientX
    touchOrigin.y = touch.clientY
  }
}
function touchEnd(event: TouchEvent) {
  // 双指变单指时不立即转为拖拽，避免抬手跳动和合成 click 误开全屏。
  if (pinchGesture || event.type === 'touchcancel') {
    suppressClick = true
    isTouchBlocked = true
    pinchGesture = undefined
    touchOrigin = undefined
  }
  if (event.targetTouches.length === 0) {
    touchOrigin = undefined
    isTouchBlocked = false
    isDragging.value = false
  }
}
// 3. 键盘缩放、移动和重置与同一相机状态同步，避免另建交互状态。
function keyboard(event: KeyboardEvent) {
  if (event.key === '+' || event.key === '=') {
    zoomBy(limits.step)
  } else if (event.key === '-') {
    zoomBy(1 / limits.step)
  } else if (event.key === '0') {
    fit()
  } else if (!props.fullscreen && (event.key === 'Enter' || event.key === ' ')) {
    emit('fullscreen')
  } else if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(event.key)) {
    isAutoFitEnabled.value = false
    let offsetX = 0
    let offsetY = 0
    if (event.key === 'ArrowLeft') {
      offsetX = limits.keyboardPan
    } else if (event.key === 'ArrowRight') {
      offsetX = -limits.keyboardPan
    } else if (event.key === 'ArrowUp') {
      offsetY = limits.keyboardPan
    } else if (event.key === 'ArrowDown') {
      offsetY = -limits.keyboardPan
    }
    camera.value = { ...camera.value, x: camera.value.x + offsetX, y: camera.value.y + offsetY }
  } else {
    return
  }
  event.preventDefault()
  event.stopPropagation()
}
function measureDiagram() {
  const box = plane.value?.querySelector('svg')?.viewBox.baseVal
  if (!box?.width || !box.height) {
    return
  }
  if (box.width !== diagramSize.value.width || box.height !== diagramSize.value.height) {
    diagramSize.value = { width: box.width, height: box.height }
    fit()
  }
}
watch(
  () => props.svg,
  async () => {
    await nextTick()
    measureDiagram()
  },
)
// 4. 自然图表尺寸与容器尺寸分别测量；手动缩放后只修正中心，不强制重新适配。
onMounted(() => {
  measureDiagram()
  viewportObserver = new ResizeObserver((entries) => {
    const rect = entries[0]!.contentRect,
      previous = viewportSize.value
    if (rect.width === previous.width && rect.height === previous.height) {
      return
    }
    viewportSize.value = { width: rect.width, height: rect.height }
    if (isAutoFitEnabled.value) {
      fit()
    } else {
      camera.value = {
        ...camera.value,
        x: camera.value.x + (rect.width - previous.width) / 2,
        y: camera.value.y + (rect.height - previous.height) / 2,
      }
    }
  })
  if (viewport.value) {
    viewportObserver.observe(viewport.value)
  }
})
onBeforeUnmount(() => viewportObserver?.disconnect())
defineExpose({ focus: () => viewport.value?.focus({ preventScroll: true }) })
</script>

<template>
  <div class="blog-mermaid-view" :class="{ 'blog-mermaid-view-full': fullscreen }">
    <div class="blog-mermaid-frame">
      <div
        ref="viewport"
        class="blog-mermaid-viewport"
        :class="{ 'blog-mermaid-viewport-full': fullscreen, 'is-dragging': isDragging }"
        :style="{ '--mermaid-aspect-ratio': diagramSize.width + '/' + diagramSize.height }"
        tabindex="0"
        :role="fullscreen ? 'region' : 'button'"
        :aria-haspopup="fullscreen ? undefined : 'dialog'"
        :aria-label="
          fullscreen
            ? '全屏图表，可拖拽移动，使用加减键缩放和方向键移动'
            : '图表内容，点击全屏查看，可拖拽移动，Ctrl 加滚轮缩放'
        "
        @wheel="wheel"
        @keydown="keyboard"
        @click="clickDiagram"
        @touchstart="touchStart"
        @touchmove="touchMove"
        @touchend="touchEnd"
        @touchcancel="touchEnd"
        @pointerdown="startDrag"
        @pointermove="move"
        @pointerup="endDrag"
        @pointercancel="endDrag"
        @lostpointercapture="endDrag"
      >
        <!-- 仅接收 renderBlogMermaid 安全边界生成的 SVG；缩放/拖拽只更新容器 transform。 -->
        <!-- eslint-disable-next-line vue/no-v-html -- SVG 已由 DOMPurify 及 safeSvg 过滤脚本、外部引用和越界样式，不接受作者 HTML。 -->
        <div ref="plane" class="blog-mermaid-plane" :style="planeStyle" v-html="svg" />
      </div>
    </div>
  </div>
</template>
