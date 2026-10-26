import type { ContextFormatter, FormatterContext } from '@dprint/formatter'

const plugins = {
  web: { load: () => import('@dprint/oxc/plugin.wasm?url') },
  markup: { load: () => import('dprint-plugin-markup/plugin.wasm?url') },
  clang: { load: () => import('@dprint/clang-format/plugin.wasm?url') },
  python: { load: () => import('@dprint/ruff/plugin.wasm?url') },
  go: { load: () => import('@jakebailey/dprint-plugin-gofumpt/plugin.wasm?url') },
  shell: { load: () => import('@dprint/shfmt/plugin.wasm?url') },
  sql: { load: () => import('@dprint/sql/plugin.wasm?url') },
}
type PluginId = keyof typeof plugins
const formats: Record<string, [PluginId, string]> = {
  javascript: ['web', 'code.js'],
  jsx: ['web', 'code.jsx'],
  typescript: ['web', 'code.ts'],
  tsx: ['web', 'code.tsx'],
  json: ['web', 'code.json'],
  jsonc: ['web', 'code.jsonc'],
  json5: ['web', 'code.json5'],
  css: ['web', 'code.css'],
  scss: ['web', 'code.scss'],
  less: ['web', 'code.less'],
  markdown: ['web', 'code.md'],
  yaml: ['web', 'code.yaml'],
  toml: ['web', 'code.toml'],
  graphql: ['web', 'code.graphql'],
  html: ['markup', 'code.html'],
  vue: ['markup', 'code.vue'],
  svelte: ['markup', 'code.svelte'],
  astro: ['markup', 'code.astro'],
  'angular-html': ['markup', 'code.component.html'],
  c: ['clang', 'code.c'],
  cpp: ['clang', 'code.cpp'],
  java: ['clang', 'code.java'],
  csharp: ['clang', 'code.cs'],
  'objective-c': ['clang', 'code.m'],
  'objective-cpp': ['clang', 'code.mm'],
  proto: ['clang', 'code.proto'],
  python: ['python', 'code.py'],
  go: ['go', 'code.go'],
  shellscript: ['shell', 'code.sh'],
  sql: ['sql', 'code.sql'],
  plsql: ['sql', 'code.sql'],
}
export function codeFormatter(language: string) {
  return formats[language]
}

let context: Promise<FormatterContext> | undefined
const loaded = new Map<PluginId, Promise<ContextFormatter>>()
async function loadPlugin(id: PluginId): Promise<ContextFormatter> {
  const existing = loaded.get(id)
  if (existing) {
    return existing
  }
  const pending = (async () => {
    context ??= import('@dprint/formatter').then(({ createContext }) =>
      createContext({ indentWidth: 2, lineWidth: 100, newLineKind: 'lf' }),
    )
    const host = await context
    // Markup 插件通过同一 dprint 上下文格式化嵌入的 JS/TS/CSS。
    if (id === 'markup') {
      await loadPlugin('web')
    }
    const { default: url } = await plugins[id].load()
    const response = await fetch(url)
    if (!response.ok) {
      throw new Error('格式化语言插件加载失败，请重试；原代码已保留。')
    }
    const formatter = await host.addPluginStreaming(
      response,
      id === 'clang' ? { SortIncludes: 'Never' } : {},
    )
    const issues = formatter.getConfigDiagnostics()
    if (issues.length) {
      throw new Error('格式化配置不兼容；原代码已保留。')
    }
    return formatter
  })().catch((cause) => {
    loaded.delete(id)
    throw cause
  })
  loaded.set(id, pending)
  return pending
}

/** 所有语言共用 dprint 的插件协议与配置，不维护各格式化器的独立 JS 调用链。 */
export async function formatCodeSource(source: string, language: string) {
  const target = codeFormatter(language)
  if (!target) {
    throw new Error('当前语言没有可在浏览器运行的 dprint 插件；原代码已保留。')
  }
  if (source.length > 200000) {
    throw new Error('代码过长，请拆分后再格式化；原代码已保留。')
  }
  const formatter = await loadPlugin(target[0])
  return formatter.formatText({
    filePath: target[1],
    fileText: source,
    overrideConfig: language === 'plsql' ? { dialect: 'oracle' } : undefined,
  })
}
