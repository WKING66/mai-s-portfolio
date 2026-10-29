<script setup lang="ts">
import type { Editor } from '@tiptap/vue-3'
import type { Editor as CoreEditor, Command } from '@tiptap/core'
import type { EditorToolbarItem } from '@nuxt/ui'
import {
  immersiveTextRanges,
  immersiveSelectedCells,
  immersiveMarkActive,
  formatImmersiveText,
} from '../../utils/editor/interaction/immersiveSelection'
import { immersiveLinkRange } from '../../utils/editor/interaction/immersiveLinks'
import { closeHistory } from '@tiptap/pm/history'
import { PluginKey } from '@tiptap/pm/state'
import {
  alignImmersiveColumns,
  editImmersiveAxes,
  immersiveAxisProblem,
  clearImmersiveCells,
} from '../../utils/editor/interaction/immersiveTables'
const props = defineProps<{
  editor: Editor
  enabled: boolean
  toolbarItems: EditorToolbarItem[][]
}>()
const canvasBoundary = shallowRef<HTMLElement | null>(null)
let boundaryObserver: ResizeObserver | undefined
let layoutFrame = 0
onMounted(async () => {
  await nextTick()
  if (props.editor.isDestroyed) {
    return
  }
  canvasBoundary.value = props.editor.view.dom.closest<HTMLElement>('.canvas-pane')
  boundaryObserver = new ResizeObserver(() => {
    cancelAnimationFrame(layoutFrame)
    layoutFrame = requestAnimationFrame(() => {
      if (!props.editor.isDestroyed) {
        props.editor.view.dispatch(props.editor.state.tr.setMeta(formatMenuKey, 'updatePosition'))
      }
    })
  })
  if (canvasBoundary.value) {
    boundaryObserver.observe(canvasBoundary.value)
  }
})
onBeforeUnmount(() => {
  boundaryObserver?.disconnect()
  cancelAnimationFrame(layoutFrame)
})
const formatMenuKey = new PluginKey('immersive-format-menu')
const tableInsertion = ref(false)
const linkActive = ref(false)
const contextBusy = computed(() => tableInsertion.value || linkActive.value)
watch([contextBusy, () => props.enabled], async ([active]) => {
  await nextTick()
  if (props.editor.isDestroyed) {
    return
  }
  // 拖拽或结构菜单结束时，选区可能未变；主动恢复同一格式条，不修改正文/撤销历史。
  const visible = !active && props.enabled && showFormatTools({ editor: props.editor })
  props.editor.view.dispatch(
    props.editor.state.tr.setMeta(formatMenuKey, visible ? 'show' : 'hide'),
  )
  if (visible) {
    props.editor.view.dispatch(props.editor.state.tr.setMeta(formatMenuKey, 'updatePosition'))
  }
})
function tableCommand(command: Command) {
  props.editor.view.dispatch(closeHistory(props.editor.state.tr))
  props.editor.commands.command(command)
  props.editor.view.dispatch(closeHistory(props.editor.state.tr))
  props.editor.view.focus()
}
function showFormatTools({ editor }: { editor: CoreEditor }) {
  return (
    props.enabled &&
    !contextBusy.value &&
    editor.isEditable &&
    (immersiveTextRanges(editor.state).length > 0 ||
      immersiveSelectedCells(editor.state).length > 0)
  )
}
const hiddenItems: EditorToolbarItem[][] = []
const items = computed<EditorToolbarItem[][]>(() => {
  // BubbleMenu 保留同一实例；没有可格式化选区时不为隐藏按钮反复执行 can()/isActive()。
  if (!showFormatTools({ editor: props.editor })) {
    return hiddenItems
  }
  const rows = props.toolbarItems.map((row) =>
    row.map((item) => {
      if (
        'kind' in item &&
        item.kind === 'mark' &&
        'mark' in item &&
        ['bold', 'italic', 'strike', 'code'].includes(String(item.mark))
      ) {
        const mark = item.mark as 'bold' | 'italic' | 'strike' | 'code'
        return {
          icon: item.icon,
          tooltip: item.tooltip,
          active: immersiveMarkActive(props.editor.state, mark),
          disabled: !immersiveTextRanges(props.editor.state).length,
          onClick: () => {
            props.editor.commands.command(formatImmersiveText(mark))
            props.editor.view.focus()
          },
        }
      }
      if (item.icon === 'i-lucide-link') {
        return { ...item, disabled: !immersiveLinkRange(props.editor) }
      }
      return item
    }),
  )
  rows[0]!.push({
    icon: 'i-lucide-remove-formatting',
    tooltip: { text: '清除文字样式' },
    disabled: !immersiveTextRanges(props.editor.state).length,
    onClick: () => {
      props.editor.commands.command(formatImmersiveText('clear'))
      props.editor.view.focus()
    },
  })
  const inTable = props.editor.isActive('table')
  rows[0]!.push({
    icon: 'i-lucide-align-left',
    tooltip: { text: inTable ? '文本对齐（GFM 整列）' : '文本对齐：Markdown 正文仅支持默认左对齐' },
    items: [
      [
        {
          label: inTable ? 'GFM 对齐作用于整列' : 'Markdown 正文仅支持默认左对齐',
          type: 'label' as const,
        },
      ],
      (['left', 'center', 'right'] as const).map((align, index) => ({
        label: ['左对齐', '居中', '右对齐'][index]!,
        icon: ['i-lucide-align-left', 'i-lucide-align-center', 'i-lucide-align-right'][index]!,
        disabled: !inTable,
        onSelect: () => tableCommand(alignImmersiveColumns(align)),
      })),
    ],
  })
  // 同一上下文条管理文本样式与真实表格选区，不再叠加独立结构菜单。
  if (immersiveSelectedCells(props.editor.state).length || props.editor.isActive('table')) {
    return [
      rows[0]!,
      [
        {
          icon: 'i-lucide-table-properties',
          tooltip: { text: '表格选区操作' },
          items: [
            [
              {
                label: '清空所选单元格',
                icon: 'i-lucide-eraser',
                disabled: !immersiveSelectedCells(props.editor.state).length,
                onSelect: () => tableCommand(clearImmersiveCells()),
              },
            ],
            (['row', 'column'] as const).map((axis) => ({
              label: axis === 'row' ? '删除所选行' : '删除所选列',
              icon: axis === 'row' ? 'i-lucide-rows-2' : 'i-lucide-columns-2',
              disabled: Boolean(immersiveAxisProblem(props.editor.state, 'delete', axis)),
              onSelect: () => tableCommand(editImmersiveAxes('delete', 0, axis)),
            })),
          ],
        },
      ],
      props.toolbarItems
        .at(-1)!
        .filter((item) => 'kind' in item && ['undo', 'redo'].includes(item.kind)),
    ]
  }
  return rows
})
const hasCellSelection = computed(() => immersiveSelectedCells(props.editor.state).length > 0)
const toolbarOptions = computed(() => {
  const boundary = canvasBoundary.value ?? undefined
  return {
    placement: hasCellSelection.value ? ('bottom' as const) : ('top' as const),
    scrollTarget: boundary,
    // 留出外围选择层；由 Floating UI 根据实际选区与滚动边界翻转，不使用固定页面坐标。
    offset: hasCellSelection.value ? 18 : 8,
    shift: { padding: 8, boundary },
    flip: { padding: 8, boundary, fallbackAxisSideDirection: 'end' as const },
    size: {
      boundary,
      padding: 8,
      apply({
        availableWidth,
        elements,
      }: {
        availableWidth: number
        elements: { floating: HTMLElement }
      }) {
        elements.floating.style.maxWidth = Math.max(120, availableWidth) + 'px'
      },
    },
  }
})
// Vue BubbleMenu 只在挂载时读取 options，后续通过同一 PluginKey 更新，避免重建菜单或编辑器。
watch(
  toolbarOptions,
  (options) => {
    if (props.editor.isDestroyed || !canvasBoundary.value) {
      return
    }
    props.editor.view.dispatch(
      props.editor.state.tr.setMeta(formatMenuKey, { type: 'updateOptions', options: { options } }),
    )
    props.editor.view.dispatch(props.editor.state.tr.setMeta(formatMenuKey, 'updatePosition'))
  },
  { flush: 'post' },
)
function selectionAnchor() {
  const cells = immersiveSelectedCells(props.editor.state)
  if (!cells.length) {
    return null
  }
  const canvas = props.editor.view.dom.closest('.canvas-pane')?.getBoundingClientRect()
  const rects = cells
    .map((position) => props.editor.view.nodeDOM(position))
    .filter((node): node is HTMLElement => node instanceof HTMLElement)
    .map((node) => node.getBoundingClientRect())
    .filter((rect) => !canvas || (rect.bottom > canvas.top && rect.top < canvas.bottom))
  if (!rects.length) {
    return null
  }
  const left = Math.max(canvas?.left ?? 0, Math.min(...rects.map((rect) => rect.left)))
  const right = Math.min(
    canvas?.right ?? window.innerWidth,
    Math.max(...rects.map((rect) => rect.right)),
  )
  // 超长行列选区以可见末端单元格作为操作锚点；不把整个离屏表格作为巨大虚拟矩形。
  const firstTop = Math.min(...rects.map((rect) => rect.top))
  const lastBottom = Math.max(...rects.map((rect) => rect.bottom))
  const visibleHead = rects
    .filter((rect) => rect.bottom <= (canvas?.bottom ?? window.innerHeight) - 64)
    .at(-1)
  if (lastBottom - firstTop > (canvas?.height ?? window.innerHeight) - 112 && visibleHead) {
    return {
      contextElement: props.editor.view.dom,
      getBoundingClientRect: () =>
        new DOMRect(left, visibleHead.top, right - left, visibleHead.height),
    }
  }
  const top = Math.max((canvas?.top ?? 0) + 8, Math.min(...rects.map((rect) => rect.top)))
  const bottom = Math.min(
    canvas?.bottom ?? window.innerHeight,
    Math.max(...rects.map((rect) => rect.bottom)),
  )
  return {
    contextElement: props.editor.view.dom,
    getBoundingClientRect: () => new DOMRect(left, top, right - left, Math.max(1, bottom - top)),
  }
}
</script>
<template>
  <UEditorToolbar
    v-if="canvasBoundary"
    :editor="editor"
    :items="items"
    layout="bubble"
    :plugin-key="formatMenuKey"
    :should-show="showFormatTools"
    :get-referenced-virtual-element="selectionAnchor"
    :options="toolbarOptions"
    :ui="{ base: 'max-w-[calc(100vw-2rem)] flex-wrap' }"
  />
  <EditorImmersiveCommandMenu :editor="editor" />
  <EditorImmersiveLinkPopover :editor="editor" @active="linkActive = $event" />
  <EditorImmersiveTableHandles :editor="editor" @active="tableInsertion = $event" />
  <EditorImmersiveBlockMenu :editor="editor" />
</template>
