import { defineNuxtRouteMiddleware, navigateTo } from '#app'
import { useAuthState } from '../composables/useAuthState'

/** 后台仅在客户端权限确认后加载业务数据；SSR 不渲染管理快照。 */
export default defineNuxtRouteMiddleware(async (to) => {
  if (import.meta.server) return
  const auth = useAuthState()
  try { await auth.refresh() } catch { return navigateTo('/login?returnTo=' + encodeURIComponent(to.path)) }
  if (!auth.state.value.loggedIn) return navigateTo('/login?returnTo=' + encodeURIComponent(to.path))
  if (!auth.isOwner.value) return navigateTo('/forbidden')
})
