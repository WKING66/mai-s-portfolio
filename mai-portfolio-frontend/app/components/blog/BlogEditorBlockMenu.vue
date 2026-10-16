<script setup lang="ts">
import type { Editor, JSONContent } from '@tiptap/vue-3'
const props = defineProps<{ editor: Editor; disabled?: boolean }>()
const target = shallowRef<{ node: JSONContent; pos: number } | null>(null)
let hoveredNode: ReturnType<Editor['state']['doc']['nodeAt']> = null
function rememberBlock(block: { node: JSONContent; pos: number }) {
  target.value = block
  hoveredNode = props.editor.state.doc.nodeAt(block.pos)
}
function currentBlock() {
  if (props.disabled || !target.value || props.editor.isDestroyed) {
    return null
  }
  const node = props.editor.state.doc.nodeAt(target.value.pos)
  // 手柄打开后若正文已经改变，旧位置不能误操作其他块；等待原生手柄重新确认目标。
  return node === hoveredNode ? node : null
}
function selectBlock(): boolean {
  return Boolean(
    currentBlock() && target.value && props.editor.commands.setNodeSelection(target.value.pos),
  )
}
function insertParagraph() {
  if (props.disabled || !target.value) {
    return
  }
  const node = props.editor.state.doc.nodeAt(target.value.pos)
  if (node) {
    const position = target.value.pos + node.nodeSize
    props.editor
      .chain()
      .insertContentAt(position, { type: 'paragraph' })
      .focus(position + 1)
      .run()
  }
}
const actions = computed(() => [
  {
    label: '转换为正文',
    disabled: !['paragraph', 'heading'].includes(target.value?.node.type ?? ''),
    icon: 'i-lucide-text',
    onSelect() {
      if (!selectBlock()) {
        return
      }
      props.editor.chain().focus().setParagraph().run()
    },
  },
  ...[1, 2, 3, 4, 5, 6].map((level) => ({
    label: '转换为标题 ' + level,
    disabled: !['paragraph', 'heading'].includes(target.value?.node.type ?? ''),
    icon: 'i-lucide-heading',
    onSelect() {
      if (!selectBlock()) {
        return
      }
      props.editor
        .chain()
        .focus()
        .toggleHeading({ level: level as 1 | 2 | 3 | 4 | 5 | 6 })
        .run()
    },
  })),
  {
    label: '创建块副本',
    icon: 'i-lucide-copy',
    onSelect() {
      if (!target.value || props.disabled) {
        return
      }
      const node = props.editor.state.doc.nodeAt(target.value.pos)
      if (node) {
        // 复制真实节点，保留资源身份及原文保护属性，不经 HTML 或纯文本中转。
        props.editor
          .chain()
          .insertContentAt(target.value.pos + node.nodeSize, node.toJSON())
          .focus()
          .run()
      }
    },
  },
  {
    label: '删除块',
    icon: 'i-lucide-trash-2',
    color: 'error' as const,
    onSelect() {
      if (!selectBlock()) {
        return
      }
      props.editor.chain().focus().deleteSelection().run()
    },
  },
])
</script>

<template>
  <UEditorDragHandle v-if="!disabled" :editor="editor" @hover="rememberBlock">
    <template #default>
      <div
        class="flex rounded-lg border border-default bg-default shadow-sm"
        contenteditable="false"
      >
        <UButton
          icon="i-lucide-plus"
          color="neutral"
          variant="ghost"
          size="xs"
          aria-label="在块后添加段落"
          @click.stop="insertParagraph"
        />
        <UDropdownMenu :items="actions">
          <UButton
            icon="i-lucide-grip-vertical"
            color="neutral"
            variant="ghost"
            size="xs"
            aria-label="拖动或操作此块"
          />
        </UDropdownMenu>
      </div>
    </template>
  </UEditorDragHandle>
</template>
