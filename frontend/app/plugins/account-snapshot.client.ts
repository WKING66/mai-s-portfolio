import { useAccountStore } from '../stores/account'
import { useAuthState } from '../composables/useAuthState'
import { ACCOUNT_SNAPSHOT_KEY, persistAccountSnapshot, readAccountSnapshot } from '../utils/accountSnapshot'

export default defineNuxtPlugin({
  name: 'account-snapshot',
  dependsOn: ['pinia', 'nuxt:router'],
  setup(app) {
    const account = useAccountStore(app.$pinia)
    const auth = useAuthState()
    try {
      const storage = window.localStorage
      const unsubscribe = account.$subscribe((_mutation, state) => persistAccountSnapshot(storage, state),
        { detached: true, flush: 'post' })
      const save = () => persistAccountSnapshot(storage, account.$state)
      function sync(event: StorageEvent) {
        if (event.storageArea !== storage || (event.key !== null && event.key !== ACCOUNT_SNAPSHOT_KEY)) return
        const snapshot = readAccountSnapshot(storage)
        // 跨标签页登录/退出或角色更新也使旧的在途请求失效；不查询、不双写 Cookie。
        app.runWithContext(() => snapshot ? auth.accept(snapshot.session) : auth.clear())
        if (!snapshot) account.ready = false
        account.clearProfile()
        account.profile = snapshot?.profile ?? null
      }
      window.addEventListener('storage', sync)
      window.addEventListener('pagehide', save)
      app.vueApp.onUnmount(() => {
        unsubscribe()
        window.removeEventListener('storage', sync)
        window.removeEventListener('pagehide', save)
      })
    } catch { /* 禁用存储时保持当前请求链路可用。 */ }
  },
})
