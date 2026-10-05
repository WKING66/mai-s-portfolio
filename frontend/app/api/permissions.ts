import { AUTH_ACCESS, AUTH_PATHS, AUTH_ROLE, type AuthAccess } from '../constants/auth'

export interface AuthSession {
  loggedIn: boolean
  roles: string[]
}

/** 未确认会话、未知策略和非精确角色一律拒绝；前端不作为服务端授权凭证。 */
export function hasAccess(session: AuthSession, ready: boolean, access: AuthAccess): boolean {
  if (!ready || !session.loggedIn) return false
  switch (access) {
    case AUTH_ACCESS.AUTHENTICATED:
      return true
    case AUTH_ACCESS.OWNER:
      return session.roles.includes(AUTH_ROLE.OWNER)
    default:
      return false
  }
}

/** 路由守卫和请求拦截使用同一跳转规则，禁止组件自行拼装权限分支。 */
export function accessRedirect(session: AuthSession, ready: boolean, access: AuthAccess, returnTo: unknown): string | null {
  if (hasAccess(session, ready, access)) return null
  if (!ready || !session.loggedIn) {
    return AUTH_PATHS.login + '?returnTo=' + encodeURIComponent(safeReturnPath(returnTo))
  }
  return AUTH_PATHS.forbidden
}

export function safeReturnPath(value: unknown): string {
  return typeof value === 'string' && /^\/admin\/projects(?:\/[0-9]+|\/new)?$/.test(value)
    ? value : AUTH_PATHS.manageProjects
}
