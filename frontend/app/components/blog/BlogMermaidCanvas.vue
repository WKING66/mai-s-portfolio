<script setup lang="ts">
const props = withDefaults(defineProps<{ svg: string; fullscreen?: boolean }>(), { fullscreen: false })
const emit = defineEmits<{ fullscreen: [] }>()
const viewport = useTemplateRef<HTMLElement>('viewport')
const plane = useTemplateRef<HTMLElement>('plane')
const limits = { step: 1.2, wheelSensitivity: .002, padding: 16, minimumFitRatio: .2, maximumFitRatio: 8, maximumNaturalScale: 4, keyboardPan: 40, dragThreshold: 5 }
const size = shallowRef({ width: 0, height: 0 }), diagram = shallowRef({ width: 1, height: 1 })
const camera = shallowRef({ x: 0, y: 0, scale: 1 })
const autoFit = ref(true), dragging = ref(false)
const fitScale = computed(() => Math.min(Math.max(1, size.value.width - limits.padding * 2) / diagram.value.width, Math.max(1, size.value.height - limits.padding * 2) / diagram.value.height))
const minimumScale = computed(() => fitScale.value * limits.minimumFitRatio), maximumScale = computed(() => Math.max(fitScale.value * limits.maximumFitRatio, limits.maximumNaturalScale))
const planeStyle = computed(() => ({ width: diagram.value.width + 'px', height: diagram.value.height + 'px', transform: `translate(${camera.value.x}px, ${camera.value.y}px) scale(${camera.value.scale})` }))
let observer: ResizeObserver | undefined, drag: { pointer: number; x: number; y: number; moved: boolean } | undefined
let suppressClick = false
let touchOrigin: { x: number; y: number; moved: boolean } | undefined
let pinch: { ids: number[]; x: number; y: number; distance: number; left: number; top: number; camera: typeof camera.value } | undefined
let touchBlocked = false
function fit() {
  autoFit.value = true
  const scale = fitScale.value
  camera.value = { scale, x: (size.value.width - diagram.value.width * scale) / 2, y: (size.value.height - diagram.value.height * scale) / 2 }
}
function clampScale(scale: number) { return Math.max(minimumScale.value, Math.min(maximumScale.value, scale)) }
function zoomBy(factor: number, x = size.value.width / 2, y = size.value.height / 2) {
  const current = camera.value, scale = clampScale(current.scale * factor)
  autoFit.value = false
  camera.value = { scale, x: x - (x - current.x) * scale / current.scale, y: y - (y - current.y) * scale / current.scale }
}
function wheel(event: WheelEvent) {
  if (!event.ctrlKey) return // 普通滚轮继续滚动文章，局部缩放不改变网页比例。
  event.preventDefault(); event.stopPropagation()
  const rect = viewport.value!.getBoundingClientRect()
  const delta = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? size.value.height : 1)
  zoomBy(Math.exp(-Math.max(-100, Math.min(100, delta)) * limits.wheelSensitivity), event.clientX - rect.left, event.clientY - rect.top)
}
function startDrag(event: PointerEvent) {
  if (event.pointerType === 'touch' || event.button !== 0 || !event.isPrimary) return
  viewport.value?.focus({ preventScroll: true })
  viewport.value?.setPointerCapture(event.pointerId)
  suppressClick = false
  drag = { pointer: event.pointerId, x: event.clientX, y: event.clientY, moved: false }
}
function move(event: PointerEvent) {
  if (!drag || drag.pointer !== event.pointerId) return
  const dx = event.clientX - drag.x, dy = event.clientY - drag.y
  if (!drag.moved && Math.hypot(dx, dy) < limits.dragThreshold) return
  drag.moved = true; dragging.value = true; autoFit.value = false
  camera.value = { ...camera.value, x: camera.value.x + dx, y: camera.value.y + dy }
  drag.x = event.clientX; drag.y = event.clientY
}
function endDrag(event: PointerEvent) {
  if (drag?.pointer !== event.pointerId) return
  suppressClick = drag.moved || event.type === 'pointercancel'
  drag = undefined; dragging.value = false
  if (viewport.value?.hasPointerCapture(event.pointerId)) viewport.value.releasePointerCapture(event.pointerId)
}
function clickDiagram() {
  if (suppressClick) { suppressClick = false; return }
  if (!props.fullscreen) emit('fullscreen')
}
// 触摸独立处理：双指使用距离比和手势中心，单指直接拖拽图表，正文和全屏使用相同手势。
function touchStart(event: TouchEvent) {
  const touches = event.targetTouches
  if (touches.length === 1) {
    suppressClick = false; touchBlocked = false
    touchOrigin = { x: touches[0]!.clientX, y: touches[0]!.clientY, moved: false }
  } else if (touches.length === 2 && !touchBlocked) {
    const first = touches[0]!, second = touches[1]!, rect = viewport.value!.getBoundingClientRect()
    pinch = { ids: [first.identifier, second.identifier], x: (first.clientX + second.clientX) / 2 - rect.left, y: (first.clientY + second.clientY) / 2 - rect.top, distance: Math.max(1, Math.hypot(first.clientX - second.clientX, first.clientY - second.clientY)), left: rect.left, top: rect.top, camera: { ...camera.value } }
    touchOrigin = undefined; suppressClick = true
    event.preventDefault()
  } else if (touches.length > 2) {
    touchBlocked = true; pinch = undefined; suppressClick = true
    event.preventDefault()
  }
}
function touchMove(event: TouchEvent) {
  const touches = event.targetTouches
  if (touchBlocked) { event.preventDefault(); return }
  if (pinch && touches.length === 2) {
    const first = Array.from(touches).find(touch => touch.identifier === pinch!.ids[0]), second = Array.from(touches).find(touch => touch.identifier === pinch!.ids[1])
    if (!first || !second) return
    event.preventDefault(); autoFit.value = false
    const scale = clampScale(pinch.camera.scale * Math.hypot(first.clientX - second.clientX, first.clientY - second.clientY) / pinch.distance)
    camera.value = { scale, x: (first.clientX + second.clientX) / 2 - pinch.left - (pinch.x - pinch.camera.x) * scale / pinch.camera.scale, y: (first.clientY + second.clientY) / 2 - pinch.top - (pinch.y - pinch.camera.y) * scale / pinch.camera.scale }
  } else if (touchOrigin && touches.length === 1) {
    const touch = touches[0]!, dx = touch.clientX - touchOrigin.x, dy = touch.clientY - touchOrigin.y
    if (!touchOrigin.moved && Math.hypot(dx, dy) < limits.dragThreshold) return
    touchOrigin.moved = true; suppressClick = true
    event.preventDefault(); dragging.value = true; autoFit.value = false
    camera.value = { ...camera.value, x: camera.value.x + dx, y: camera.value.y + dy }
    touchOrigin.x = touch.clientX; touchOrigin.y = touch.clientY
  }
}
function touchEnd(event: TouchEvent) {
  // 双指变单指时不立即转为拖拽，避免抬手跳动和合成 click 误开全屏。
  if (pinch || event.type === 'touchcancel') { suppressClick = true; touchBlocked = true; pinch = undefined; touchOrigin = undefined }
  if (event.targetTouches.length === 0) { touchOrigin = undefined; touchBlocked = false; dragging.value = false }
}
function keyboard(event: KeyboardEvent) {
  if (event.key === '+' || event.key === '=') zoomBy(limits.step)
  else if (event.key === '-') zoomBy(1 / limits.step)
  else if (event.key === '0') fit()
  else if (!props.fullscreen && (event.key === 'Enter' || event.key === ' ')) emit('fullscreen')
  else if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(event.key)) {
    autoFit.value = false
    camera.value = { ...camera.value, x: camera.value.x + (event.key === 'ArrowLeft' ? limits.keyboardPan : event.key === 'ArrowRight' ? -limits.keyboardPan : 0), y: camera.value.y + (event.key === 'ArrowUp' ? limits.keyboardPan : event.key === 'ArrowDown' ? -limits.keyboardPan : 0) }
  } else return
  event.preventDefault(); event.stopPropagation()
}
function measureDiagram() {
  const box = plane.value?.querySelector('svg')?.viewBox.baseVal
  if (!box?.width || !box.height) return
  if (box.width !== diagram.value.width || box.height !== diagram.value.height) { diagram.value = { width: box.width, height: box.height }; fit() }
}
watch(() => props.svg, async () => { await nextTick(); measureDiagram() })
onMounted(() => {
  measureDiagram()
  observer = new ResizeObserver(entries => {
    const rect = entries[0]!.contentRect, previous = size.value
    if (rect.width === previous.width && rect.height === previous.height) return
    size.value = { width: rect.width, height: rect.height }
    if (autoFit.value) fit()
    else camera.value = { ...camera.value, x: camera.value.x + (rect.width - previous.width) / 2, y: camera.value.y + (rect.height - previous.height) / 2 }
  })
  if (viewport.value) observer.observe(viewport.value)
})
onBeforeUnmount(() => observer?.disconnect())
defineExpose({ focus: () => viewport.value?.focus({ preventScroll: true }) })
</script>

