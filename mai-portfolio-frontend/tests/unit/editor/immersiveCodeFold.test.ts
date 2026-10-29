import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { transpileModule, ModuleKind } from 'typescript'
import { computed, ref, shallowRef, reactive, shallowReactive, watch, nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { NodeSelection, TextSelection, Selection } from '@tiptap/pm/state'
import { DecorationSet } from '@tiptap/pm/view'
import { ImmersiveDecorations } from '../../../app/utils/editor/interaction/immersiveDecorations'
import { CODE_FOLD } from '../../../app/constants/codeThemes'

// 执行真实组件脚本和真实 PM 选区；重现当前 Vue NodeView 在同一节点上不刷新 decorations props 的行为。
function codeView(onlyCode = false) {
  const editor = new Editor({
    element: null,
    extensions: [
      StarterKit.configure({ trailingNode: false }),
      ImmersiveDecorations.configure({ ready: () => false }),
    ],
    content: {
      type: 'doc',
      content: [
        ...(onlyCode ? [] : [{ type: 'paragraph', content: [{ type: 'text', text: '前文' }] }]),
        {
          type: 'codeBlock',
          attrs: { language: 'typescript' },
          content: [
            {
              type: 'text',
              text: Array.from({ length: 40 }, (_, i) => `const n${i} = ${i};`).join('\n'),
            },
          ],
        },
        ...(onlyCode ? [] : [{ type: 'paragraph', content: [{ type: 'text', text: '后文' }] }]),
      ],
    },
  })
  editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
  const position = onlyCode ? 0 : editor.state.doc.firstChild!.nodeSize
  const paint = editor.state.plugins.find(
    (p) => p.spec.key?.getState(editor.state) instanceof DecorationSet,
  )!
  const decorations = () =>
    (paint.props.decorations!.call(paint, editor.state) as DecorationSet).find(
      position,
      position + editor.state.doc.nodeAt(position)!.nodeSize,
    )
  // 无布局环境只替代 nodeDOM 查询；事务、事件和选区均由真实编辑器执行。
  const componentEditor = new Proxy(editor, {
    get(target, key) {
      if (key === 'view') {
        return {
          nodeDOM: () => null,
          focus: vi.fn(),
          dispatch: editor.view.dispatch.bind(editor.view),
        }
      }
      const value = Reflect.get(target, key, target)
      return typeof value === 'function' ? value.bind(target) : value
    },
  })
  const props = shallowReactive({
    editor: componentEditor,
    getPos: () => position,
    node: editor.state.doc.nodeAt(position)!,
    decorations: decorations(),
    updateAttributes: vi.fn(),
  })
  const update = () => {
    const node = editor.state.doc.nodeAt(position)!
    if (node !== props.node) {
      props.node = node
      props.decorations = decorations()
    }
  }
  editor.on('transaction', update)
  const cleanup: (() => void)[] = []
  const toast = { add: vi.fn() }
  const source = readFileSync(
    new URL('../../../app/components/editor/ImmersiveCode.vue', import.meta.url),
    'utf8',
  )
    .split('<script setup lang="ts">')[1]!
    .split('</script>')[0]!
  const script = transpileModule(source, {
    compilerOptions: { module: ModuleKind.CommonJS },
  }).outputText
  const controls = runInNewContext(script + ';({toggleCode, expanded, collapsed, caretInside});', {
    exports: {},
    require: (name: string) => {
      if (name === '@tiptap/pm/state') {
        return { NodeSelection, TextSelection, Selection }
      }
      if (name.includes('codeThemes')) {
        return { CODE_FOLD, codeLabel: () => '代码' }
      }
      if (name === '@vueuse/core') {
        return { useClipboard: () => ({ copy: vi.fn(), copied: ref(false) }) }
      }
      if (name.includes('codeLanguages')) {
        return { codeLanguageOptions: () => [], resolveCodeLanguage: (value: string) => value }
      }
      if (name.includes('codeInfo')) {
        return { parseCodeInfo: () => ({ filename: '' }) }
      }
      return {}
    },
    defineProps: () => props,
    computed,
    ref,
    shallowRef,
    reactive,
    watch,
    nextTick,
    inject: (_key: unknown, fallback: unknown) => fallback,
    useTemplateRef: () => ref(null),
    useToast: () => toast,
    onBeforeUnmount: (fn: () => void) => cleanup.push(fn),
    HTMLElement: class {
      readonly nodeType = 1
    },
  })
  return {
    editor,
    position,
    props,
    controls,
    toast,
    dispose: () => {
      cleanup.forEach((fn) => fn())
      editor.destroy()
    },
  }
}

describe('代码编辑与收起的真实选区联动', () => {
  it('只有孤立代码块时，节点选区不会被误判为内部光标而重新展开', async () => {
    const view = codeView(true)
    try {
      view.editor.commands.setTextSelection(8)
      view.editor.view.dispatch(view.editor.state.tr.insertText('新增'))
      await nextTick()
      const document = view.editor.state.doc
      await view.controls.toggleCode()
      expect(view.controls.collapsed.value).toBe(true)
      expect(view.editor.state.selection).toBeInstanceOf(NodeSelection)
      expect(view.editor.state.doc).toBe(document)
    } finally {
      view.dispose()
    }
  })
  it('编辑后即使 NodeView 装饰属性滞后，也能收起而不把代码变成全选', async () => {
    const view = codeView()
    try {
      view.editor.commands.setTextSelection(view.position + 8)
      view.editor.view.dispatch(view.editor.state.tr.insertText('新增'))
      await nextTick()
      expect(view.controls.caretInside.value).toBe(true)
      expect(view.controls.collapsed.value).toBe(false)
      expect(view.props.decorations.some((d) => d.spec.immersiveCaret)).toBe(true)
      const document = view.editor.state.doc
      await view.controls.toggleCode()
      await nextTick()
      expect(view.controls.collapsed.value).toBe(true)
      expect(view.editor.state.selection.empty).toBe(true)
      expect(view.editor.state.selection.$from.parent.type.name).toBe('paragraph')
      expect(view.editor.state.doc).toBe(document)
      // 真实 NodeView 的装饰属性仍滞后，收起不能再以它作为选区事实来源。
      expect(view.props.decorations.some((d) => d.spec.immersiveCaret)).toBe(true)
      expect(view.editor.commands.undo()).toBe(true)
      expect(view.editor.state.doc.textContent).not.toContain('新增')
      expect(view.editor.commands.redo()).toBe(true)
      expect(view.editor.state.doc.eq(document)).toBe(true)
      await view.controls.toggleCode()
      view.editor.commands.setTextSelection(view.position + 9)
      view.editor.view.dispatch(view.editor.state.tr.insertText('继续'))
      await nextTick()
      await view.controls.toggleCode()
      expect(view.controls.collapsed.value).toBe(true)
    } finally {
      view.dispose()
    }
  })
  it('文字或跨块选区不被收起操作替换，光标重新进入代码时自动展开', async () => {
    const view = codeView()
    try {
      view.editor.commands.setTextSelection({ from: view.position + 2, to: view.position + 20 })
      await nextTick()
      const selection = view.editor.state.selection
      await view.controls.toggleCode()
      expect(view.editor.state.selection).toBe(selection)
      expect(view.controls.collapsed.value).toBe(false)
      expect(view.toast.add).toHaveBeenCalledOnce()
      view.editor.commands.setTextSelection({ from: 1, to: view.position + 10 })
      await nextTick()
      const across = view.editor.state.selection
      await view.controls.toggleCode()
      expect(view.editor.state.selection).toBe(across)
      view.editor.commands.setTextSelection(1)
      await nextTick()
      await view.controls.toggleCode()
      expect(view.controls.collapsed.value).toBe(true)
      view.editor.commands.setTextSelection(view.position + 3)
      await nextTick()
      expect(view.controls.collapsed.value).toBe(false)
    } finally {
      view.dispose()
    }
  })
})
