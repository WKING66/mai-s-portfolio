import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RouteLocationNormalized, RouteLocationNormalizedLoaded } from 'vue-router'

const runtime = vi.hoisted(() => ({
  app: { hooks: { hookOnce: vi.fn() }, $router: { currentRoute: { value: { fullPath: '' } } } },
}))
// 启动阶段不能加载聚合入口；让错误导入在测试中明确失败，而不是靠生产打包掩盖循环。
vi.mock('#app', () => { throw new Error('Router options must not import the #app barrel') })
vi.mock('#app/nuxt', () => ({ useNuxtApp: () => runtime.app }))
import options from '../router.options'

afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks() })

function scroll(path: string, hash: string, fromMatched: unknown[], fromPath = '/') {
  vi.stubGlobal('window', { matchMedia: () => ({ matches: true }) })
  const to = { path, hash, fullPath: path + hash } as RouteLocationNormalized
  const from = { path: fromPath, hash: '', matched: fromMatched } as RouteLocationNormalizedLoaded
  return options.scrollBehavior!(to, from, null)
}

describe('Nuxt router startup and scrolling', () => {
  it('loads the narrow runtime entry without waiting for page or session requests', () => {
    expect(scroll('/', '', [])).toBe(false)
    expect(runtime.app.hooks.hookOnce).not.toHaveBeenCalled()
  })

  it('resolves a same-page anchor without waiting for a page or session request', () => {
    const element = { id: 'projects' }
    vi.stubGlobal('document', { getElementById: () => element })
    vi.stubGlobal('getComputedStyle', () => ({ scrollMarginTop: '96px' }))
    expect(scroll('/', '#projects', [{}])).toEqual({ el: element, top: 96, behavior: 'instant' })
    expect(runtime.app.hooks.hookOnce).not.toHaveBeenCalled()
  })

  it('leaves initial fragment restoration to the synchronous head bootstrap instead of scrolling again', () => {
    expect(scroll('/', '#contact', [])).toBe(false)
    expect(runtime.app.hooks.hookOnce).not.toHaveBeenCalled()
  })

  it('does not reset same-page scrolling for query-only navigation', () => {
    expect(scroll('/projects', '', [{}], '/projects')).toBe(false)
  })
})
