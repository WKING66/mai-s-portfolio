import { defineNuxtRouteMiddleware, navigateTo, useNuxtApp } from '#app'
import { useAuthState } from '../composables/useAuthState'
import { accessRedirect } from '../api/permissions'

/** 首次恢复会话后切页只读取 Pinia；最终权限校验属于服务端，错误由请求拦截器统一处理。 */
export default defineNuxtRouteMiddleware(async (to) => {
  if (import.meta.server || !to.meta.auth) return
  const app = useNuxtApp()
  const auth = useAuthState()
  async function checkAccess() {
    try { await auth.ensureSession() } catch {
      // 查询失败已由会话层清理权限；守卫不能放行未经确认的管理内容。
    }
    return accessRedirect(auth.state.value, auth.ready.value, to.meta.auth!, to.path)
  }
  // SSR 输出的是 Auth 的未确认占位树。首轮守卫若先改为登录路由，Nuxt 会用
  // 登录页水合管理页 DOM；只在本次占位树水合完成后跳转，不提前挂载受保护内容。
  if (app.isHydrating && app.payload.serverRendered) {
    app.hooks.hookOnce('app:mounted', async () => {
      if (app.$router.currentRoute.value.fullPath !== to.fullPath) return
      const destination = await checkAccess()
      if (destination && app.$router.currentRoute.value.fullPath === to.fullPath) {
        await app.runWithContext(() => navigateTo(destination))
      }
    })
    return
  }
  const destination = await checkAccess()
  if (destination) return navigateTo(destination)
})
