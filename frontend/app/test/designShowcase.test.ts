import { describe, expect, it } from 'vitest'
import { SHOWCASE_PALETTES, SHOWCASE_COLORS } from '../constants/designShowcase'
import { readFileSync } from 'node:fs'
const tokens = readFileSync(new URL('../assets/css/design-tokens.css', import.meta.url), 'utf8')

// 验证实际共享 CSS，不另建一套测试颜色配置。
function showcaseVariables(theme: 'light' | 'dark'): Record<string, string> {
  const blocks = [...tokens.matchAll(/\{([^}]+)\}/g)].map(match => Object.fromEntries([...match[1]!.matchAll(/(--[\w-]+):\s*([^;]+);/g)].map(item => [item[1]!, item[2]!])))
  const raw = { ...blocks[2], ...blocks[theme === 'light' ? 0 : 1] }
  function resolve(value: string): string { return value.replace(/var\((--[\w-]+)\)/g, (_, key) => resolve(raw[key]!)) }
  return Object.fromEntries(Object.entries(raw).map(([key, value]) => [key, resolve(value)]))
}

function luminance(hex: string) {
  const rgb = hex.match(/[a-f\d]{2}/gi)!.map(channel => parseInt(channel, 16) / 255)
    .map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4)
  return rgb[0]! * .2126 + rgb[1]! * .7152 + rgb[2]! * .0722
}
function contrast(a: string | undefined, b: string | undefined) {
  if (!a || !b) throw new Error('缺少设计 token')
  const values = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (values[0]! + .05) / (values[1]! + .05)
}

describe.each(['light', 'dark'] as const)('Showcase %s readability', theme => {
  const palette = SHOWCASE_PALETTES[theme]
  const variables = showcaseVariables(theme)
  it('keeps body and supporting text readable on the canvas and cards', () => {
    expect(variables['--site-bg']).toBe(palette.background)
    expect(variables['--site-surface']).toBe(palette.surface)
    for (const background of [palette.background, palette.surface]) {
      for (const foreground of [palette.text, variables['--ui-text-muted']]) {
        expect(contrast(foreground, background)).toBeGreaterThanOrEqual(4.5)
      }
    }
  })
  it.each(SHOWCASE_COLORS.filter(color => color !== 'neutral'))('keeps %s buttons and status text readable', color => {
    const foreground = variables[`--ui-${color}` as keyof typeof variables]
    expect(contrast(foreground, palette.surface)).toBeGreaterThanOrEqual(4.5)
    expect(contrast(foreground, variables['--ui-text-inverted'])).toBeGreaterThanOrEqual(4.5)
  })
  it('keeps the primary hover state readable', () => {
    expect(contrast(variables['--showcase-primary-hover'], variables['--ui-text-inverted'])).toBeGreaterThanOrEqual(4.5)
  })
})
