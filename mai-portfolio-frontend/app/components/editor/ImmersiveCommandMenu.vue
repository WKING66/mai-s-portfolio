<script setup lang="ts">
import type { Editor } from '@tiptap/core'
import { Plugin } from '@tiptap/pm/state'
import {
  IMMERSIVE_MENU_KEY,
  IMMERSIVE_COMMANDS,
  immersiveCommandProblem,
  executeImmersiveCommand,
  nextImmersiveMenuIndex,
  type ImmersiveMenuTarget,
  type ImmersiveCommandItem,
} from '../../utils/editor/immersiveCommands'
import { IMMERSIVE_OVERLAY_HOST } from '../../utils/editor/immersiveContext'
const props = defineProps<{ editor: Editor }>()
const host = inject(IMMERSIVE_OVERLAY_HOST)
const target = shallowRef<ImmersiveMenuTarget | null>(null)
const search = ref('')
const selected = ref(0)
const panel = useTemplateRef<HTMLElement>('panel')
const menuStyle = ref({ left: '0px', top: '0px', maxWidth: '20rem', maxHeight: '22rem' })
const toast = useToast()
let frame = 0
const entries = computed(() => {
  if (!target.value) {
    return []
  }
  const query = (target.value.kind === 'slash' ? target.value.query : search.value)
    .trim()
    .toLowerCase()
  return IMMERSIVE_COMMANDS.filter(
    (item) =>
      !query || [item.label, ...item.aliases].some((text) => text.toLowerCase().includes(query)),
  ).map((item) => ({ item, problem: immersiveCommandProblem(props.editor, target.value!, item) }))
})
function close() {
  if (!props.editor.isDestroyed) {
    props.editor.view.dispatch(props.editor.state.tr.setMeta(IMMERSIVE_MENU_KEY, null))
  }
}
function positionMenu() {
  frame = 0
  if (!target.value || !panel.value) {
    return
  }
  const current = target.value
  const node = current.kind === 'block' ? props.editor.view.nodeDOM(current.from) : null
  const rect =
    node instanceof HTMLElement
      ? node.getBoundingClientRect()
      : props.editor.view.coordsAtPos(current.to)
  const canvas = props.editor.view.dom.closest('.canvas-pane')?.getBoundingClientRect()
  const left = Math.max(8, canvas?.left ?? 8)
  const right = Math.min(window.innerWidth - 8, (canvas?.right ?? window.innerWidth) - 8)
  const top = Math.max(8, (canvas?.top ?? 0) + 8)
  const bottom = Math.min(window.innerHeight - 8, (canvas?.bottom ?? window.innerHeight) - 8)
  const anchorBottom = current.kind === 'block' ? Math.min(rect.bottom, rect.top + 28) : rect.bottom
  const above = Math.max(0, rect.top - top - 10)
  const below = Math.max(0, bottom - anchorBottom - 10)
  const box = panel.value.getBoundingClientRect()
  const placeAbove =
    current.kind === 'slash'
      ? above >= Math.min(box.height, 160) || above > below
      : below < 120 && above > below
  const height = Math.max(70, Math.min(352, placeAbove ? above : below))
  menuStyle.value = {
    left: Math.max(left, Math.min(rect.left, right - Math.min(box.width, right - left))) + 'px',
    top: (placeAbove ? rect.top - Math.min(box.height, height) - 10 : anchorBottom + 10) + 'px',
    maxWidth: Math.max(100, right - left) + 'px',
    maxHeight: height + 'px',
  }
}
function schedulePosition() {
  if (!frame) {
    frame = requestAnimationFrame(positionMenu)
  }
}
function execute(item: ImmersiveCommandItem) {
  if (!target.value) {
    return
  }
  const problem = executeImmersiveCommand(props.editor, target.value, item)
  if (problem) {
    toast.add({ title: '无法插入', description: problem, color: 'warning' })
    return
  }
  close()
}
function keydown(event: KeyboardEvent) {
  if (!target.value || event.isComposing) {
    return false
  }
  if (event.key === 'Escape') {
    close()
    event.preventDefault()
    return true
  }
  if (['ArrowUp', 'ArrowDown'].includes(event.key)) {
    selected.value = nextImmersiveMenuIndex(
      entries.value.map((entry) => Boolean(entry.problem)),
      selected.value,
      event.key === 'ArrowUp' ? -1 : 1,
    )
  } else if (event.key === 'Enter') {
    const entry = entries.value[selected.value]
    if (entry && !entry.problem) {
      execute(entry.item)
    }
  } else {
    return false
  }
  event.preventDefault()
  event.stopPropagation()
  return true
}
watch(entries, () => {
  selected.value = nextImmersiveMenuIndex(
    entries.value.map((entry) => Boolean(entry.problem)),
    -1,
    1,
  )
})
watch(selected, () => {
  void nextTick(() =>
    panel.value?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' }),
  )
})
watch(
  () => Boolean(target.value),
  (open) => {
    props.editor.commands.setMeta('lockDragHandle', open)
    if (open) {
      search.value = ''
      void nextTick(positionMenu)
    }
  },
)
function handleScroll(event: Event) {
  if (!target.value || (event.target instanceof Node && panel.value?.contains(event.target))) {
    return
  }
  const canvas = props.editor.view.dom.closest('.canvas-pane')
  if (target.value.kind === 'block' && (event.target === canvas || event.target === document)) {
    close()
  } else {
    schedulePosition()
  }
}
function outside(event: PointerEvent) {
  if (
    target.value &&
    event.target instanceof Node &&
    !panel.value?.contains(event.target) &&
    !(event.target instanceof Element && event.target.closest('.immersive-block-handle'))
  ) {
    close()
  }
}
onMounted(() => {
  props.editor.registerPlugin(
    new Plugin<ImmersiveMenuTarget | null>({
      key: IMMERSIVE_MENU_KEY,
      state: {
        init: () => null,
        apply(tr, previous, _old, state) {
          const explicit = tr.getMeta(IMMERSIVE_MENU_KEY)
          if (explicit !== undefined) {
            return explicit
          }
          if (previous?.kind === 'block') {
            return tr.docChanged ? null : previous
          }
          if (!tr.docChanged && !tr.selectionSet) {
            return previous
          }
          const resolved = state.selection.$from
          if (!state.selection.empty || resolved.parent.type.name !== 'paragraph') {
            return null
          }
          const text = resolved.parent.textBetween(0, resolved.parentOffset, undefined, '\ufffc')
          const match = text.match(new RegExp('^/([^\\n]{0,60})$'))
          if (!match || (!previous && !tr.docChanged)) {
            return null
          }
          return {
            kind: 'slash',
            from: resolved.start(),
            to: state.selection.from,
            query: match[1] || '',
          }
        },
      },
      props: { handleKeyDown: (_view, event) => keydown(event) },
      view: (view) => {
        target.value = IMMERSIVE_MENU_KEY.getState(view.state) ?? null
        return {
          update: (current) => {
            target.value = IMMERSIVE_MENU_KEY.getState(current.state) ?? null
            if (target.value) {
              schedulePosition()
            }
          },
        }
      },
    }),
    // 菜单按键先于默认 Enter/方向键；否则默认 keymap 会先拆段落并关闭菜单。
    (plugin, plugins) => [plugin, ...plugins],
  )
  document.addEventListener('pointerdown', outside, true)
  window.addEventListener('scroll', handleScroll, true)
  window.addEventListener('resize', schedulePosition)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', outside, true)
  window.removeEventListener('scroll', handleScroll, true)
  window.removeEventListener('resize', schedulePosition)
  cancelAnimationFrame(frame)
  if (!props.editor.isDestroyed) {
    props.editor.unregisterPlugin(IMMERSIVE_MENU_KEY)
  }
})
</script>
<template>
  <Teleport :to="host || 'body'">
    <template v-if="target">
      <div
        ref="panel"
        data-editor-controls
        data-immersive-overlay
        class="immersive-command-menu"
        :style="menuStyle"
        @mousedown="target.kind === 'slash' && $event.preventDefault()"
      >
        <input
          v-if="target.kind === 'block'"
          v-model="search"
          aria-label="搜索插入命令"
          placeholder="搜索内容块…"
          @keydown="keydown"
        />
        <div role="listbox" aria-label="插入内容命令" class="command-list">
          <button
            v-for="(entry, index) in entries"
            :key="entry.item.label"
            type="button"
            role="option"
            :aria-selected="selected === index"
            :aria-disabled="Boolean(entry.problem)"
            :disabled="Boolean(entry.problem)"
            :title="entry.problem || entry.item.label"
            @mouseenter="!entry.problem && (selected = index)"
            @mousedown.prevent
            @click="execute(entry.item)"
          >
            <UIcon :name="entry.item.icon" class="size-4 shrink-0" /><span
              :class="{ unavailable: entry.problem }"
              >{{ entry.item.label }}</span
            ><small v-if="entry.problem">{{ entry.problem }}</small>
          </button>
          <p v-if="!entries.length" class="p-3 text-sm text-muted">没有匹配命令</p>
        </div>
      </div>
    </template>
  </Teleport>
</template>
<style scoped>
.immersive-command-menu {
  position: fixed;
  z-index: 60;
  width: min(20rem, calc(100vw - 1rem));
  pointer-events: auto;
  border: 1px solid var(--site-line);
  background: var(--site-surface);
  box-shadow: var(--site-shadow);
  border-radius: 0.65rem;
  padding: 0.35rem;
  display: flex;
  flex-direction: column;
}
.immersive-command-menu input {
  width: 100%;
  padding: 0.55rem 0.65rem;
  border-bottom: 1px solid var(--site-line);
  outline-color: var(--site-accent);
}
.command-list {
  min-height: 0;
  overscroll-behavior: contain;
  overflow-y: auto;
}
.command-list button {
  width: 100%;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.65rem;
  border-radius: 0.35rem;
  text-align: left;
  font-size: 0.875rem;
}
.command-list button[aria-selected='true'] {
  color: var(--site-accent);
  background: var(--site-bg-accented);
}
.command-list button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.unavailable {
  text-decoration: line-through;
}
.command-list small {
  flex-basis: 100%;
  font-size: 0.7rem;
  padding-inline-start: 1.5rem;
}
</style>
