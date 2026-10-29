import { beforeEach, describe, expect, it, vi } from 'vitest'
import guard from '../../../app/middleware/auth.global.ts'
import { AUTH_ACCESS } from '../../../app/constants/auth'
const fixture = vi.hoisted(() => ({
  navigate: vi.fn(),
  ensure: vi.fn(),
  mounted: undefined as undefined | (() => Promise<void>),
  app: {
    isHydrating: true,
    payload: { serverRendered: true },
    $router: { currentRoute: { value: { fullPath: '/admin/projects' } } },
  },
  session: { value: { loggedIn: false, roles: [] as string[] } },
  ready: { value: false },
}))
vi.mock('#app', () => ({
  defineNuxtRouteMiddleware: (callback: unknown) => callback,
  navigateTo: fixture.navigate,
  useNuxtApp: () => ({
    ...fixture.app,
    hooks: {
      hookOnce: (_: string, cb: () => Promise<void>) => {
        fixture.mounted = cb
      },
    },
    runWithContext: (cb: () => unknown) => cb(),
  }),
}))
vi.mock('../../../app/composables/useAuthState', () => ({
  useAuthState: () => ({
    ensureSession: fixture.ensure,
    state: fixture.session,
    ready: fixture.ready,
  }),
}))
const route = {
  path: '/admin/projects',
  fullPath: '/admin/projects',
  meta: { auth: AUTH_ACCESS.OWNER },
}
const invoke = () => (guard as unknown as (to: typeof route) => Promise<unknown>)(route)
beforeEach(() => {
  fixture.navigate.mockReset()
  fixture.ensure.mockReset().mockImplementation(async () => {
    fixture.ready.value = true
  })
  fixture.mounted = undefined
  fixture.app.isHydrating = true
  fixture.app.$router.currentRoute.value.fullPath = route.fullPath
  fixture.session.value = { loggedIn: false, roles: [] }
  fixture.ready.value = false
})
describe('protected route initial hydration', () => {
  it('hydrates the SSR pending tree before redirecting anonymous users, preserving returnTo', async () => {
    await invoke()
    expect(fixture.navigate).not.toHaveBeenCalled()
    expect(fixture.ensure).not.toHaveBeenCalled()
    await fixture.mounted!()
    expect(fixture.navigate).toHaveBeenCalledWith('/login?returnTo=%2Fadmin%2Fprojects')
  })
  it('keeps normal client navigation blocking and denies an authenticated non-owner', async () => {
    fixture.app.isHydrating = false
    fixture.session.value.loggedIn = true
    await invoke()
    expect(fixture.navigate).toHaveBeenCalledWith('/forbidden')
  })
  it('does not redirect a newer route after a delayed session response', async () => {
    await invoke()
    fixture.ensure.mockImplementation(async () => {
      fixture.app.$router.currentRoute.value.fullPath = '/blog'
    })
    await fixture.mounted!()
    expect(fixture.navigate).not.toHaveBeenCalled()
  })
  it('keeps confirmed owners on the protected destination', async () => {
    fixture.session.value = { loggedIn: true, roles: ['OWNER'] }
    await invoke()
    await fixture.mounted!()
    expect(fixture.navigate).not.toHaveBeenCalled()
  })
})
