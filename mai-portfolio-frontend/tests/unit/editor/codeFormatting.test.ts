import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import {
  codeFormatter,
  codeFormattingMessage,
  formatCodeSource as formatCanonicalCodeSource,
} from '../../../app/utils/editor/code/codeFormatting'
import { resolveCodeLanguage } from '../../../app/utils/markdown/code/codeLanguages'
import { applyFormattedCode } from '../../../app/utils/editor/code/codeFormattingTransaction'
import { ImmersiveDecorations } from '../../../app/utils/editor/interaction/immersiveDecorations'
import { DecorationSet } from '@tiptap/pm/view'

// 仅以本地文件模拟静态资源传输；运行的仍是生产所用 dprint 与原始 WASM 插件。
const pluginFetch = vi.fn(async (url: string) => {
  const path = url.startsWith('/@fs/')
    ? url.slice(5)
    : resolve(process.cwd(), url.replace(/^\//, ''))
  return new Response(new Uint8Array(readFileSync(path)), {
    headers: { 'Content-Type': 'application/wasm' },
  })
})
beforeAll(() => vi.stubGlobal('fetch', pluginFetch))
afterAll(() => vi.unstubAllGlobals())
const formatCodeSource = (source: string, language: string) =>
  formatCanonicalCodeSource(source, resolveCodeLanguage(language))

describe('代码格式化边界', () => {
  it('光标和部分文字选区都激活代码视图，离开后清除；选区操作不修改正文', () => {
    const editor = new Editor({
      element: null,
      extensions: [StarterKit, ImmersiveDecorations],
      content: {
        type: 'doc',
        content: [
          { type: 'paragraph', content: [{ type: 'text', text: '前文' }] },
          {
            type: 'codeBlock',
            attrs: { language: 'typescript' },
            content: [{ type: 'text', text: 'const a=1;\nconst b=2;' }],
          },
          { type: 'paragraph' },
        ],
      },
    })
    editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
    const active = () =>
      editor.state.plugins.some((plugin) => {
        const decorations = plugin.props.decorations?.call(plugin, editor.state)
        return (
          decorations instanceof DecorationSet &&
          decorations.find().some((item) => item.spec.immersiveCaret)
        )
      })
    try {
      const before = editor.state.doc
      editor.commands.setTextSelection(6)
      expect(active()).toBe(true)
      editor.commands.setTextSelection({ from: 5, to: 17 })
      expect(active()).toBe(true)
      editor.commands.setTextSelection({ from: 1, to: 3 })
      expect(active()).toBe(false)
      expect(editor.state.doc).toBe(before)
    } finally {
      editor.destroy()
    }
  })
  it('统一框架插件下载失败可重试，原文本不被伪格式化', async () => {
    pluginFetch.mockResolvedValueOnce(new Response(null, { status: 503 }))
    await expect(formatCodeSource('x={"a":1}', 'python')).rejects.toThrow('加载失败')
    expect(await formatCodeSource('x={"a":1}', 'python')).toContain('x = ')
  })
  it.each([
    ['js', 'const x={a:1,b:[2,3]}'],
    ['typescript', 'const x:number=1'],
    ['json', '{"a":1,"b":[2,3]}'],
    ['css', '.demo{color:red}'],
    ['html', '<div><span>Hello</span></div>'],
    ['yaml', 'a: [1,2,3]'],
    ['graphql', 'query { user { id name } }'],
    ['markdown', '#  heading\n\n-   item'],
    ['c', 'int main(){int x=1;return x;}'],
    ['cpp', 'int main(){auto x=1;return x;}'],
    ['java', 'class Main{public static void main(String[]args){System.out.println("你好");}}'],
    ['csharp', 'class Demo{static int Main(){return 1;}}'],
    ['python', 'def demo(a,b):\n return {"a":a,"b":b}'],
    ['go', 'package main\nfunc main(){println("你好")}'],
    ['bash', 'if true; then echo "你好"; fi'],
    ['sql', 'select a,b from demo where a=1 and b=2'],
    ['scss', '.demo{color:red}'],
    ['less', '.demo{color:red}'],
    ['json5', '{a:1,b:[2,3]}'],
    ['jsonc', '// 注释\n{"a":1,"b":[2,3]}'],
    ['jsx', 'const view=<div className="x">{value}</div>;'],
    ['tsx', 'const view=<div className="x">{value}</div>;'],
    ['vue', '<template><div>{{msg}}</div></template>\n<script setup>const msg="你好";</script>'],
    ['svelte', '<script>let count=0;</script><button>{count}</button>'],
    ['astro', '---\nconst x=1;\n---\n<div>{x}</div>'],
    ['angular-html', '@if(show){<div>{{msg}}</div>}'],
    ['toml', 'a=[1,2,3]'],
    ['objective-c', '@interface Demo:NSObject\n-(void)test;\n@end'],
    ['objective-cpp', 'int main(){auto x=1;return x;}'],
    ['proto', 'syntax="proto3";message Demo{string name=1;}'],
    ['plsql', 'begin null;end;'],
  ])('%s使用真实解析器，格式化结果可稳定重入', async (language, source) => {
    const result = await formatCodeSource(source, language)
    expect(result).not.toBe(source)
    expect(await formatCodeSource(result, language)).toBe(result)
  })
  it('不支持、语法错误及过长输入不生成猜测的格式化结果', async () => {
    expect(codeFormatter('unknown-language')).toBeUndefined()
    expect(codeFormatter('mdx')).toBeUndefined()
    await expect(formatCodeSource('unknown source', 'unknown-language')).rejects.toThrow(
      'dprint 插件',
    )
    await expect(formatCodeSource('if a:', 'python')).rejects.toThrow()
    await expect(formatCodeSource('const = {', 'javascript')).rejects.toThrow()
    await expect(formatCodeSource('x'.repeat(200001), 'javascript')).rejects.toThrow('过长')
  })
  it('真实笔记的大写 C 围栏及相对路径 include 使用 C 格式化器', async () => {
    const source =
      '/**\n * File: time_complexity.c\n * Created Time: 2023-01-03\n * C fixture\n */\n\n#include "../utils/common.h"\n\nint constant(int n) { int count = 0; for (int i = 0; i < 100000; i++) { count++; } return count; }'
    expect(resolveCodeLanguage('C')).toBe('c')
    const result = await formatCodeSource(source, 'C')
    expect(result).toContain('#include "../utils/common.h"')
    expect(result).toContain('int constant(int n)')
    expect(await formatCodeSource(result, 'C')).toBe(result)
  })
  it('语言选错时先说明当前语言与修正办法，原始解析器错误仅作为详情', async () => {
    const source = '#include "../utils/common.h"\nint main(){return 0;}'
    let failure: unknown
    try {
      await formatCodeSource(source, 'javascript')
    } catch (cause) {
      failure = cause
    }
    expect(failure).toBeInstanceOf(Error)
    const hint = codeFormattingMessage(failure, source, 'JavaScript')
    expect(hint.message).toContain('JavaScript')
    expect(hint.message).toContain('改为 C 或 C++')
    expect(hint.message).toContain('原代码未改动')
    expect(hint.message).not.toContain('Expected')
    expect(hint.detail).toBe((failure as Error).message)
    const network = codeFormattingMessage(
      new Error('格式化语言插件加载失败，请重试；原代码已保留。'),
      source,
      'C',
    )
    expect(network.message).toContain('加载失败')
    expect(network.message).not.toContain('检查语法')
    expect(network.detail).toBe('')
  })
  it('C 代码格式化不重排 include 或修改字符串内容', async () => {
    const result = await formatCodeSource(
      '#include "z.h"\n#include "a.h"\nint main(){puts(" x  y ");}',
      'c',
    )
    expect(result.indexOf('z.h')).toBeLessThan(result.indexOf('a.h'))
    expect(result).toContain('" x  y "')
  })
  it('只改目标代码块并作为单次撤销，异步期间继续输入或改语言则拒绝覆盖', () => {
    const editor = new Editor({
      element: null,
      extensions: [StarterKit],
      content: {
        type: 'doc',
        content: [
          { type: 'paragraph', content: [{ type: 'text', text: '前文' }] },
          {
            type: 'codeBlock',
            attrs: { language: 'javascript' },
            content: [{ type: 'text', text: 'const x=1' }],
          },
          { type: 'paragraph', content: [{ type: 'text', text: '后文' }] },
        ],
      },
    })
    editor.view.updateState(editor.state.reconfigure({ plugins: editor.extensionManager.plugins }))
    // element:null 没有浏览器视图；只模拟视图存活标志，事务与历史仍使用真实 ProseMirror。
    vi.spyOn(editor, 'isDestroyed', 'get').mockReturnValue(false)
    vi.spyOn(editor, 'isEditable', 'get').mockReturnValue(true)
    try {
      const before = editor.state.doc.toJSON()
      const snapshot = { source: 'const x=1', info: 'javascript' }
      expect(applyFormattedCode(editor, 4, snapshot, 'const x = 1;\n')).toBe(true)
      expect(editor.state.doc.firstChild?.textContent).toBe('前文')
      expect(editor.state.doc.lastChild?.textContent).toBe('后文')
      editor.commands.undo()
      expect(editor.state.doc.toJSON()).toEqual(before)
      editor.commands.redo()
      expect(editor.state.doc.nodeAt(4)?.textContent).toBe('const x = 1;\n')
      expect(applyFormattedCode(editor, 4, snapshot, '过期结果')).toBe(false)
      editor.view.dispatch(editor.state.tr.setNodeMarkup(4, undefined, { language: 'python' }))
      expect(
        applyFormattedCode(editor, 4, { source: 'const x = 1;\n', info: 'javascript' }, '过期结果'),
      ).toBe(false)
      expect(applyFormattedCode(editor, undefined, snapshot, '过期结果')).toBe(false)
    } finally {
      vi.restoreAllMocks()
      editor.destroy()
    }
  })
})
