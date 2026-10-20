import { nextTick, type Ref, type ShallowRef } from 'vue'
import type { Editor } from '@tiptap/core'
import { Selection } from '@tiptap/pm/state'
import { IMMERSIVE_POSITION_KEY } from '../utils/editor/immersivePositionHighlight'
import {
  sourceRangeAt,
  type ImmersiveSourceRange,
  type PreparedImmersiveSourceMap,
} from '../utils/editor/immersiveSourceMap'
import {
  measureImmersiveSourceCaret,
  releaseImmersiveSourceMeasure,
} from '../utils/editor/immersiveSourceCaret'

interface PositionOptions {
  editor: ShallowRef<Editor | null>
  source: Ref<string>
  prepared: Ref<PreparedImmersiveSourceMap | undefined>
  pending: Ref<boolean>
  split: Ref<boolean>
  sourceElement: Ref<HTMLTextAreaElement | null>
  canvas: Ref<HTMLElement | null>
  reveal?: (position: number) => void
  bar: Ref<HTMLElement | null>
}
export interface ImmersiveAnchor {
  position: number
  offset: number
  focus: 'source' | 'rich' | null
}
/** 同一个内部画布跨布局保留语义块；主动换块才定位对侧，被动滚动不追赶。 */
export function useImmersivePosition(options: PositionOptions) {
  let previousSourceBlock = ''
  let previousRichBlock = ''
  let revision = 0
  let synchronizing = false
  let highlightTimer: ReturnType<typeof setTimeout> | undefined
  let layoutObserver: ResizeObserver | undefined
  function cancelRestore() {
    revision++
    layoutObserver?.disconnect()
    layoutObserver = undefined
  }
  function mapping() {
    const editor = options.editor.value
    if (!editor || options.pending.value) {
      return []
    }
    const prepared = options.prepared.value
    return prepared?.source === options.source.value && prepared.document === editor.state.doc
      ? prepared.ranges
      : []
  }
  function elementAt(position: number) {
    const node = options.editor.value?.view.nodeDOM(position)
    return node instanceof HTMLElement ? node : node?.parentElement
  }
  function topBlock(position: number) {
    const editor = options.editor.value
    if (!editor) {
      return 0
    }
    const resolved = editor.state.doc.resolve(
      Math.min(editor.state.doc.content.size, Math.max(0, position)),
    )
    return resolved.depth ? resolved.before(1) : resolved.pos
  }
  function richTop() {
    return options.canvas.value?.getBoundingClientRect().top ?? 0
  }
  function scrollRich(position: number, offset = 24, revealOnly = false) {
    const element = elementAt(topBlock(position))
    const canvas = options.canvas.value
    if (!element || !canvas?.clientHeight) {
      return
    }
    const rect = element.getBoundingClientRect()
    if (
      revealOnly &&
      rect.top >= richTop() + 8 &&
      rect.bottom <= canvas.getBoundingClientRect().bottom - 8
    ) {
      return
    }
    const delta = rect.top - richTop() - offset
    if (Math.abs(delta) >= 1) {
      canvas.scrollTop += delta
    }
  }
  function scrollSource(range: ImmersiveSourceRange, position?: number) {
    const input = options.sourceElement.value
    if (!input?.clientHeight) {
      return
    }
    const offset =
      position !== undefined && range.contentSourceFrom !== undefined
        ? Math.min(range.sourceTo, range.contentSourceFrom + Math.max(0, position - range.from - 1))
        : range.sourceFrom
    if (document.activeElement !== input) {
      input.setSelectionRange(offset, offset)
    }
    input.scrollTop = Math.max(
      0,
      measureImmersiveSourceCaret(input, offset) - input.clientHeight * 0.25,
    )
  }
  function remind(range: ImmersiveSourceRange) {
    const input = options.sourceElement.value
    if (input && options.split.value && input.clientHeight) {
      const end =
        range.sourceFrom +
        options.source.value.slice(range.sourceFrom, range.sourceTo).trimEnd().length
      const top = measureImmersiveSourceCaret(input, range.sourceFrom)
      const bottom = measureImmersiveSourceCaret(input, end)
      const lineHeight = Number.parseFloat(getComputedStyle(input).lineHeight) || 24
      input.style.setProperty('--source-line-y', top + 'px')
      input.style.setProperty(
        '--source-range-height',
        Math.max(lineHeight, bottom - top + lineHeight) + 'px',
      )
      input.style.setProperty('--source-highlight-opacity', '1')
    }
    const editor = options.editor.value
    editor?.view.dispatch(editor.state.tr.setMeta(IMMERSIVE_POSITION_KEY, range))
    clearTimeout(highlightTimer)
    highlightTimer = setTimeout(() => {
      input?.style.setProperty('--source-highlight-opacity', '0')
      if (editor && !editor.isDestroyed) {
        editor.view.dispatch(editor.state.tr.setMeta(IMMERSIVE_POSITION_KEY, null))
      }
    }, 1100)
  }
  function selectPosition(position: number) {
    const editor = options.editor.value
    if (!editor) {
      return
    }
    synchronizing = true
    try {
      const selection = Selection.near(
        editor.state.doc.resolve(Math.min(editor.state.doc.content.size, Math.max(0, position))),
      )
      editor.view.dispatch(editor.state.tr.setSelection(selection))
    } finally {
      synchronizing = false
    }
  }
  // 所有明确导航共享同一范围映射和提醒；程序化 Selection 不再反向触发另一轮同步。
  function navigate(position: number) {
    options.reveal?.(position)
    const range = sourceRangeAt(mapping(), position)
    if (!range) {
      return
    }
    cancelRestore()
    selectPosition(position)
    scrollRich(position)
    if (options.split.value) {
      scrollSource(range, position)
    }
    remind(range)
    previousSourceBlock = range.type + ':' + range.sourceFrom
    previousRichBlock = range.type + ':' + range.from
  }
  function sourceAction(force = false) {
    const input = options.sourceElement.value
    if (synchronizing || !options.split.value || !input || document.activeElement !== input) {
      return
    }
    const range = sourceRangeAt(mapping(), input.selectionStart, true)
    if (!range) {
      return
    }
    const key = range.type + ':' + range.sourceFrom
    if (force || previousSourceBlock !== key) {
      cancelRestore()
      const textOffset =
        range.contentSourceFrom === undefined
          ? 1
          : Math.max(1, input.selectionStart - range.contentSourceFrom + 1)
      options.reveal?.(Math.min(range.to - 1, range.from + textOffset))
      selectPosition(Math.min(range.to - 1, range.from + textOffset))
      scrollRich(range.from, 24, true)
      remind(range)
      previousSourceBlock = key
    }
  }
  function richAction(force = false) {
    const editor = options.editor.value
    if (
      synchronizing ||
      !options.split.value ||
      !editor ||
      options.pending.value ||
      (!force && !editor.view.dom.contains(document.activeElement))
    ) {
      return
    }
    const block = topBlock(editor.state.selection.from)
    const blockKey = editor.state.doc.nodeAt(block)?.type.name + ':' + block
    if (!force && previousRichBlock === blockKey) {
      return
    }
    const range = sourceRangeAt(mapping(), editor.state.selection.from)
    if (!range) {
      return
    }
    const key = range.type + ':' + range.from
    if (force || previousRichBlock !== key) {
      scrollSource(range, editor.state.selection.from)
      remind(range)
      previousRichBlock = key
    }
  }
  function capture(preferSource = false): ImmersiveAnchor | undefined {
    const editor = options.editor.value
    const canvas = options.canvas.value
    if (!editor || !canvas) {
      return
    }
    let focus: ImmersiveAnchor['focus'] = null
    if (
      document.activeElement === options.sourceElement.value ||
      (preferSource && options.split.value)
    ) {
      focus = 'source'
    } else if (editor.view.dom.contains(document.activeElement)) {
      focus = 'rich'
    }
    if (focus === 'source') {
      const range = sourceRangeAt(mapping(), options.sourceElement.value!.selectionStart, true)
      if (range) {
        return { position: range.from, offset: 24, focus }
      }
    }
    // 模式切换的画布锚点直接取真实文档，不依赖 Markdown 转换是否正好完成。
    const selected = topBlock(editor.state.selection.from)
    const current = elementAt(selected)
    const top = richTop()
    const bottom = canvas.getBoundingClientRect().bottom
    if (
      focus === 'rich' &&
      current &&
      current.getBoundingClientRect().bottom > top &&
      current.getBoundingClientRect().top < bottom
    ) {
      return {
        position: editor.state.selection.from,
        offset: current.getBoundingClientRect().top - top,
        focus,
      }
    }
    let visible: ImmersiveAnchor | undefined
    editor.state.doc.forEach((_node, position) => {
      if (visible) {
        return
      }
      const element = elementAt(position)
      if (element && element.getBoundingClientRect().bottom > top + 8) {
        visible = {
          position: position + (_node.isTextblock ? 1 : 0),
          offset: element.getBoundingClientRect().top - top,
          focus,
        }
      }
    })
    return visible
  }
  async function restore(anchor?: ImmersiveAnchor) {
    cancelRestore()
    if (!anchor) {
      return
    }
    const expected = revision
    await nextTick()
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
    if (expected !== revision) {
      return
    }
    function align() {
      if (expected === revision) {
        scrollRich(anchor!.position, anchor!.offset)
      }
    }
    align()
    const range = sourceRangeAt(mapping(), anchor.position)
    if (range) {
      remind(range)
    }
    if (options.split.value && range) {
      scrollSource(range, anchor.position)
    }
    if (anchor.focus === 'rich' || (!options.split.value && anchor.focus === 'source')) {
      options.editor.value?.commands.focus(
        Math.min(options.editor.value.state.doc.content.size, Math.max(1, anchor.position)),
        { scrollIntoView: false },
      )
    }
    // 等待的是实际布局变化：字体换行、公式/图表高度和容器尺寸变化都重新校正该锚点。
    // 下一次用户滚动/输入/点击会取消；旧布局任务不得覆盖快速切换的新任务。
    layoutObserver = new ResizeObserver(align)
    for (const element of [
      options.canvas.value,
      options.editor.value?.view.dom,
      elementAt(topBlock(anchor.position)),
    ]) {
      if (element) {
        layoutObserver.observe(element)
      }
    }
  }

  return {
    navigate,
    sourceAction,
    richAction,
    capture,
    restore,
    cancelRestore,
    dispose: () => {
      cancelRestore()
      releaseImmersiveSourceMeasure(options.sourceElement.value)
      clearTimeout(highlightTimer)
    },
  }
}
