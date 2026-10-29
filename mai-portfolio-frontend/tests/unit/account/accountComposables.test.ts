import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia } from 'pinia'
import { useAuthState } from '../../../app/composables/useAuthState'
import { useAccountProfile } from '../../../app/composables/useAccountProfile'
import { useManagementStore } from '../../../app/stores/management'

const context = vi.hoisted(() => ({ application: undefined as unknown }))
vi.mock('#app', () => ({
  useNuxtApp: () => context.application,
  clearNuxtData: vi.fn(),
  navigateTo: vi.fn(),
}))
vi.mock('../../../app/composables/useAssetUrlRefresh', () => ({ useAssetUrlRefresh: vi.fn() }))
vi.mock('../../../app/composables/useFeedback', () => ({ useFeedback: () => ({ error: vi.fn() }) }))

function createApplication() {
  return {
    $pinia: createPinia(),
    $router: { currentRoute: { value: { path: '/account' } } },
    runWithContext: (operation: () => unknown) => operation(),
  }
}
const owner = { loggedIn: true, username: 'alice', roles: ['OWNER'], csrfToken: 'test-only' }
const profile = { username: 'alice', nickname: '昵称', avatarUrl: null, avatarExpiresAt: null }
beforeEach(() => {
  context.application = createApplication()
})

describe('account composable contracts', () => {
  it('shares state inside one application but isolates separate SSR applications', () => {
    const firstAuth = useAuthState()
    firstAuth.accept(owner)
    const firstProfile = useAccountProfile()
    firstProfile.accept(profile)
    expect(useAuthState().state.value.username).toBe('alice')
    expect(useAccountProfile().state.value).toEqual(profile)
    expect(firstAuth.ready.value).toBe(true)
    expect(firstProfile.pending.value).toBe(false)

    context.application = createApplication()
    expect(useAuthState().state.value.loggedIn).toBe(false)
    expect(useAccountProfile().state.value).toBeNull()
    expect(firstAuth.state.value.username).toBe('alice')
    expect(firstProfile.state.value).toEqual(profile)
  })

  it('clears private state on account switch and refuses another account profile', () => {
    const application = createApplication()
    context.application = application
    const auth = useAuthState()
    auth.accept(owner)
    const accountProfile = useAccountProfile()
    accountProfile.accept(profile)
    const managementStore = useManagementStore(application.$pinia)
    managementStore.tags = []
    const previousRevision = accountProfile.revision.value

    auth.accept({ ...owner, username: 'bob', roles: [] })
    accountProfile.accept(profile)
    expect(accountProfile.state.value).toBeNull()
    expect(accountProfile.revision.value).toBeGreaterThan(previousRevision)
    expect(managementStore.tags).toBeNull()
    expect(auth.state.value.username).toBe('bob')
  })
})
