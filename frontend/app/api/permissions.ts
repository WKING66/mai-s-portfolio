import { OWNER_ROLE } from '../constants/projects'

/** 角色只决定客户端体验，不能作为服务端授权凭证。 */
export function canManageProjects(session: { loggedIn: boolean; roles: string[] }): boolean {
  return session.loggedIn && session.roles.includes(OWNER_ROLE)
}

export function safeReturnPath(value: unknown): string {
  return typeof value === 'string' && /^\/admin\/projects(?:\/[0-9]+|\/new)?$/.test(value)
    ? value : '/admin/projects'
}
