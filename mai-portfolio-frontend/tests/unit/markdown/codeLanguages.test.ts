import { describe, expect, it, vi } from 'vitest'
import { bundledLanguages } from 'shiki/langs'
import { CODE_LANGUAGES, codeLanguageOptions, resolveCodeLanguage } from '../../../app/utils/markdown/code/codeLanguages'
import { highlightCode } from '../../../app/utils/markdown/code/codeHighlight'
import { textContent } from 'comark/utils'

describe('阅读与编辑共享完整语言目录及按需高亮', () => {
  it.each([
    ['c', (i: number) => `int value${i}(int a) { return a + ${i}; }`],
    ['java', (i: number) => `int value${i} = ${i}; // comment`],
    ['python', (i: number) => `value${i} = func(${i})  # comment`],
    ['javascript', (i: number) => `const value${i} = ${i} + 1;`],
    ['sql', (i: number) => `select a, b from table${i} where a = ${i};`],
    ['bash', (i: number) => `printf "%s" "$value${i}" # comment`],
  ])('正常大小的多行 %s 不因节点预算整块丢失高亮', async (language, line) => {
    const source = Array.from({ length: 70 }, (_, index) => line(index)).join('\n')
    const node = await highlightCode(source, language)
    expect(node[1]['data-highlight']).not.toBe('plain')
    expect(textContent(node)).toBe(source)
  })
  it('一个超长行不应让其余正常行一并丢失高亮', async () => {
    const source = 'const longValue="' + 'x'.repeat(1300) + '";\nconst next = 1;'
    const node = await highlightCode(source, 'javascript')
    expect(node[1]['data-highlight']).not.toBe('plain')
    expect(textContent(node)).toBe(source)
    expect(JSON.stringify(node)).toContain('span')
  })
  it('覆盖完整目录，别名/大小写可定位且未知语言不会被改写', () => {
    expect(CODE_LANGUAGES.length).toBeGreaterThan(200)
    expect(resolveCodeLanguage('C++')).toBe('cpp')
    expect(resolveCodeLanguage('CS')).toBe('csharp')
    expect(resolveCodeLanguage('py')).toBe('python')
    expect(resolveCodeLanguage('')).toBe('text')
    expect(resolveCodeLanguage('   ')).toBe('text')
    expect(resolveCodeLanguage('unknown-language')).toBe('text')
    expect(codeLanguageOptions('unknown-language').at(-1)?.value).toBe('unknown-language')
    expect(new Set(CODE_LANGUAGES.map((item) => item.value)).size).toBe(CODE_LANGUAGES.length)
  })
  it.each([
    ['go', 'package main\nfunc main() {}'],
    ['rust', 'fn main() { let x = 1; }'],
    ['php', '<?php echo "Hello";'],
    ['CS', 'class Demo { static int x = 1; }'],
    ['powershell', '$x = Get-Process'],
  ])('新增的%s真实分词而不是标成支持但显示纯文本', async (language, source) => {
    const node = await highlightCode(source, language)
    expect(JSON.stringify(node)).toContain('--shiki-dark:')
    expect(node[1]['data-highlight']).not.toBe('plain')
    expect(JSON.stringify(node)).toContain('span')
  })
  it('未知语言保留原文，大块代码完成后台分词后仍能着色', async () => {
    const source = '未识别语言的原始文本'
    const plain = await highlightCode(source, 'unknown')
    expect(plain[1]['data-highlight']).toBe('plain')
    expect(plain[2]).toEqual(['code', {}, source])
    const largeSource = 'fn main() { let value = 1; }\n'.repeat(600)
    const large = await highlightCode(largeSource, 'rust')
    expect(large[1]['data-highlight']).not.toBe('plain')
    expect(textContent(large)).toBe(largeSource)
    expect(JSON.stringify(large)).toContain('--shiki-nord:')
  })
  it('语法分包下载失败只降级本块，后续能重试加载', async () => {
    const load = vi.spyOn(bundledLanguages, 'ruby').mockRejectedValueOnce(new Error('下载失败'))
    try {
      const source = 'puts "Hello"'
      expect((await highlightCode(source, 'ruby'))[1]['data-highlight']).toBe('plain')
      expect((await highlightCode(source, 'ruby'))[1]['data-highlight']).not.toBe('plain')
      expect(load).toHaveBeenCalledTimes(2)
    } finally {
      load.mockRestore()
    }
  })
})
