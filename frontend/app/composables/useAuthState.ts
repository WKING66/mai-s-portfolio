import { clearNuxtData, useNuxtApp, navigateTo, type NuxtApp } from '#app'
import { storeToRefs } from 'pinia'
import { useAccountStore } from '../stores/account'
import { useManagementStore } from '../stores/management'
import { hasAccess } from '../api/permissions'
import { createAuthInterceptor } from '../api/authInterceptor'
import { AUTH_ACCESS } from '../constants/auth'

// 以 NuxtApp 隔离在途请求和世代计数，不能用全局单例共享不同 SSR 用户的会话。
const interceptors = new WeakMap<NuxtApp, ReturnType<typeof createAuthInterceptor>>()

/** 兼容现有业务调用入口，唯一数据源为 Pinia；拦截器仍按 NuxtApp 隔离。 */
export function useAuthState() {
  const app = useNuxtApp()
  const account = useAccountStore(app.$pinia)
  const management = useManagementStore(app.$pinia)
  const { session: state, ready } = storeToRefs(account)
  let interceptor = interceptors.get(app)
  if (!interceptor) {
    interceptor = createAuthInterceptor({
      session: () => state.value,
      ready: () => ready.value,
      isClient: () => import.meta.client,
      currentPath: () => app.$router.currentRoute.value.path,
      navigate: async path => app.runWithContext(() => navigateTo(path)),
      setSession: session => {
        // 换账号/注销时同步丢弃个人资料，并让旧的头像/昵称请求不能回填新账户菜单。
        // 同一账号的角色或 CSRF 更新不清资料，避免菜单在路由切换中无故闪烁。
        const switchedAccount = state.value.loggedIn !== session.loggedIn || state.value.username !== session.username
        account.setSession(session)
        if (switchedAccount || !hasAccess(session, true, AUTH_ACCESS.OWNER)) management.clear()
        if (!hasAccess(session, true, AUTH_ACCESS.OWNER)) {
          app.runWithContext(() => clearNuxtData(key => key.startsWith('projects:MANAGE:')))
        }
      },
    })
    interceptors.set(app, interceptor)
  }
  return { state, ready, ...interceptor }
}
