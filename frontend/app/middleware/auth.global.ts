import { defineNuxtRouteMiddleware, navigateTo } from '#app'
import { useAuthState } from '../composables/useAuthState'
import { accessRedirect } from '../api/permissions'

/** 页面声明访问策略，守卫统一恢复会话；管理内容由 Auth 阻止进入 SSR 输出。 */
export default defineNuxtRouteMiddleware(async (to) => {
  if (import.meta.server || !to.meta.auth) return
  const auth = useAuthState()
  try {
    await auth.refresh()
  } catch {
    // 查询失败已由会话层清理权限；守卫不能放行未经确认的管理内容。
    const destination = accessRedirect(auth.state.value, auth.ready.value, to.meta.auth, to.path)
    if (destination) return navigateTo(destination)
    return
  }
  const destination = accessRedirect(auth.state.value, auth.ready.value, to.meta.auth, to.path)
  if (destination) return navigateTo(destination)
})
