import { describe, expect, it, vi } from 'vitest'
import { navigationScroll, SCROLL_RESTORATION_BOOTSTRAP_SCRIPT, SCROLL_SNAPSHOT_KEY } from '../../../app/utils/site/scrollPosition'

function bootstrap(saved: unknown = null, hash = '', history: Record<string, unknown> = { scrollRestoration: 'manual' }, readingPending = false) {
  const values = new Map([[SCROLL_SNAPSHOT_KEY, JSON.stringify(saved)]])
  const listeners = new Map<string, () => void>()
  const window = { history, location: { pathname: '/', search: '', hash }, scrollY: 0, scrollX: 0, innerHeight: 800,
    sessionStorage: { getItem: (key: string) => values.get(key), setItem: (key: string, value: string) => values.set(key, value) },
    addEventListener: (event: string, fn: () => void) => listeners.set(event, fn), removeEventListener: (event: string) => listeners.delete(event),
    scrollTo: vi.fn((position: { top: number }) => { window.scrollY = position.top }), requestAnimationFrame: vi.fn(() => 1), cancelAnimationFrame: vi.fn(),
    getComputedStyle: () => ({ scrollMarginTop: '70px' }) }
  const document = { body: {}, readyState: 'loading', documentElement: { scrollHeight: 3500 },
    querySelector: vi.fn(() => readingPending ? {} : null),
    getElementById: (id: string) => id === 'blog' ? { getBoundingClientRect: () => ({ top: 2300 }) } : null,
    addEventListener: (event: string, fn: () => void) => listeners.set(event, fn), removeEventListener: (event: string) => listeners.delete(event) }
  const disconnect = vi.fn()
  class Observer { observe() {} disconnect = disconnect }
  new Function('window', 'document', 'MutationObserver', SCROLL_RESTORATION_BOOTSTRAP_SCRIPT)(window, document, Observer)
  return { window, document, listeners, values, disconnect }
}

describe('immediate refresh restoration and route scrolling', () => {
  it('waits for progressive reading before measuring and restoring a saved anchor', () => {
    const { window, document, listeners } = bootstrap({ path: '/#blog', top: 2410, left: 0 }, '#blog', {}, true)
    expect(window.scrollTo).not.toHaveBeenCalled()
    document.querySelector.mockReturnValue(null)
    listeners.get('DOMContentLoaded')?.()
    expect(window.scrollTo).toHaveBeenCalledWith({ top: 2410, left: 0, behavior: 'instant' })
  })
  it('returns an inherited manual mode to native restoration before client startup', () => {
    const { window } = bootstrap()
    expect(window.history.scrollRestoration).toBe('auto')
    // 此脚本不注册持续监听；后续站内导航仍可由路由改为 manual。
    window.history.scrollRestoration = 'manual'
    expect(window.history.scrollRestoration).toBe('manual')
  })
  it('does not introduce an unsupported restoration property', () => {
    const { window } = bootstrap(null, '', {})
    expect(window.history).toEqual({})
  })
  it('restores the first refresh after selecting a new anchor before hydration, including reading offsets', () => {
    const { window, disconnect } = bootstrap({ path: '/#blog', top: 2410, left: 0 }, '#blog')
    expect(window.scrollTo).toHaveBeenCalledWith({ top: 2410, left: 0, behavior: 'instant' })
    expect(disconnect).toHaveBeenCalledOnce()
  })
  it('records the current URL on unload, not the initial URL before SPA anchor navigation', () => {
    const { window, listeners, values } = bootstrap()
    window.location.hash = '#contact'; window.scrollY = 2700
    listeners.get('pagehide')!()
    expect(JSON.parse(values.get(SCROLL_SNAPSHOT_KEY)!)).toEqual({ path: '/#contact', top: 2700, left: 0 })
  })
  it('does not apply another route snapshot and resolves a fresh fragment with its actual CSS margin', () => {
    const { window } = bootstrap({ path: '/#projects', top: 1300, left: 0 }, '#blog')
    expect(window.scrollTo).toHaveBeenCalledWith({ top: 2230, left: 0, behavior: 'instant' })
  })
  it('stops pending restoration when the user starts interacting', () => {
    const { window, listeners, disconnect } = bootstrap({ path: '/#contact', top: 6000, left: 0 }, '#contact')
    expect(window.scrollTo).not.toHaveBeenCalled()
    listeners.get('wheel')!()
    expect(disconnect).toHaveBeenCalledOnce()
    expect(listeners.has('DOMContentLoaded')).toBe(false)
  })
  const homepage = { path: '/', hash: '#projects' }
  it.each(['#projects', '#blog', ''])('does not override native initial restoration with a router snapshot %s', hash => {
    expect(navigationScroll({ path: '/', hash }, { path: '/', hash: '', matched: [] }, { left: 0, top: 1300 }, false)).toBe(false)
  })
  it('does not snap the initial document back to a fragment after native restoration', () => {
    expect(navigationScroll(homepage, { path: '/', hash: '', matched: [] }, null, false)).toBe(false)
    expect(navigationScroll({ path: '/', hash: '' }, { path: '/', hash: '', matched: [] }, null, false)).toBe(false)
  })
  it('uses immediate saved history position instead of a smooth animation', () => {
    expect(navigationScroll(homepage, { ...homepage, matched: [{}] }, { left: 0, top: 1200 }, false)).toEqual({ left: 0, top: 1200, behavior: 'instant' })
  })
  it('smoothly navigates to a selected section', () => {
    expect(navigationScroll(homepage, { path: '/', hash: '#about', matched: [{}] }, null, false)).toEqual({ el: '#projects', behavior: 'smooth' })
  })
  it('respects reduced motion', () => {
    expect(navigationScroll(homepage, { path: '/', hash: '', matched: [{}] }, null, true)).toEqual({ el: '#projects', behavior: 'instant' })
  })
  it('does not reset scroll on same-page query updates', () => {
    expect(navigationScroll({ path: '/projects', hash: '' }, { path: '/projects', hash: '', matched: [{}] }, null, false)).toBe(false)
  })
  it('starts other pages at the top immediately', () => {
    expect(navigationScroll({ path: '/account', hash: '' }, { ...homepage, matched: [{}] }, null, false)).toEqual({ left: 0, top: 0, behavior: 'instant' })
  })
})
