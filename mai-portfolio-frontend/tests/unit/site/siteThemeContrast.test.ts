import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
const tokens = readFileSync(
  new URL('../../../app/assets/css/design-tokens.css', import.meta.url),
  'utf8',
)

// 验证实际共享 CSS，不另建一套测试颜色配置。
function siteVariables(theme: 'light' | 'dark'): Record<string, string> {
  const blocks = [...tokens.matchAll(/\{([^}]+)\}/g)].map((match) =>
    Object.fromEntries(
      [...match[1]!.matchAll(/(--[\w-]+):\s*([^;]+);/g)].map((item) => [item[1]!, item[2]!]),
    ),
  )
  const raw = { ...blocks[2], ...blocks[theme === 'light' ? 0 : 1] }
  function resolve(value: string): string {
    return value.replace(/var\((--[\w-]+)\)/g, (_, key) => resolve(raw[key]!))
  }
  return Object.fromEntries(Object.entries(raw).map(([key, value]) => [key, resolve(value)]))
}

function luminance(hex: string) {
  const rgb = hex
    .match(/[a-f\d]{2}/gi)!
    .map((channel) => parseInt(channel, 16) / 255)
    .map((value) => (value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4))
  return rgb[0]! * 0.2126 + rgb[1]! * 0.7152 + rgb[2]! * 0.0722
}
function contrast(a: string | undefined, b: string | undefined) {
  if (!a || !b) {
    throw new Error('缺少设计 token')
  }
  const values = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (values[0]! + 0.05) / (values[1]! + 0.05)
}

describe.each(['light', 'dark'] as const)('Site theme %s readability', (theme) => {
  const variables = siteVariables(theme)
  const palette = {
    background: variables['--site-bg'],
    surface: variables['--site-surface'],
    text: variables['--site-text'],
  }
  it('keeps body and supporting text readable on the canvas and cards', () => {
    for (const background of [palette.background, palette.surface]) {
      for (const foreground of [palette.text, variables['--ui-text-muted']]) {
        expect(contrast(foreground, background)).toBeGreaterThanOrEqual(4.5)
      }
    }
  })
  it.each(['primary', 'success', 'info', 'warning', 'error'])(
    'keeps %s buttons and status text readable',
    (color) => {
      const foreground = variables[`--ui-${color}` as keyof typeof variables]
      expect(contrast(foreground, palette.surface)).toBeGreaterThanOrEqual(4.5)
      expect(contrast(foreground, variables['--ui-text-inverted'])).toBeGreaterThanOrEqual(4.5)
    },
  )
  it('keeps the primary hover state readable', () => {
    expect(
      contrast(variables['--site-primary-hover'], variables['--ui-text-inverted']),
    ).toBeGreaterThanOrEqual(4.5)
  })
})
