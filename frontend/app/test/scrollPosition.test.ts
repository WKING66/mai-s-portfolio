import { describe, expect, it } from 'vitest'
import { navigationScroll } from '../utils/scrollPosition'

describe('browser-owned refresh and route scrolling', () => {
  const homepage = { path: '/', hash: '#projects' }
  it.each(['#projects', '#blog', ''])('does not reset scroll on initial load %s', hash => {
    expect(navigationScroll({ path: '/', hash }, { path: '/', hash: '', matched: [] }, { left: 0, top: 1300 }, false)).toBe(false)
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
