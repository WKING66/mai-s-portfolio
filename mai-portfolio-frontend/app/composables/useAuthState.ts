import { clearNuxtData, useNuxtApp, navigateTo, type NuxtApp } from '#app'
import { storeToRefs } from 'pinia'
import { useAccountStore } from '../stores/account'
import { useManagementStore } from '../stores/management'
import { hasAccess } from '../api/permissions'
import { createAuthInterceptor } from '../api/authInterceptor'
import { AUTH_ACCESS } from '../constants/auth'

// 以 NuxtApp 隔离在途请求和世代计数，不能用全局单例共享不同 SSR 用户的会话。
const authInterceptors = new WeakMap<NuxtApp, ReturnType<typeof createAuthInterceptor>>()

/** 兼容现有业务调用入口，唯一数据源为 Pinia；拦截器仍按 NuxtApp 隔离。 */
export function useAuthState() {
  // 1. 绑定本次 Nuxt 应用及 Pinia，SSR 用户之间不共享拦截器。
  const nuxtApp = useNuxtApp()
  const accountStore = useAccountStore(nuxtApp.$pinia)
  const managementStore = useManagementStore(nuxtApp.$pinia)
  const { session: sessionSnapshot, ready } = storeToRefs(accountStore)
  let authInterceptor = authInterceptors.get(nuxtApp)
  // 2. 只创建一次拦截器；账号或角色变化时同步清理受保护缓存。
  if (!authInterceptor) {
    authInterceptor = createAuthInterceptor({
      session: () => sessionSnapshot.value,
      ready: () => ready.value,
      invalidateSession: () => accountStore.forgetSnapshot(),
      isClient: () => import.meta.client,
      currentPath: () => nuxtApp.$router.currentRoute.value.path,
      navigate: async (path) => nuxtApp.runWithContext(() => navigateTo(path)),
      setSession: (session) => {
        // 换账号/注销时同步丢弃个人资料，并让旧的头像/昵称请求不能回填新账户菜单。
        // 同一账号的角色或 CSRF 更新不清资料，避免菜单在路由切换中无故闪烁。
        const switchedAccount =
          sessionSnapshot.value.loggedIn !== session.loggedIn ||
          sessionSnapshot.value.username !== session.username
        accountStore.setSession(session)
        if (switchedAccount || !hasAccess(session, true, AUTH_ACCESS.OWNER)) {
          managementStore.clear()
        }
        if (!hasAccess(session, true, AUTH_ACCESS.OWNER)) {
          nuxtApp.runWithContext(() => clearNuxtData((key) => key.startsWith('projects:MANAGE:')))
        }
      },
    })
    authInterceptors.set(nuxtApp, authInterceptor)
  }
  function ensureSession() {
    // 3. 浏览器先恢复展示快照；真正未就绪时仍交给会话查询确认。
    if (import.meta.client && !ready.value) {
      accountStore.restoreSnapshot()
    }
    return authInterceptor!.ensureSession()
  }
  return { state: sessionSnapshot, ready, ...authInterceptor, ensureSession }
}