<template>
  <div class="blog-mermaid-view" :class="{ 'blog-mermaid-view-full': fullscreen }">
    <div class="blog-mermaid-frame">
      <div ref="viewport" class="blog-mermaid-viewport" :class="{ 'blog-mermaid-viewport-full': fullscreen, 'is-dragging': dragging }" :style="{ '--mermaid-aspect-ratio': diagram.width + '/' + diagram.height }" tabindex="0" :role="fullscreen ? 'region' : 'button'" :aria-haspopup="fullscreen ? undefined : 'dialog'" :aria-label="fullscreen ? '全屏图表，可拖拽移动，使用加减键缩放和方向键移动' : '图表内容，点击全屏查看，可拖拽移动，Ctrl 加滚轮缩放'" @wheel="wheel" @keydown="keyboard" @click="clickDiagram" @touchstart="touchStart" @touchmove="touchMove" @touchend="touchEnd" @touchcancel="touchEnd" @pointerdown="startDrag" @pointermove="move" @pointerup="endDrag" @pointercancel="endDrag" @lostpointercapture="endDrag">
        <!-- 仅接收 renderBlogMermaid 安全边界生成的 SVG；缩放/拖拽只更新容器 transform。 -->
        <div ref="plane" class="blog-mermaid-plane" :style="planeStyle" v-html="svg" />
      </div>
    </div>
  </div>
</template>
