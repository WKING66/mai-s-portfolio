import { defineNuxtRouteMiddleware, navigateTo, useNuxtApp } from '#app'
import { useAuthState } from '../composables/useAuthState'
import { accessRedirect } from '../api/permissions'

/** 首次恢复会话后切页只读取 Pinia；最终权限校验属于服务端，错误由请求拦截器统一处理。 */
export default defineNuxtRouteMiddleware(async (to) => {
  // 1. 服务端保持受保护页面的占位结构，公开路由不发起会话恢复。
  if (import.meta.server || !to.meta.auth) {
    return
  }
  const nuxtApp = useNuxtApp()
  const authState = useAuthState()
  // 2. 会话查询失败仍按未确认权限处理，不能把网络失败当作允许访问。
  async function checkAccess() {
    try {
      await authState.ensureSession()
    } catch {
      // 查询失败已由会话层清理权限；守卫不能放行未经确认的管理内容。
    }
    return accessRedirect(authState.state.value, authState.ready.value, to.meta.auth!, to.path)
  }
  // SSR 输出的是 Auth 的未确认占位树。首轮守卫若先改为登录路由，Nuxt 会用
  // 登录页水合管理页 DOM；只在本次占位树水合完成后跳转，不提前挂载受保护内容。
  // 3. SSR 占位页面完成水合后再检查跳转，并防止迟到的结果干扰新路由。
  if (nuxtApp.isHydrating && nuxtApp.payload.serverRendered) {
    nuxtApp.hooks.hookOnce('app:mounted', async () => {
      if (nuxtApp.$router.currentRoute.value.fullPath !== to.fullPath) {
        return
      }
      const redirectPath = await checkAccess()
      if (redirectPath && nuxtApp.$router.currentRoute.value.fullPath === to.fullPath) {
        await nuxtApp.runWithContext(() => navigateTo(redirectPath))
      }
    })
    return
  }
  const redirectPath = await checkAccess()
  if (redirectPath) {
    return navigateTo(redirectPath)
  }
})
