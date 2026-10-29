import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAccountStore } from '../../../app/stores/account'
import {
  ACCOUNT_SNAPSHOT_KEY,
  persistAccountSnapshot,
  readAccountSnapshot,
} from '../../../app/utils/account/snapshot'

const owner = { loggedIn: true, username: 'owner', roles: ['OWNER'], csrfToken: 'test-csrf' }
const profile = { username: 'owner', nickname: '昵称', avatarUrl: null, avatarExpiresAt: null }
function storage() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: vi.fn((key: string, value: string) => values.set(key, value)),
    removeItem: (key: string) => values.delete(key),
  }
}
beforeEach(() => setActivePinia(createPinia()))

describe('persistent account display snapshot', () => {
  it('restores session and profile across fresh Pinia instances without sharing SSR state', () => {
    const disk = storage(),
      first = useAccountStore()
    first.setSession(owner)
    first.profile = profile
    first.profilePending = true
    persistAccountSnapshot(disk, first.$state)
    const restored = readAccountSnapshot(disk)!
    const second = useAccountStore(createPinia())
    second.setSession(restored.session)
    second.profile = restored.profile
    expect(second.session).toEqual(owner)
    expect(second.profile).toEqual(profile)
    expect(second.profilePending).toBe(false)
    expect(useAccountStore(createPinia()).ready).toBe(false)
    expect(JSON.parse(disk.getItem(ACCOUNT_SNAPSHOT_KEY)!)).not.toHaveProperty('profilePending')
  })
  it('does not overwrite a saved login with an uninitialized SSR store', () => {
    const disk = storage(),
      store = useAccountStore()
    store.setSession(owner)
    persistAccountSnapshot(disk, store.$state)
    persistAccountSnapshot(disk, useAccountStore(createPinia()).$state)
    expect(readAccountSnapshot(disk)?.session).toEqual(owner)
    expect(disk.setItem).toHaveBeenCalledTimes(1)
  })
  it('saves writes and logout, and does not save another account profile', () => {
    const disk = storage(),
      store = useAccountStore()
    store.setSession(owner)
    store.profile = profile
    persistAccountSnapshot(disk, store.$state)
    store.profile = { ...profile, nickname: '保存后昵称' }
    persistAccountSnapshot(disk, store.$state)
    expect(readAccountSnapshot(disk)?.profile?.nickname).toBe('保存后昵称')
    store.setSession({ ...owner, username: 'visitor', roles: [] })
    store.profile = profile
    persistAccountSnapshot(disk, store.$state)
    expect(readAccountSnapshot(disk)?.profile).toBeNull()
    store.setSession({ loggedIn: false, username: null, csrfToken: null, roles: [] })
    persistAccountSnapshot(disk, store.$state)
    expect(readAccountSnapshot(disk)?.session.loggedIn).toBe(false)
    expect(readAccountSnapshot(disk)?.profile).toBeNull()
  })
  it('drops an expired avatar but retains the session instead of refetching both', () => {
    const disk = storage(),
      store = useAccountStore()
    store.setSession(owner)
    store.profile = {
      ...profile,
      avatarUrl: 'https://oss.test/avatar',
      avatarExpiresAt: '2000-01-01T00:00:00Z',
    }
    persistAccountSnapshot(disk, store.$state)
    expect(readAccountSnapshot(disk)).toEqual({ session: owner, profile: null })
  })
  it.each(['{invalid', '{"version":2}', '{"version":1,"session":{"loggedIn":true}}'])(
    'discards incompatible or damaged cache %s',
    (raw) => {
      const disk = storage()
      disk.setItem(ACCOUNT_SNAPSHOT_KEY, raw)
      expect(readAccountSnapshot(disk)).toBeNull()
      expect(disk.getItem(ACCOUNT_SNAPSHOT_KEY)).toBeNull()
    },
  )
  it('keeps storage errors out of the login and data flow', () => {
    const disk = {
      getItem: () => {
        throw new Error('disabled')
      },
      setItem: () => {
        throw new Error('quota')
      },
      removeItem: () => {
        throw new Error('disabled')
      },
    }
    expect(readAccountSnapshot(disk)).toBeNull()
    const store = useAccountStore()
    store.setSession(owner)
    expect(() => persistAccountSnapshot(disk, store.$state)).not.toThrow()
    expect(store.session).toEqual(owner)
  })
})
