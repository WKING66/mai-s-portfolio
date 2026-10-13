import type { AuthAccess } from '../constants/auth'

declare module '#app' {
  interface PageMeta {
    /** 由全局路由拦截器统一处理；不声明时保持公开页面。 */
    auth?: AuthAccess
  }
}

export {}
