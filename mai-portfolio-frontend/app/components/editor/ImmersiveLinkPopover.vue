<script setup lang="ts">
import type { Editor } from '@tiptap/core'
import { Plugin } from '@tiptap/pm/state'
import { closeHistory } from '@tiptap/pm/history'
import {
  IMMERSIVE_LINK_KEY,
  immersiveLinkRange,
  openImmersiveLink,
} from '../../utils/editor/immersiveLinks'
import { IMMERSIVE_OVERLAY_HOST } from '../../utils/editor/immersiveContext'
import { isEditorUrl } from '../../utils/editor/markdownExtensions'
import { safeBlogUrl } from '../../utils/blogResources'
const props = defineProps<{ editor: Editor }>()
const emit = defineEmits<{ active: [active: boolean] }>()
const host = inject(IMMERSIVE_OVERLAY_HOST)
const range = shallowRef<{ from: number; to: number; href: string } | null>(null)
const mode = ref<'preview' | 'edit' | null>(null)
const href = ref('')
const error = ref('')
const panel = useTemplateRef<HTMLElement>('panel')
const input = useTemplateRef<HTMLInputElement>('input')
const style = ref({ left: '0px', top: '0px' })
const previewHref = computed(() => safeBlogUrl(range.value?.href))
const toast = useToast()
let frame = 0
let leaveTimer: ReturnType<typeof setTimeout> | undefined
let applying = false
watch(mode, (value) => emit('active', Boolean(value)), { flush: 'sync' })
function reposition() {
  frame = 0
  if (!range.value || !panel.value) {
    return
  }
  const rect = props.editor.view.coordsAtPos(range.value.from)
  const box = panel.value.getBoundingClientRect()
  const canvas = props.editor.view.dom.closest('.canvas-pane')?.getBoundingClientRect()
  const left = Math.max(8, canvas?.left ?? 8)
  const right = Math.min(window.innerWidth - 8, canvas?.right ?? window.innerWidth - 8)
  const bottom = Math.min(window.innerHeight - 8, canvas?.bottom ?? window.innerHeight - 8)
  style.value = {
    left: Math.max(left, Math.min(rect.left, right - box.width)) + 'px',
    top:
      Math.max(
        canvas?.top ?? 8,
        rect.bottom + 8 + box.height > bottom ? rect.top - box.height - 8 : rect.bottom + 8,
      ) + 'px',
  }
}
function schedule() {
  if (mode.value && !frame) {
    frame = requestAnimationFrame(reposition)
  }
}
function keepOpen() {
  clearTimeout(leaveTimer)
}
function close(restore = false) {
  keepOpen()
  const previous = range.value
  mode.value = null
  range.value = null
  error.value = ''
  if (restore && previous && !props.editor.isDestroyed) {
    props.editor.commands.setTextSelection({ from: previous.from, to: previous.to })
    props.editor.view.focus()
  }
}
function leavePreview() {
  if (mode.value === 'preview') {
    // 只为鼠标跨越链接与浮层间隙留出时间；输入态不做失焦提交。
    leaveTimer = setTimeout(() => close(), 160)
  }
}
function show(position?: number, nextMode: 'preview' | 'edit' = 'edit') {
  if (!props.editor.isEditable || (mode.value === 'edit' && nextMode === 'preview')) {
    return
  }
  const selected = immersiveLinkRange(props.editor, position)
  if (!selected || (nextMode === 'preview' && !selected.href)) {
    return
  }
  keepOpen()
  if (
    mode.value === nextMode &&
    range.value?.from === selected.from &&
    range.value?.to === selected.to
  ) {
    return
  }
  range.value = selected
  href.value = selected.href
  error.value = ''
  mode.value = nextMode
  void nextTick(() => {
    reposition()
    if (nextMode === 'edit') {
      input.value?.focus()
    }
  })
}
function edit() {
  if (!range.value) {
    return
  }
  href.value = range.value.href
  error.value = ''
  mode.value = 'edit'
  void nextTick(() => {
    reposition()
    input.value?.focus()
  })
}
function validUrl(url: string) {
  if (!isEditorUrl(url)) {
    return false
  }
  if (/^https?:\/\//i.test(url)) {
    try {
      return Boolean(new URL(url).hostname)
    } catch {
      return false
    }
  }
  return true
}
function apply(remove = false) {
  const selected = range.value
  if (!selected) {
    return
  }
  const url = href.value.trim()
  if (!remove && !validUrl(url)) {
    error.value = '请输入有效的 HTTP(S)、站内路径或锚点。'
    return
  }
  if (!remove && url === selected.href) {
    close()
    props.editor.view.focus()
    return
  }
  // 只有明确确认/移除才写入真实 Mark；外部点击和 Esc 从不提交输入。
  const before = props.editor.state.doc
  props.editor.view.dispatch(closeHistory(props.editor.state.tr))
  applying = true
  let applied = false
  try {
    const chain = props.editor.chain().setTextSelection({ from: selected.from, to: selected.to })
    applied = (remove ? chain.unsetLink() : chain.setLink({ href: url }))
      .setTextSelection(selected.to)
      .run()
    applied &&= props.editor.state.doc !== before
  } finally {
    applying = false
  }
  props.editor.view.dispatch(closeHistory(props.editor.state.tr))
  if (applied) {
    close()
    props.editor.view.focus()
  } else {
    error.value = '当前位置无法设置链接，原文保持不变。'
  }
}
async function copy() {
  if (!range.value) {
    return
  }
  try {
    await navigator.clipboard.writeText(range.value.href)
    toast.add({ title: '链接已复制', icon: 'i-lucide-check' })
  } catch {
    toast.add({ title: '复制失败，请手动复制链接地址。', color: 'warning' })
  }
}
function outside(event: PointerEvent) {
  if (!mode.value || !(event.target instanceof Node) || panel.value?.contains(event.target)) {
    return
  }
  if (
    event.target instanceof Element &&
    event.target.closest('a,[data-editor-resource-link]') &&
    mode.value === 'preview'
  ) {
    return
  }
  close()
}
onMounted(() => {
  props.editor.registerPlugin(
    new Plugin({
      key: IMMERSIVE_LINK_KEY,
      props: {
        handleKeyDown: (_view, event) => {
          if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
            event.preventDefault()
            openImmersiveLink(props.editor)
            return true
          }
          if (event.key === 'Escape' && mode.value) {
            close()
            return true
          }
          return false
        },
        handleDOMEvents: {
          mouseover: (view, event) => {
            const link =
              event.target instanceof Element
                ? event.target.closest('a,[data-editor-resource-link]')
                : null
            if (link) {
              show(view.posAtDOM(link, 0), 'preview')
            }
            return false
          },
          mouseout: (_view, event) => {
            if (
              event.target instanceof Element &&
              event.target.closest('a,[data-editor-resource-link]') &&
              !(event.relatedTarget instanceof Node && panel.value?.contains(event.relatedTarget))
            ) {
              leavePreview()
            }
            return false
          },
        },
        handleClick: (_view, position, event) => {
          if (
            props.editor.isEditable &&
            event.target instanceof Element &&
            event.target.closest('a,[data-editor-resource-link]') &&
            !event.ctrlKey &&
            !event.metaKey
          ) {
            event.preventDefault()
            show(position, 'preview')
            return true
          }
          return false
        },
      },
      view: () => ({
        update(view, previous) {
          if (!applying && mode.value && !view.state.doc.eq(previous.doc)) {
            close()
          }
        },
      }),
      appendTransaction(transactions) {
        if (transactions.some((tr) => tr.getMeta(IMMERSIVE_LINK_KEY)?.open)) {
          queueMicrotask(() => {
            if (!props.editor.isDestroyed) {
              show()
            }
          })
        }
        return null
      },
    }),
  )
  document.addEventListener('pointerdown', outside, true)
  window.addEventListener('scroll', schedule, true)
  window.addEventListener('resize', schedule)
})
onBeforeUnmount(() => {
  keepOpen()
  cancelAnimationFrame(frame)
  emit('active', false)
  document.removeEventListener('pointerdown', outside, true)
  window.removeEventListener('scroll', schedule, true)
  window.removeEventListener('resize', schedule)
  if (!props.editor.isDestroyed) {
    props.editor.unregisterPlugin(IMMERSIVE_LINK_KEY)
  }
})
</script>
<template>
  <Teleport :to="host || 'body'">
    <div
      v-if="mode"
      ref="panel"
      class="immersive-link-popover"
      :style="style"
      data-editor-controls
      data-immersive-overlay
      role="dialog"
      :aria-label="mode === 'edit' ? '编辑文本链接' : '文本链接预览'"
      @pointerenter="keepOpen"
      @pointerleave="leavePreview"
      @keydown.esc.prevent.stop="close(true)"
    >
      <form v-if="mode === 'edit'" class="flex items-center gap-1" @submit.prevent="apply()">
        <input
          ref="input"
          v-model="href"
          aria-label="文本链接地址"
          placeholder="粘贴链接、站内路径或锚点…"
          autocomplete="off"
        />
        <UButton
          type="submit"
          icon="i-lucide-check"
          aria-label="确认文本链接"
          size="xs"
          variant="ghost"
        />
      </form>
      <div v-else class="flex items-center gap-1">
        <a
          v-if="previewHref"
          :href="previewHref"
          target="_blank"
          rel="noopener noreferrer"
          aria-label="打开链接预览"
          class="link-preview text-primary truncate"
          @pointerdown.stop
          >{{ range?.href }}</a
        >
        <span v-else class="link-preview text-muted truncate">{{ range?.href }}</span>
        <UButton
          icon="i-lucide-pencil"
          aria-label="修改文本链接"
          size="xs"
          variant="ghost"
          @click="edit"
        />
        <UButton
          icon="i-lucide-copy"
          aria-label="复制文本链接"
          size="xs"
          variant="ghost"
          @click="copy"
        />
        <UButton
          icon="i-lucide-unlink"
          aria-label="移除文本链接"
          size="xs"
          variant="ghost"
          @click="apply(true)"
        />
      </div>
      <p v-if="error" role="alert" class="mt-1 text-xs text-error">{{ error }}</p>
    </div>
  </Teleport>
</template>
<style scoped>
.immersive-link-popover {
  position: fixed;
  z-index: 65;
  width: min(22rem, calc(100vw - 2rem));
  max-width: calc(100vw - 2rem);
  padding: 0.3rem;
  border: 1px solid var(--site-line);
  border-radius: 0.5rem;
  background: var(--site-surface);
  box-shadow: var(--site-shadow);
  pointer-events: auto;
}
.immersive-link-popover input {
  min-width: 0;
  flex: 1;
  padding: 0.35rem 0.5rem;
  font-size: 0.875rem;
  border: 0;
  background: transparent;
  outline: none;
}
.immersive-link-popover input:focus-visible {
  box-shadow: inset 0 -1px var(--site-accent);
}
.link-preview {
  flex: 1;
  min-width: 0;
  font-size: 0.875rem;
  padding-inline: 0.3rem;
}
</style>
