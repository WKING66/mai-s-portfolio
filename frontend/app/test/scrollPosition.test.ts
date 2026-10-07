import { describe, expect, it } from 'vitest'
import { navigationScroll, SCROLL_RESTORATION_BOOTSTRAP_SCRIPT } from '../utils/scrollPosition'

describe('immediate refresh restoration and route scrolling', () => {
  it('returns an inherited manual mode to native restoration before client startup', () => {
    const window = { history: { scrollRestoration: 'manual' } }
    new Function('window', SCROLL_RESTORATION_BOOTSTRAP_SCRIPT)(window)
    expect(window.history.scrollRestoration).toBe('auto')
    // 此脚本不注册持续监听；后续站内导航仍可由路由改为 manual。
    window.history.scrollRestoration = 'manual'
    expect(window.history.scrollRestoration).toBe('manual')
  })
  it('does not introduce an unsupported restoration property', () => {
    const window = { history: {} }
    new Function('window', SCROLL_RESTORATION_BOOTSTRAP_SCRIPT)(window)
    expect(window.history).toEqual({})
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
