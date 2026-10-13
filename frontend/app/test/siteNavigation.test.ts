import { describe, expect, it } from 'vitest'
import { navigationSection, visibleSection } from '../utils/siteNavigation'

describe('public navigation selection', () => {
  it.each(['about', 'stack', 'projects', 'blog', 'contact'] as const)('selects only the exact homepage hash %s', section => {
    expect(navigationSection('/', '#' + section)).toBe(section)
  })

  it('does not mark public links active while visiting management or the account center', () => {
    expect(navigationSection('/admin/projects', '#projects')).toBeNull()
    expect(navigationSection('/admin/projects/12', '')).toBeNull()
    expect(navigationSection('/account', '#about')).toBeNull()
    expect(navigationSection('/login', '')).toBeNull()
    expect(navigationSection('/projects', '')).toBe('projects')
  })

  it('uses a safe first section for unknown hashes and selects the section at the reading line', () => {
    expect(navigationSection('/', '#invalid')).toBe('about')
    expect(visibleSection([{ id: 'about', top: -400 }, { id: 'stack', top: 100 }, { id: 'projects', top: 600 }], 140)).toBe('stack')
    expect(visibleSection([{ id: 'about', top: -1000 }, { id: 'projects', top: 80 }, { id: 'blog', top: 500 }], 140)).toBe('projects')
  })

  it('highlights the final visible section at document bottom and handles pages without sections', () => {
    expect(visibleSection([{ id: 'blog', top: -20 }, { id: 'contact', top: 300 }], 140, true)).toBe('contact')
    expect(visibleSection([], 140)).toBeNull()
  })
})
