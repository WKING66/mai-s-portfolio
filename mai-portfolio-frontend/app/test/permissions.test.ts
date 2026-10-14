import { describe, expect, it } from 'vitest'
import { accessRedirect, hasAccess, safeReturnPath } from '../api/permissions'
import { AUTH_ACCESS, type AuthAccess } from '../constants/auth'

describe('project permissions', () => {
  it('uses one rule for routes, requests and Auth visibility', () => {
    const owner = { loggedIn: true, roles: ['OWNER'] }
    const normal = { loggedIn: true, roles: [] }
    expect(hasAccess(owner, false, AUTH_ACCESS.OWNER)).toBe(false)
    expect(hasAccess(normal, true, AUTH_ACCESS.AUTHENTICATED)).toBe(true)
    expect(hasAccess(normal, true, AUTH_ACCESS.OWNER)).toBe(false)
    expect(hasAccess(owner, true, 'unknown' as AuthAccess)).toBe(false)
    expect(accessRedirect(owner, true, AUTH_ACCESS.OWNER, '/admin/projects/42')).toBeNull()
    expect(accessRedirect(normal, true, AUTH_ACCESS.OWNER, '/admin/projects/42')).toBe('/forbidden')
    expect(accessRedirect({ loggedIn: false, roles: [] }, true, AUTH_ACCESS.OWNER, '/admin/projects/42'))
      .toBe('/login?returnTo=%2Fadmin%2Fprojects%2F42')
  })
  it('requires both authentication and the exact OWNER role', () => {
    expect(hasAccess({ loggedIn: false, roles: ['OWNER'] }, true, AUTH_ACCESS.OWNER)).toBe(false)
    expect(hasAccess({ loggedIn: true, roles: [] }, true, AUTH_ACCESS.OWNER)).toBe(false)
    expect(hasAccess({ loggedIn: true, roles: ['owner'] }, true, AUTH_ACCESS.OWNER)).toBe(false)
    expect(hasAccess({ loggedIn: true, roles: ['OWNER'] }, true, AUTH_ACCESS.OWNER)).toBe(true)
  })
  it.each(['https://evil.test', '//evil.test', '/admin/projects/1?x=1', '/admin/projects/../login', '/admin/profile?returnTo=https://evil.test', undefined])('rejects unsafe return paths: %s', value => {
    expect(safeReturnPath(value)).toBe('/admin/projects')
  })
  it.each(['/admin/projects', '/admin/projects/new', '/admin/projects/42', '/admin/profile', '/account'])('retains a valid authenticated route: %s', value => {
    expect(safeReturnPath(value)).toBe(value)
  })
})
