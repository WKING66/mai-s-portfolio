<script setup lang="ts">
import type { Editor } from '@tiptap/core'
import type { Node as DocumentNode } from '@tiptap/pm/model'
import { Plugin, PluginKey, TextSelection } from '@tiptap/pm/state'
import { CellSelection, TableMap } from '@tiptap/pm/tables'
import { closeHistory } from '@tiptap/pm/history'
import {
  immersiveTableContext,
  editImmersiveAxes,
  immersiveAxisProblem,
  changeImmersiveTable,
  type ImmersiveTableChange,
} from '../../utils/editor/interaction/immersiveTables'
import { immersiveAxisSelection, selectImmersiveAxes } from '../../utils/editor/interaction/immersiveSelection'
import { IMMERSIVE_OVERLAY_HOST } from '../../utils/editor/session/immersiveContext'

const props = defineProps<{ editor: Editor }>()
const emit = defineEmits<{ active: [active: boolean] }>()
const host = inject(IMMERSIVE_OVERLAY_HOST)
type Axis = 'row' | 'column'
interface TableTarget {
  table: HTMLTableElement
  position: number
  axis: Axis
  index: number
  kind: 'insert' | 'select'
  left: number
  top: number
  line: Record<string, string>
  preview: Record<string, string>
}
const target = shallowRef<TableTarget | null>(null)
const rails = shallowRef<{ rows: TableTarget[]; columns: TableTarget[] } | null>(null)
let tableObserver: ResizeObserver | undefined
const selected = ref(false)
const isDragging = ref(false)
watch(
  () => isDragging.value,
  (value) => emit('active', value),
)
const dropStyle = shallowRef<Record<string, string> | null>(null)
const key = new PluginKey('immersive-table-handles')
let canvas: HTMLElement | null = null
let currentTable: HTMLTableElement | null = null
let pointerFrame = 0
let point = { x: 0, y: 0 }
let gesture: {
  mode: 'select' | 'move'
  id: number
  x: number
  y: number
  origin: TableTarget
  node: DocumentNode
  destination: number
  handle: HTMLElement
} | null = null

