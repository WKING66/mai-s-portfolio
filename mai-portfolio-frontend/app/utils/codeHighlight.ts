import { createHighlighterCore } from 'shiki/core'
import { createOnigurumaEngine } from 'shiki/engine/oniguruma'
import java from 'shiki/dist/langs/java.mjs'
import typescript from 'shiki/dist/langs/typescript.mjs'
import javascript from 'shiki/dist/langs/javascript.mjs'
import python from 'shiki/dist/langs/python.mjs'
import json from 'shiki/dist/langs/json.mjs'
import sql from 'shiki/dist/langs/sql.mjs'
import bash from 'shiki/dist/langs/bash.mjs'
import light from 'shiki/dist/themes/github-light.mjs'
import dark from 'shiki/dist/themes/github-dark.mjs'
import nord from 'shiki/dist/themes/nord.mjs'
import tokyo from 'shiki/dist/themes/tokyo-night.mjs'
import dracula from 'shiki/dist/themes/dracula.mjs'
import type { ElementNode, Node } from 'comark'
import type { Root, Element, Text } from 'hast'
import { countBlogReading } from './blogReadingProbe'

const themes = { light, dark, nord, tokyo, dracula }
const themeNames = Object.fromEntries(
  Object.entries(themes).map(([key, theme]) => [key, theme.name!]),
)
// 只加载这七种语言和五个主题；共享一个 Promise，避免并发代码块重复创建实例。
const highlighter = createHighlighterCore({
  themes: Object.values(themes),
  langs: [java, typescript, javascript, python, json, sql, bash],
  engine: createOnigurumaEngine(import('shiki/wasm')),
})
// 附加语言遇到时再加载；代码编辑和公开阅读仍复用同一个 Shiki 实例。
const extraLanguages = {
  html: () => import('shiki/dist/langs/html.mjs'),
  css: () => import('shiki/dist/langs/css.mjs'),
  c: () => import('shiki/dist/langs/c.mjs'),
  cpp: () => import('shiki/dist/langs/cpp.mjs'),
  'c++': () => import('shiki/dist/langs/cpp.mjs'),
}
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
export const CODE_HIGHLIGHT_LIMITS = {
  blockCharacters: 12000,
  lineCharacters: 1200,
  blockNodes: 900,
  documentCharacters: 48000,
  documentNodes: 4000,
} as const
export async function highlightCode(code: string, language = '', plain = false) {
  // 1. 根据单块与长行限额选择高亮或完整纯文本，始终保留原代码。
  countBlogReading('highlightRequest')
  plain ||=
    code.length > CODE_HIGHLIGHT_LIMITS.blockCharacters ||
    code.split('\n').some((line) => line.length > CODE_HIGHLIGHT_LIMITS.lineCharacters)
  const key = (plain ? 'plain:' : '') + language + '\0' + code
  if (highlightCache.has(key)) {
    return highlightCache.get(key)!
  }
  const shikiHighlighter = await highlighter
  const loadLanguage = extraLanguages[language as keyof typeof extraLanguages]
  if (loadLanguage && !shikiHighlighter.getLoadedLanguages().includes(language)) {
    await shikiHighlighter.loadLanguage((await loadLanguage()).default)
  }
  // 并发代码块会在 await 初始化时同时命中旧缓存；恢复后再检查，避免重复分词。
  if (highlightCache.has(key)) {
    return highlightCache.get(key)!
  }
  const resolvedLanguage = shikiHighlighter.getLoadedLanguages().includes(language)
    ? language
    : { js: 'javascript', ts: 'typescript', py: 'python', sh: 'bash' }[language] || 'text'
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
  // 2. 多主题一次分词；超过节点限额时降级为可复制的完整纯文本。
  countBlogReading('shikiTokenize')
  const hast: Root = shikiHighlighter.codeToHast(code, {
    lang: resolvedLanguage,
    themes: themeNames,
    defaultColor: false,
  })
  const node = toNode(hast.children[0] as Element) as ElementNode
  if (codeNodeCount(node) > CODE_HIGHLIGHT_LIMITS.blockNodes) {
    return createPlainCodeNode()
  }
  highlightCache.set(key, node)
  if (highlightCache.size > 32) {
    highlightCache.delete(highlightCache.keys().next().value!)
  }
  return node
}
export function codeNodeCount(node: Node): number {
  return Array.isArray(node)
    ? 1 + node.slice(2).reduce<number>((sum, child) => sum + codeNodeCount(child as Node), 0)
    : 0
}
