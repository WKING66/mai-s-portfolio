import { describe, expect, it } from 'vitest'
import { canManageProjects, safeReturnPath } from './permissions'

describe('project permissions', () => {
  it('requires both authentication and the exact OWNER role', () => {
    expect(canManageProjects({ loggedIn: false, roles: ['OWNER'] })).toBe(false)
    expect(canManageProjects({ loggedIn: true, roles: [] })).toBe(false)
    expect(canManageProjects({ loggedIn: true, roles: ['owner'] })).toBe(false)
    expect(canManageProjects({ loggedIn: true, roles: ['OWNER'] })).toBe(true)
  })
  it.each(['https://evil.test', '//evil.test', '/admin/projects/1?x=1', '/admin/projects/../login', undefined])('rejects unsafe return paths: %s', value => {
    expect(safeReturnPath(value)).toBe('/admin/projects')
  })
  it.each(['/admin/projects', '/admin/projects/new', '/admin/projects/42'])('retains a valid project route: %s', value => {
    expect(safeReturnPath(value)).toBe(value)
  })
})
