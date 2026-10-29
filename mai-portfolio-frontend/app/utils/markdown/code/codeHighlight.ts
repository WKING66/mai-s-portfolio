import { createHighlighterCore } from 'shiki/core'
import { createOnigurumaEngine } from 'shiki/engine/oniguruma'
import { bundledLanguages, type BundledLanguage } from 'shiki/langs'
import { resolveCodeLanguage } from './codeLanguages'
import light from 'shiki/dist/themes/github-light.mjs'
import dark from 'shiki/dist/themes/github-dark.mjs'
import nord from 'shiki/dist/themes/nord.mjs'
import tokyo from 'shiki/dist/themes/tokyo-night.mjs'
import dracula from 'shiki/dist/themes/dracula.mjs'
import type { ElementNode, Node } from 'comark'
import type { Root, Element, Text } from 'hast'
import { countBlogReading } from '../blogReadingProbe'

const themes = { light, dark, nord, tokyo, dracula }
const themeNames = Object.fromEntries(
  Object.entries(themes).map(([key, theme]) => [key, theme.name!]),
)
// 共享主题与引擎；语言只在实际遇到时加载，不把完整语法库塞进首屏。
const highlighter = createHighlighterCore({
  themes: Object.values(themes),
  langs: [],
  engine: createOnigurumaEngine(import('shiki/wasm')),
})
// 附加语言遇到时再加载；代码编辑和公开阅读仍复用同一个 Shiki 实例。
const languageLoads = new Map<string, Promise<void>>()
const highlightCache = new Map<string, ElementNode>()
function toNode(node: Element | Text): Node {
  if (node.type === 'text') {
    return node.value
  }
  return [
    node.tagName,
    { ...node.properties },
    ...node.children
      .filter((child): child is Element | Text => child.type === 'element' || child.type === 'text')
      .map(toNode),
  ] as ElementNode
}
const CACHE_CHARACTERS = 12000
export async function highlightCode(code: string, language = '', plain = false) {
  // 1. 浏览器调用方在 Worker 中分词；代码长度不再导致整块永久失去高亮。
  countBlogReading('highlightRequest')
  const key = (plain ? 'plain:' : '') + language + '\0' + code
  if (highlightCache.has(key)) {
    return highlightCache.get(key)!
  }
  const shikiHighlighter = await highlighter
  let resolvedLanguage = resolveCodeLanguage(language)
  if (
    !plain &&
    resolvedLanguage !== 'text' &&
    !shikiHighlighter.getLoadedLanguages().includes(resolvedLanguage)
  ) {
    let loading = languageLoads.get(resolvedLanguage)
    if (!loading) {
      loading = shikiHighlighter
        .loadLanguage(bundledLanguages[resolvedLanguage as BundledLanguage])
        .catch((cause) => {
          languageLoads.delete(resolvedLanguage)
          throw cause
        })
      languageLoads.set(resolvedLanguage, loading)
    }
    try {
      await loading
    } catch {
      // 语法分包下载失败只降级当前代码块，不能让整篇文章读取失败。
      resolvedLanguage = 'text'
    }
  }
  // 并发代码块会在 await 初始化时同时命中旧缓存；恢复后再检查，避免重复分词。
  if (highlightCache.has(key)) {
    return highlightCache.get(key)!
  }
  const createPlainCodeNode = (): ElementNode => [
    'pre',
    {
      class: 'shiki',
      'data-highlight': 'plain',
      style: Object.entries(themeNames)
        .map(([key, name]) => {
          const theme = shikiHighlighter.getTheme(name)
          return `--shiki-${key}:${theme.fg};--shiki-${key}-bg:${theme.bg}`
        })
        .join(';'),
    },
    ['code', {}, code],
  ]
  if (plain || resolvedLanguage === 'text') {
    return createPlainCodeNode()
  }
  // 2. 多主题一次分词；在 Worker 内保留 Shiki 自身的单行分词超时保护。
  countBlogReading('shikiTokenize')
  const hast: Root = shikiHighlighter.codeToHast(code, {
    lang: resolvedLanguage,
    themes: themeNames,
    defaultColor: false,
    // Shiki 按所有主题的完整样式合并相邻 token，减少阅读 DOM 和编辑装饰数量。
    mergeSameStyleTokens: true,
  })
  const node = toNode(hast.children[0] as Element) as ElementNode
  // 大块结果交给节点持有，避免共享缓存重复保留数份大型代码树。
  if (code.length <= CACHE_CHARACTERS) {
    highlightCache.set(key, node)
  }
  if (highlightCache.size > 32) {
    highlightCache.delete(highlightCache.keys().next().value!)
  }
  return node
}
