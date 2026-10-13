export const CODE_THEMES = [
  { label: '跟随网站', value: 'follow' },
  { label: 'GitHub Light', value: 'light' },
  { label: 'GitHub Dark', value: 'dark' },
  { label: 'Nord', value: 'nord' },
  { label: 'Tokyo Night', value: 'tokyo' },
  { label: 'Dracula', value: 'dracula' },
] as const
export type CodeTheme = typeof CODE_THEMES[number]['value']
export const CODE_FOLD = { threshold: 20, previewLines: 14 } as const
export const codeLabel = (filename?: string, language?: string) => filename?.trim() || language?.trim() || '代码'
