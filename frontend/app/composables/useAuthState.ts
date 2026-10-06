import { useState, clearNuxtData, useNuxtApp, navigateTo, type NuxtApp } from '#app'
import type { SessionResponse } from '../api/session'
import { hasAccess } from '../api/permissions'
import { createAuthInterceptor } from '../api/authInterceptor'
import { AUTH_ACCESS } from '../constants/auth'
import type { AccountProfileVo } from '../api/account'

// 以 NuxtApp 隔离在途请求和世代计数，不能用全局单例共享不同 SSR 用户的会话。
const interceptors = new WeakMap<NuxtApp, ReturnType<typeof createAuthInterceptor>>()

/** 每次 SSR 请求独立的 Nuxt 状态；不持久化角色或读取 HttpOnly Cookie。 */
export function useAuthState() {
  const state = useState<SessionResponse>('auth-session', () => ({
    loggedIn: false, username: null, csrfToken: null, roles: [],
  }))
  const ready = useState<boolean>('auth-ready', () => false)
  const accountProfile = useState<AccountProfileVo | null>('account-profile', () => null)
  const accountRevision = useState<number>('account-profile-revision', () => 0)
  const accountError = useState<string>('account-profile-error', () => '')
  const accountPending = useState<boolean>('account-profile-pending', () => false)
  const app = useNuxtApp()
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
        if (state.value.loggedIn !== session.loggedIn || state.value.username !== session.username) {
          accountProfile.value = null
          accountRevision.value += 1
          accountError.value = ''
          accountPending.value = false
        }
        state.value = session
        ready.value = true
        if (!hasAccess(session, true, AUTH_ACCESS.OWNER)) {
          app.runWithContext(() => clearNuxtData(key => key.startsWith('projects:MANAGE:')))
        }
      },
    })
    interceptors.set(app, interceptor)
  }
  return { state, ready, ...interceptor }
}
