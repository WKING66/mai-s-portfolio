/** 路由、请求和 Auth 元素共用访问策略；角色只在权限规则中映射。 */
export const AUTH_ACCESS = {
  AUTHENTICATED: 'authenticated',
  OWNER: 'owner',
} as const

export type AuthAccess = typeof AUTH_ACCESS[keyof typeof AUTH_ACCESS]

export const AUTH_PATHS = {
  login: '/login',
  forbidden: '/forbidden',
  manageProjects: '/admin/projects',
  account: '/account',
  session: '/api/v1/auth/session',
  apiPrefix: '/api/v1/',
} as const

export const AUTH_ROLE = { OWNER: 'OWNER' } as const
export const CSRF_HEADER = 'X-CSRF-Token'

export const AUTH_ERROR_CODES = {
  forbidden: 'FORBIDDEN',
  csrfInvalid: 'CSRF_INVALID',
  clientForbidden: 'CLIENT_FORBIDDEN',
  sessionChanged: 'AUTH_SESSION_CHANGED',
  invalidRequest: 'INVALID_AUTH_REQUEST',
} as const

export const AUTH_MESSAGES = {
  forbidden: '当前账号没有访问权限。',
  verifying: '正在验证访问权限…',
  csrf: '安全令牌已更新，请重新提交本次操作。',
  sessionChanged: '账号状态已变化，请重新读取页面。',
  invalidRequest: '认证请求只能发送到本站接口。',
} as const
