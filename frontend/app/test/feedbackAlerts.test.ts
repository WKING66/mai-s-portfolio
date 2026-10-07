import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { parse } from '@vue/compiler-sfc'
import { parse as parseTemplate, type ElementNode } from '@vue/compiler-dom'

// 检查真实页面模板，防止后来把接口反馈退回普通段落或无视觉提示的 sr-only。
function elements(file: string): ElementNode[] {
  const source = readFileSync(new URL('../' + file, import.meta.url), 'utf8')
  const { descriptor, errors } = parse(source)
  expect(errors).toEqual([])
  const found: ElementNode[] = []
  function walk(nodes: ReturnType<typeof parseTemplate>['children']) {
    for (const node of nodes) {
      if (node.type === 1) { found.push(node); walk(node.children) }
    }
  }
  walk(parseTemplate(descriptor.template!.content).children)
  return found
}

function attribute(node: ElementNode, name: string) {
  const prop = node.props.find(prop => prop.type === 6 && prop.name === name)
  return prop?.type === 6 ? prop.value?.content : undefined
}

const targets = [
  ['pages/login.vue', 3], ['pages/register.vue', 4], ['pages/index.vue', 2],
  ['pages/projects.vue', 1], ['pages/forbidden.vue', 1], ['components/AccountMenu.vue', 2],
  ['components/AccountSettings.vue', 4], ['components/AdminProjectList.vue', 2],
  ['components/ProjectEditor.vue', 2], ['components/ProfileEditor.vue', 2], ['components/SiteHeader.vue', 1],
] as const

describe('visible API feedback components', () => {
  it.each(targets)('%s uses semantic, colored and icon-bearing Alerts (%i)', (file, count) => {
    const nodes = elements(file)
    expect(nodes.filter(node => node.tag === 'p' && attribute(node, 'role') === 'alert')).toEqual([])
    const feedback = nodes.filter(node => node.tag === 'UAlert' && ['error', 'success'].includes(attribute(node, 'color') ?? ''))
    expect(feedback).toHaveLength(count)
    for (const node of feedback) {
      expect(attribute(node, 'role')).toBe(attribute(node, 'color') === 'error' ? 'alert' : 'status')
      expect(attribute(node, 'variant')).toBe('soft')
      expect(attribute(node, 'icon')).toMatch(/^i-lucide-/)
      expect(attribute(node, 'class') ?? '').not.toContain('sr-only')
      expect(node.props.some(prop => prop.type === 7 && prop.name === 'bind' && prop.arg?.type === 4 && prop.arg.content === 'title')).toBe(true)
    }
  })

  it('keeps menu-closed errors visible without changing header layout', () => {
    const alert = elements('components/AccountMenu.vue').find(node => node.tag === 'UAlert'
      && node.props.some(prop => prop.type === 7 && prop.name === 'if' && prop.exp?.type === 4 && prop.exp.content === 'error && !open'))!
    expect(attribute(alert, 'role')).toBe('alert')
    expect(attribute(alert, 'class')).toContain('absolute')
    expect(attribute(alert, 'class')).toContain('max-w-[calc(100vw-2rem)]')
  })

  it('uses an Alert for registration success rather than burying the result in copy', () => {
    const nodes = elements('pages/register.vue')
    const alert = nodes.find(node => node.tag === 'UAlert' && attribute(node, 'color') === 'success')!
    expect(alert.props.some(prop => prop.type === 7 && prop.exp?.type === 4 && prop.exp.content.includes('REGISTRATION_MESSAGES.success'))).toBe(true)
    expect(nodes.filter(node => node.tag === 'p').some(node => node.children.some(child => child.type === 5
      && child.content.type === 4 && child.content.content.includes('REGISTRATION_MESSAGES.success')))).toBe(false)
  })
})
