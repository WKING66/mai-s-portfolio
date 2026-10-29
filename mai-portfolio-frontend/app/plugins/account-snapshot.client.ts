import { useAccountStore } from '../stores/account'
import { useAuthState } from '../composables/useAuthState'
import {
  ACCOUNT_SNAPSHOT_KEY,
  persistAccountSnapshot,
  readAccountSnapshot,
} from '../utils/account/snapshot'

export default defineNuxtPlugin({
  name: 'account-snapshot',
  dependsOn: ['pinia', 'nuxt:router'],
  setup(nuxtApp) {
    const accountStore = useAccountStore(nuxtApp.$pinia)
    const authState = useAuthState()
    // 1. 在 Pinia 与路由就绪后订阅当前账号快照，只持久化既定的展示状态。
    try {
      const storage = window.localStorage
      const unsubscribeSnapshot = accountStore.$subscribe(
        (_mutation, state) => persistAccountSnapshot(storage, state),
        { detached: true, flush: 'post' },
      )
      const persistCurrentSnapshot = () => persistAccountSnapshot(storage, accountStore.$state)
      // 2. 跨标签页只接收对应存储键，让共享认证层使旧请求失效，再替换资料快照。
      function syncStoredAccount(event: StorageEvent) {
        if (
          event.storageArea !== storage ||
          (event.key !== null && event.key !== ACCOUNT_SNAPSHOT_KEY)
        ) {
          return
        }
        const storedSnapshot = readAccountSnapshot(storage)
        // 跨标签页登录/退出或角色更新也使旧的在途请求失效；不查询、不双写 Cookie。
        nuxtApp.runWithContext(() =>
          storedSnapshot ? authState.accept(storedSnapshot.session) : authState.clear(),
        )
        if (!storedSnapshot) {
          accountStore.ready = false
        }
        accountStore.clearProfile()
        accountStore.profile = storedSnapshot?.profile ?? null
      }
      window.addEventListener('storage', syncStoredAccount)
      window.addEventListener('pagehide', persistCurrentSnapshot)
      // 3. 卸载时解除订阅和浏览器监听；存储禁用不影响正常认证请求。
      nuxtApp.vueApp.onUnmount(() => {
        unsubscribeSnapshot()
        window.removeEventListener('storage', syncStoredAccount)
        window.removeEventListener('pagehide', persistCurrentSnapshot)
      })
    } catch {
      /* 禁用存储时保持当前请求链路可用。 */
    }
  },
})
