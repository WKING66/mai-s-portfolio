import { defineNuxtRouteMiddleware, navigateTo } from '#app'
import { useAuthState } from '../composables/useAuthState'
import { accessRedirect } from '../api/permissions'

/** 首次恢复会话后切页只读取 Pinia；最终权限校验属于服务端，错误由请求拦截器统一处理。 */
export default defineNuxtRouteMiddleware(async (to) => {
  if (import.meta.server || !to.meta.auth) return
  const auth = useAuthState()
  try {
    await auth.ensureSession()
  } catch {
    // 查询失败已由会话层清理权限；守卫不能放行未经确认的管理内容。
    const destination = accessRedirect(auth.state.value, auth.ready.value, to.meta.auth, to.path)
    if (destination) return navigateTo(destination)
    return
  }
  const destination = accessRedirect(auth.state.value, auth.ready.value, to.meta.auth, to.path)
  if (destination) return navigateTo(destination)
})
