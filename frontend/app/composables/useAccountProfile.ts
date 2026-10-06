import { useState, useNuxtApp, type NuxtApp } from '#app'
import { useAuthState } from './useAuthState'
import { useAccountApi, type AccountProfileVo } from '../api/account'
import { ACCOUNT_MESSAGES } from '../constants/account'
import { AUTH_ACCESS } from '../constants/auth'

const loads = new WeakMap<NuxtApp, { username: string; revision: number; promise: Promise<void> }>()

/** 页面和菜单共享资料；不持久化用户权限，且不同 SSR 应用不共享在途查询。 */
export function useAccountProfile() {
  const auth = useAuthState()
  const api = useAccountApi()
  const app = useNuxtApp()
  const state = useState<AccountProfileVo | null>('account-profile', () => null)
  const revision = useState<number>('account-profile-revision', () => 0)
  const error = useState<string>('account-profile-error', () => '')
  const pending = useState<boolean>('account-profile-pending', () => false)

  function accept(profile: AccountProfileVo) {
    if (auth.canAccess(AUTH_ACCESS.AUTHENTICATED) && profile.username === auth.state.value.username) {
      revision.value += 1
      state.value = profile
      error.value = ''
    }
  }
  function clear() {
    revision.value += 1
    state.value = null
    error.value = ''
    pending.value = false
  }
  async function load() {
    if (!import.meta.client || !auth.canAccess(AUTH_ACCESS.AUTHENTICATED)) return
    const username = auth.state.value.username
    if (!username || state.value?.username === username) return
    const startedAt = revision.value
    const existing = loads.get(app)
    if (existing?.username === username && existing.revision === startedAt) return existing.promise
    pending.value = true
    error.value = ''
    const promise = api.getProfile().then(profile => {
      if (revision.value === startedAt && auth.state.value.username === username) accept(profile)
    }).catch(cause => {
      if (revision.value === startedAt && auth.state.value.username === username) {
        error.value = cause instanceof Error ? cause.message : ACCOUNT_MESSAGES.loadFailed
      }
      throw cause
    }).finally(() => {
      // 新登录或更新资料后的旧请求不能修改新请求的 loading。
      if (loads.get(app)?.promise === promise) {
        loads.delete(app)
        pending.value = false
      }
    })
    loads.set(app, { username, revision: startedAt, promise })
    return promise
  }
  return { state, revision, pending, error, load, accept, clear }
}
