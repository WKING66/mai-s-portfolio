import { useNuxtApp, type NuxtApp } from '#app'
import { storeToRefs } from 'pinia'
import { useAccountStore } from '../stores/account'
import { useAssetUrlRefresh } from './useAssetUrlRefresh'
import { useAuthState } from './useAuthState'
import { useAccountApi, type AccountProfileVo } from '../api/account'
import { assetUrlsFresh } from '../utils/assetUrls'
import { ACCOUNT_MESSAGES } from '../constants/account'
import { AUTH_ACCESS } from '../constants/auth'
import { useFeedback } from './useFeedback'

const loads = new WeakMap<NuxtApp, { username: string; revision: number; promise: Promise<void> }>()

/** 页面、菜单和浏览器快照共用资料；不同 SSR 应用不共享在途查询。 */
export function useAccountProfile() {
  const auth = useAuthState()
  const api = useAccountApi()
  const feedback = useFeedback()
  const app = useNuxtApp()
  const account = useAccountStore(app.$pinia)
  const { profile: state, profileRevision: revision, profilePending: pending } = storeToRefs(account)

  function accept(profile: AccountProfileVo) {
    if (auth.canAccess(AUTH_ACCESS.AUTHENTICATED) && profile.username === auth.state.value.username) {
      revision.value += 1
      state.value = profile
    }
  }
  function clear() {
    account.clearProfile()
  }
  async function load() {
    if (!import.meta.client || !auth.canAccess(AUTH_ACCESS.AUTHENTICATED)) return
    const username = auth.state.value.username
    if (!username || (state.value?.username === username && assetUrlsFresh(state.value))) return
    const startedAt = revision.value
    const existing = loads.get(app)
    if (existing?.username === username && existing.revision === startedAt) return existing.promise
    pending.value = true
    const promise = api.getProfile().then(profile => {
      if (revision.value === startedAt && auth.state.value.username === username) accept(profile)
    }).catch(cause => {
      if (revision.value === startedAt && auth.state.value.username === username) {
        feedback.error(cause, ACCOUNT_MESSAGES.loadFailed)
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
  useAssetUrlRefresh(() => state.value, load)
  return { state, revision, pending, load, accept, clear }
}