function tablePosition(table: HTMLTableElement) {
  // 只解析当前命中的单元格，不遍历正文或假定固定行高。
  const cell = table.rows[0]?.cells[0]
  if (!cell) {
    return null
  }
  const position = props.editor.view.posAtDOM(cell, 0) + 1
  const resolved = props.editor.state.doc.resolve(position)
  for (let depth = resolved.depth; depth > 0; depth--) {
    if (resolved.node(depth).type.name === 'table') {
      return resolved.before(depth)
    }
  }
  return null
}
function clear() {
  if (!gesture) {
    target.value = null
    rails.value = null
    selected.value = false
  }
}
function borderLine(table: DOMRect, axis: Axis, coordinate: number) {
  return axis === 'row'
    ? { left: table.left + 'px', top: coordinate + 'px', width: table.width + 'px', height: '2px' }
    : { left: coordinate + 'px', top: table.top + 'px', width: '2px', height: table.height + 'px' }
}
/** 从真实单元格边界恢复逻辑网格，跨行/跨列不会误当作单个等宽格。 */
function axisRects(table: HTMLTableElement, axis: Axis) {
  if (axis === 'row') {
    return Array.from(table.rows, (row) => row.getBoundingClientRect())
  }
  const position = tablePosition(table)
  const node = position === null ? null : props.editor.state.doc.nodeAt(position)
  if (!node) {
    return []
  }
  const map = TableMap.get(node)
  const boundaries = new Map<number, number>()
  for (const offset of new Set(map.map)) {
    const cell = props.editor.view.nodeDOM(position! + 1 + offset)
    if (!(cell instanceof HTMLElement)) {
      continue
    }
    const logical = map.findCell(offset)
    const rect = cell.getBoundingClientRect()
    boundaries.set(logical.left, rect.left)
    boundaries.set(logical.right, rect.right)
  }
  const bounds = table.getBoundingClientRect()
  // 浏览器没有可观测列边界时不猜测等宽位置；该复杂表格仍可使用原生单元格选择。
  return Array.from({ length: map.width }, (_, index) => {
    const left = boundaries.get(index)
    const right = boundaries.get(index + 1)
    return left === undefined || right === undefined
      ? null
      : new DOMRect(left, bounds.top, right - left, bounds.height)
  })
}
function makeTarget(
  table: HTMLTableElement,
  position: number,
  axis: Axis,
  index: number,
  rect: DOMRect,
): TableTarget {
  const bounds = table.getBoundingClientRect()
  const center = axis === 'row' ? rect.top + rect.height / 2 : rect.left + rect.width / 2
  return {
    table,
    position,
    axis,
    index,
    kind: 'select',
    left: axis === 'row' ? bounds.left - 9 : center,
    top: axis === 'row' ? center : bounds.top - 9,
    line: borderLine(bounds, axis, center),
    preview:
      axis === 'row'
        ? {
            left: bounds.left + 'px',
            top: rect.top + 'px',
            width: bounds.width + 'px',
            height: rect.height + 'px',
          }
        : {
            left: rect.left + 'px',
            top: bounds.top + 'px',
            width: rect.width + 'px',
            height: bounds.height + 'px',
          },
  }
}
function railSelected(item: TableTarget) {
  const axes = immersiveAxisSelection(props.editor.state)
  return Boolean(
    axes?.table === item.position && axes.axis === item.axis && axes.indices.includes(item.index),
  )
}
function enterRail(item: TableTarget) {
  if (!gesture) {
    target.value = item
    selected.value = railSelected(item)
  }
}
function startRail(item: TableTarget, event: PointerEvent) {
  enterRail(item)
  start(event)
}
function locate() {
  pointerFrame = 0
  const table = currentTable
  if (!table?.isConnected || !props.editor.isEditable || gesture) {
    return
  }
  const bounds = table.getBoundingClientRect()
  const viewport = canvas!.getBoundingClientRect()
  const { x, y } = point
  if (
    y < viewport.top ||
    y > viewport.bottom ||
    x < viewport.left ||
    x > viewport.right ||
    x < bounds.left - 24 ||
    x > bounds.right + 16 ||
    y < bounds.top - 24 ||
    y > bounds.bottom + 16
  ) {
    clear()
    return
  }
  const position = tablePosition(table)
  if (position === null) {
    clear()
    return
  }
  const rows = axisRects(table, 'row')
  const columns = axisRects(table, 'column')
  rails.value = {
    rows: rows.flatMap((rect, index) =>
      rect ? [makeTarget(table, position, 'row', index, rect)] : [],
    ),
    columns: columns.flatMap((rect, index) =>
      rect ? [makeTarget(table, position, 'column', index, rect)] : [],
    ),
  }
  target.value = null
  const nearRow = Math.abs(x - bounds.left) <= 20 && y >= bounds.top && y <= bounds.bottom
  const nearColumn = Math.abs(y - bounds.top) <= 20 && x >= bounds.left && x <= bounds.right
  if (!nearRow && !nearColumn) {
    return
  }
  const axis: Axis = nearRow && !nearColumn ? 'row' : 'column'
  const rects = (axis === 'row' ? rows : columns).filter((rect): rect is DOMRect => Boolean(rect))
  const coordinate = axis === 'row' ? y : x
  const borders = [
    ...rects.map((rect) => (axis === 'row' ? rect.top : rect.left)),
    axis === 'row' ? bounds.bottom : bounds.right,
  ]
  const boundary = borders.findIndex((edge) => Math.abs(coordinate - edge) <= 5)
  if (boundary >= 0 && !(axis === 'row' && boundary === 0)) {
    const base = makeTarget(
      table,
      position,
      axis,
      boundary,
      rects[Math.min(boundary, rects.length - 1)]!,
    )
    target.value = {
      ...base,
      kind: 'insert',
      left: axis === 'row' ? bounds.left - 9 : borders[boundary]!,
      top: axis === 'row' ? borders[boundary]! : bounds.top - 9,
      line: borderLine(bounds, axis, borders[boundary]!),
    }
  }
}
function leaveCanvas(event: PointerEvent) {
  if (
    !(event.relatedTarget instanceof Element) ||
    !event.relatedTarget.closest('[data-table-controls]')
  ) {
    clear()
  }
}
function leaveControl(event: PointerEvent) {
  if (!(event.relatedTarget instanceof Node) || !canvas?.contains(event.relatedTarget)) {
    clear()
  }
}
function pointerMove(event: PointerEvent) {
  if (gesture || !(event.target instanceof Element)) {
    return
  }
  if (event.target.closest('[data-table-controls]')) {
    return
  }
  // 拖动文本时不抢入结构操作；只测当前表格的行/列端点。
  if (event.buttons) {
    clear()
    return
  }
  let table = event.target.closest<HTMLTableElement>('table')
  if (!table) {
    // 浏览器已命中普通正文块时，无需再做邻点命中检测。elementFromPoint 会强制刷新
    // 刚被编辑/高亮标脏的长文布局；外侧根容器和 tableWrapper 仍保留手柄探测。
    const block = event.target.closest('.tiptap > *')
    if (block && !block.matches('.tableWrapper')) {
      if (currentTable) {
        tableObserver?.disconnect()
        currentTable = null
        clear()
      }
      return
    }
    // 可以直接从外侧进入选择区；只探测指针旁的两个实际 DOM 点，不扫描全文表格。
    for (const [x, y] of [
      [event.clientX + 24, event.clientY],
      [event.clientX, event.clientY + 24],
    ]) {
      const candidate = document.elementFromPoint(x!, y!)?.closest<HTMLTableElement>('table')
      if (candidate && props.editor.view.dom.contains(candidate)) {
        table = candidate
        break
      }
    }
  }
  if (table && props.editor.view.dom.contains(table)) {
    if (currentTable !== table) {
      tableObserver?.disconnect()
      tableObserver?.observe(table)
    }
    currentTable = table
  }
  if (!currentTable) {
    return
  }
  point = { x: event.clientX, y: event.clientY }
  cancelAnimationFrame(pointerFrame)
  pointerFrame = requestAnimationFrame(locate)
}
function focusCell(item: TableTarget, row: number, column: number) {
  const cell = item.table.rows[row]?.cells[column]
  if (!cell) {
    return false
  }
  return props.editor.commands.setTextSelection(props.editor.view.posAtDOM(cell, 0) + 1)
}
function insert() {
  const item = target.value
  if (!item || item.kind !== 'insert' || (item.axis === 'row' && item.index === 0)) {
    return
  }
  const count = item.axis === 'row' ? item.table.rows.length : item.table.rows[0]!.cells.length
  const index = Math.min(item.index, count - 1)
  if (!focusCell(item, item.axis === 'row' ? index : 0, item.axis === 'column' ? index : 0)) {
    return
  }
  let action: ImmersiveTableChange
  if (item.axis === 'row') {
    action = item.index === count ? 'rowAfter' : 'rowBefore'
  } else {
    action = item.index === count ? 'columnAfter' : 'columnBefore'
  }
  props.editor.commands.command(changeImmersiveTable(action))
  props.editor.commands.focus(undefined, { scrollIntoView: false })
  clear()
}
function select(
  item: TableTarget,
  event?: { shiftKey?: boolean; ctrlKey?: boolean; metaKey?: boolean },
) {
  let mode: 'replace' | 'extend' | 'toggle' = 'replace'
  if (event?.shiftKey) {
    mode = 'extend'
  } else if (event?.ctrlKey || event?.metaKey) {
    mode = 'toggle'
  }
  props.editor.commands.command(selectImmersiveAxes(item.position, item.axis, item.index, mode))
  props.editor.view.focus()
  selected.value = true
}
function start(event: PointerEvent) {
  const item = target.value
  if (!item || item.kind !== 'select' || event.button !== 0) {
    return
  }
  event.preventDefault()
  const axes = immersiveAxisSelection(props.editor.state)
  // 边缘只保留一个入口：首次拖动扩选，选中后拖动移动；Shift 始终用于范围选择。
  const moving = Boolean(
    axes?.table === item.position &&
    axes.axis === item.axis &&
    axes.indices.includes(item.index) &&
    !event.shiftKey &&
    !event.ctrlKey &&
    !event.metaKey,
  )
  if (
    !moving ||
    !axes ||
    axes.table !== item.position ||
    axes.axis !== item.axis ||
    !axes.indices.includes(item.index)
  ) {
    select(item, event)
  }
  const node = props.editor.state.doc.nodeAt(item.position)
  if (!node || (moving && immersiveAxisProblem(props.editor.state, 'move'))) {
    return
  }
  // Pointer Capture 避开下拉菜单/HTML5 Drag 的竞争，触屏和鼠标使用同一条真实事务路径。
  const handle = event.currentTarget as HTMLElement
  handle.setPointerCapture(event.pointerId)
  gesture = {
    mode: moving ? 'move' : 'select',
    id: event.pointerId,
    x: event.clientX,
    y: event.clientY,
    origin: item,
    node,
    destination: -1,
    handle,
  }
}
function move(event: PointerEvent) {
  const drag = gesture
  if (!drag || event.pointerId !== drag.id) {
    return
  }
  if (Math.hypot(event.clientX - drag.x, event.clientY - drag.y) < 4 && !isDragging.value) {
    return
  }
  isDragging.value = true
  const { table, axis } = drag.origin
  const rect = table.getBoundingClientRect()
  const coordinate = axis === 'row' ? event.clientY : event.clientX
  const rects = axisRects(table, axis).filter((rect): rect is DOMRect => Boolean(rect))
  if (drag.mode === 'select') {
    const index = rects.findIndex(
      (box) =>
        coordinate >= (axis === 'row' ? box.top : box.left) &&
        coordinate <= (axis === 'row' ? box.bottom : box.right),
    )
    if (index >= 0) {
      props.editor.commands.command(
        selectImmersiveAxes(drag.origin.position, axis, index, 'extend'),
      )
    }
    return
  }
  let boundary = rects.findIndex(
    (box) => coordinate < (axis === 'row' ? box.top + box.height / 2 : box.left + box.width / 2),
  )
  if (boundary < 0) {
    boundary = rects.length
  }
  if (axis === 'row') {
    boundary = Math.max(1, boundary)
  }
  const within =
    axis === 'row'
      ? event.clientX >= rect.left - 50 && event.clientX <= rect.right + 50
      : event.clientY >= rect.top - 50 && event.clientY <= rect.bottom + 50
  drag.destination = within ? boundary : -1
  let edge: number
  if (boundary === rects.length) {
    edge = axis === 'row' ? rect.bottom : rect.right
  } else {
    edge = axis === 'row' ? rects[boundary]!.top : rects[boundary]!.left
  }
  dropStyle.value = drag.destination < 0 ? null : borderLine(rect, axis, edge)
}
function finish(event?: PointerEvent, cancelled = false) {
  const drag = gesture
  if (!drag || (event && event.pointerId !== drag.id)) {
    return
  }
  gesture = null
  if (drag.handle.hasPointerCapture(drag.id)) {
    drag.handle.releasePointerCapture(drag.id)
  }
  if (
    !cancelled &&
    isDragging.value &&
    drag.mode === 'move' &&
    drag.destination >= 0 &&
    props.editor.state.doc.nodeAt(drag.origin.position) === drag.node
  ) {
    // 移动前后隔开历史组，Undo 只撤销本次行列重排，不吞掉之前的文本输入。
    props.editor.view.dispatch(closeHistory(props.editor.state.tr))
    props.editor.commands.command(editImmersiveAxes('move', drag.destination))
    props.editor.view.dispatch(closeHistory(props.editor.state.tr))
  }
  isDragging.value = false
  dropStyle.value = null
  if (event && drag.mode === 'select') {
    selected.value = true
  } else {
    clear()
  }
}
function cancel() {
  finish(undefined, true)
  clear()
}
function outside(event: PointerEvent) {
  if (event.target instanceof Element && event.target.closest('table') === currentTable) {
    // 单元格单击由 PM 管理文本选区；鼠标仍在表格内时不能把整个悬停层清掉。
    return
  }
  const active = target.value
  if (
    active &&
    Math.abs(event.clientX - active.left) < 16 &&
    Math.abs(event.clientY - active.top) < 16
  ) {
    return
  }
  if (
    !(event.target instanceof Element) ||
    !event.target.closest('[data-table-controls],[data-reka-popper-content-wrapper]')
  ) {
    cancel()
    const { state, view } = props.editor
    if (
      !(
        event.target instanceof Element &&
        event.target.closest('table,[role="toolbar"],[data-editor-controls]')
      ) &&
      (immersiveAxisSelection(state) || state.selection instanceof CellSelection)
    ) {
      view.dispatch(
        state.tr.setSelection(TextSelection.near(state.doc.resolve(state.selection.from + 1))),
      )
    }
  }
}
function keyboard(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    cancel()
  }
}
function moveWithKeyboard(event: KeyboardEvent) {
  const item = target.value
  if (!item || !event.altKey) {
    return
  }
  const step =
    item.axis === 'row'
      ? { ArrowUp: -1, ArrowDown: 1 }[event.key]
      : { ArrowLeft: -1, ArrowRight: 1 }[event.key]
  if (!step) {
    return
  }
  event.preventDefault()
  if (!immersiveAxisSelection(props.editor.state)) {
    select(item)
  }
  props.editor.view.dispatch(closeHistory(props.editor.state.tr))
  const indices = immersiveAxisSelection(props.editor.state)?.indices ?? [item.index]
  const boundary = step < 0 ? Math.min(...indices) - 1 : Math.max(...indices) + 2
  props.editor.commands.command(editImmersiveAxes('move', boundary))
  props.editor.view.dispatch(closeHistory(props.editor.state.tr))
  clear()
}
onMounted(async () => {
  await nextTick()
  if (props.editor.isDestroyed) {
    return
  }
  canvas =
    props.editor.view.dom.closest<HTMLElement>('.canvas-pane') ??
    props.editor.view.dom.parentElement
  tableObserver = new ResizeObserver(() => {
    cancelAnimationFrame(pointerFrame)
    pointerFrame = requestAnimationFrame(locate)
  })
  canvas?.addEventListener('pointermove', pointerMove)
  canvas?.addEventListener('pointerleave', leaveCanvas)
  canvas?.addEventListener('scroll', cancel, true)
  window.addEventListener('resize', cancel)
  window.addEventListener('blur', cancel)
  document.addEventListener('pointerdown', outside, true)
  document.addEventListener('keydown', keyboard, true)
  props.editor.registerPlugin(
    new Plugin({
      key,
      view: () => ({
        update(view, previous) {
          if (view.state.doc !== previous.doc) {
            cancel()
          } else if (!gesture && !view.state.selection.eq(previous.selection)) {
            const context = immersiveTableContext(view.state.doc, view.state.selection)
            if (!context) {
              clear()
            }
          }
        },
      }),
    }),
  )
})
onBeforeUnmount(() => {
  cancel()
  emit('active', false)
  cancelAnimationFrame(pointerFrame)
  tableObserver?.disconnect()
  canvas?.removeEventListener('pointermove', pointerMove)
  canvas?.removeEventListener('pointerleave', leaveCanvas)
  canvas?.removeEventListener('scroll', cancel, true)
  window.removeEventListener('resize', cancel)
  window.removeEventListener('blur', cancel)
  document.removeEventListener('pointerdown', outside, true)
  document.removeEventListener('keydown', keyboard, true)
  if (!props.editor.isDestroyed) {
    props.editor.unregisterPlugin(key)
  }
})
</script>
<template>
  <Teleport :to="host || 'body'">
    <!-- 悬停整张表即可发现连续外围层；选择区域不占正文布局，不遮挡单元格输入。 -->
    <div
      v-if="rails"
      class="table-selection-layer"
      data-table-controls
      data-editor-controls
      data-immersive-overlay
      @pointerleave="leaveControl"
    >
      <template v-for="group in [rails.columns, rails.rows]">
        <button
          v-for="item in group"
          :key="item.axis + item.index"
          type="button"
          class="table-axis-grip"
          :aria-label="(item.axis === 'row' ? '选择整行 ' : '选择整列 ') + (item.index + 1)"
          :aria-pressed="railSelected(item)"
          :style="{
            position: 'fixed',
            left: item.left + 'px',
            top: item.top + 'px',
            transform: 'translate(-50%, -50%)',
            width: item.axis === 'row' ? '10px' : item.preview.width,
            height: item.axis === 'row' ? item.preview.height : '10px',
          }"
          title="单击选择；拖动扩选；Ctrl 追加；选中后拖动移动"
          @pointerenter="enterRail(item)"
          @pointerdown.stop="startRail(item, $event)"
          @pointermove="move"
          @pointerup="finish"
          @pointercancel="finish($event, true)"
          @lostpointercapture="finish($event, true)"
          @keydown.enter.prevent="select(item, $event)"
          @keydown.space.prevent="select(item, $event)"
          @keydown="moveWithKeyboard"
        />
      </template>
    </div>
    <template v-if="target">
      <div
        class="table-boundary-control"
        :class="{ 'table-row-controls': target.axis === 'row' }"
        :style="{ left: target.left + 'px', top: target.top + 'px' }"
        data-table-controls
        data-editor-controls
        data-immersive-overlay
        @pointerdown.stop
        @pointerleave="leaveControl"
      >
        <UButton
          v-if="target.kind === 'insert'"
          icon="i-lucide-plus"
          size="xs"
          variant="soft"
          color="neutral"
          :aria-label="target.axis === 'row' ? '在此边界插入行' : '在此边界插入列'"
          :disabled="target.axis === 'row' && target.index === 0"
          :title="
            target.axis === 'row' && target.index === 0
              ? 'Markdown 表头固定，请从表头下方插入数据行'
              : undefined
          "
          @mousedown.prevent
          @click="insert"
        />
      </div>
      <div
        v-if="target.kind === 'insert' && !(target.axis === 'row' && target.index === 0)"
        class="table-boundary-line"
        :style="target.line"
      />
      <div
        v-if="target.kind === 'select' && !selected && !isDragging"
        class="table-axis-preview"
        :style="target.preview"
      />
      <div v-if="dropStyle" class="table-drop-indicator" :style="dropStyle" />
    </template>
  </Teleport>
</template>
<style scoped>
.table-selection-layer {
  position: fixed;
  inset: 0;
  z-index: 44;
  pointer-events: none;
}
.table-selection-layer button {
  pointer-events: auto;
  border: 0;
}
.table-boundary-control {
  position: fixed;
  transform: translate(-50%, -50%);
  z-index: 45;
  display: flex;
  align-items: center;
  border-radius: 0.35rem;
  background: var(--site-bg);
  pointer-events: auto;
}
.table-axis-grip {
  padding: 0;
  min-width: 0;
  border-radius: 3px;
  background: color-mix(in srgb, var(--site-accent) 14%, transparent);
  cursor: grab;
  touch-action: none;
}
.table-axis-grip[aria-pressed='true'] {
  background: var(--site-accent);
}
.table-axis-grip:active {
  cursor: grabbing;
}
.table-axis-preview {
  position: fixed;
  z-index: 4;
  pointer-events: none;
  background: color-mix(in srgb, var(--site-accent) 7%, transparent);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--site-accent) 28%, transparent);
}
.table-boundary-line,
.table-drop-indicator {
  position: fixed;
  z-index: 44;
  background: var(--site-accent);
  pointer-events: none;
}
.table-boundary-line {
  opacity: 0.35;
}
</style>
