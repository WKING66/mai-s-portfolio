import { useNuxtApp, type NuxtApp } from '#app'
import { storeToRefs } from 'pinia'
import { useAccountStore } from '../stores/account'
import { useAssetUrlRefresh } from './useAssetUrlRefresh'
import { useAuthState } from './useAuthState'
import { useAccountApi, type AccountProfileVo } from '../api/account'
import { assetUrlsFresh } from '../utils/assets/urls'
import { ACCOUNT_MESSAGES } from '../constants/account'
import { AUTH_ACCESS } from '../constants/auth'
import { useFeedback } from './useFeedback'

const pendingProfileLoads = new WeakMap<
  NuxtApp,
  { username: string; revision: number; promise: Promise<void> }
>()

/** 页面、菜单和浏览器快照共用资料；不同 SSR 应用不共享在途查询。 */
export function useAccountProfile() {
  const authState = useAuthState()
  const accountApi = useAccountApi()
  const feedback = useFeedback()
  const nuxtApp = useNuxtApp()
  const accountStore = useAccountStore(nuxtApp.$pinia)
  const {
    profile: accountProfile,
    profileRevision,
    profilePending: isProfileLoading,
  } = storeToRefs(accountStore)

  function accept(profile: AccountProfileVo) {
    if (
      authState.canAccess(AUTH_ACCESS.AUTHENTICATED) &&
      profile.username === authState.state.value.username
    ) {
      profileRevision.value += 1
      accountProfile.value = profile
    }
  }
  function clear() {
    accountStore.clearProfile()
  }
  async function load() {
    // 1. 仅客户端已登录账户读取资料；有效缓存不重复查询。
    if (!import.meta.client || !authState.canAccess(AUTH_ACCESS.AUTHENTICATED)) {
      return
    }
    const username = authState.state.value.username
    if (
      !username ||
      (accountProfile.value?.username === username && assetUrlsFresh(accountProfile.value))
    ) {
      return
    }
    // 2. 同一账号、同一资料世代复用查询，避免页面和菜单同时发请求。
    const startedRevision = profileRevision.value
    const existingLoad = pendingProfileLoads.get(nuxtApp)
    if (existingLoad?.username === username && existingLoad.revision === startedRevision) {
      return existingLoad.promise
    }
    isProfileLoading.value = true
    // 3. 成功和失败回填均检查世代与账号，旧请求不影响新账户。
    const profileRequest = accountApi
      .getProfile()
      .then((profile) => {
        if (
          profileRevision.value === startedRevision &&
          authState.state.value.username === username
        ) {
          accept(profile)
        }
      })
      .catch((error) => {
        if (
          profileRevision.value === startedRevision &&
          authState.state.value.username === username
        ) {
          feedback.error(error, ACCOUNT_MESSAGES.loadFailed)
        }
        throw error
      })
      .finally(() => {
        // 新登录或更新资料后的旧请求不能修改新请求的 loading。
        if (pendingProfileLoads.get(nuxtApp)?.promise === profileRequest) {
          pendingProfileLoads.delete(nuxtApp)
          isProfileLoading.value = false
        }
      })
    pendingProfileLoads.set(nuxtApp, {
      username,
      revision: startedRevision,
      promise: profileRequest,
    })
    return profileRequest
  }
  useAssetUrlRefresh(() => accountProfile.value, load)
  return {
    state: accountProfile,
    revision: profileRevision,
    pending: isProfileLoading,
    load,
    accept,
    clear,
  }
}
