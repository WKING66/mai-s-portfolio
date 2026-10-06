import { AUTH_ACCESS, AUTH_ERROR_CODES, AUTH_MESSAGES, AUTH_PATHS, CSRF_HEADER, type AuthAccess } from '../constants/auth'
import { LOGIN_MESSAGES } from '../constants/messages'
import { accessRedirect, hasAccess } from './permissions'
import { ApiRequestError, requestApi } from './request'
import { getSession, type SessionResponse } from './session'

type RequestOptions = NonNullable<Parameters<typeof $fetch>[1]>

interface AuthContext {
  session: () => SessionResponse
  ready: () => boolean
  setSession: (session: SessionResponse) => void
  currentPath: () => string
  navigate: (path: string) => Promise<unknown>
  isClient: () => boolean
}

function anonymousSession(): SessionResponse {
  return { loggedIn: false, username: null, csrfToken: null, roles: [] }
}

/**
 * 每个 Nuxt 应用独立创建：集中处理访问、CSRF、401/403 和过期响应。
 * 公共 SSR 请求及登录凭据错误不走此拦截器，避免泄漏管理快照或登录重定向循环。
 */
export function createAuthInterceptor(context: AuthContext) {
  let revision = 0
  let refreshing: Promise<boolean> | null = null

  function accept(session: SessionResponse) {
    revision += 1
    refreshing = null
    context.setSession({ ...session, roles: Array.isArray(session.roles) ? session.roles : [] })
  }

  function clear() {
    accept(anonymousSession())
  }

  function restore(session: SessionResponse) {
    const normalized = { ...session, roles: Array.isArray(session.roles) ? session.roles : [] }
    const current = context.session()
    // 同一账号的令牌刷新不是切换账号，不能误丢弃其他正常的在途操作。
    if (current.loggedIn !== normalized.loggedIn || current.username !== normalized.username
      || JSON.stringify([...current.roles].sort()) !== JSON.stringify([...normalized.roles].sort())) {
      revision += 1
    }
    context.setSession(normalized)
  }

  /** 共享同一在途查询；返回是否实际恢复了本世代，旧查询不能影响新登录或注销。 */
  function refresh(): Promise<boolean> {
    if (refreshing) return refreshing
    const startedAt = revision
    const pending = getSession().then(session => {
      if (revision !== startedAt) return false
      restore(session)
      return true
    }).catch(error => {
      // 旧查询失败也必须显式标为过期，否则等待它的 403 分支仍会导航新账户。
      if (revision !== startedAt) {
        throw new ApiRequestError(AUTH_MESSAGES.sessionChanged, null, AUTH_ERROR_CODES.sessionChanged)
      }
      clear()
      throw error
    }).finally(() => {
      if (refreshing === pending) refreshing = null
    })
    refreshing = pending
    return pending
  }

  function canAccess(access: AuthAccess): boolean {
    return hasAccess(context.session(), context.ready(), access)
  }

  async function redirect(access: AuthAccess) {
    const target = accessRedirect(context.session(), context.ready(), access, context.currentPath())
    if (target) await context.navigate(target)
  }

  /** 写请求统一注入最新 CSRF；任何失败都不自动重放，避免重复提交。 */
  async function request<T>(path: string, options: RequestOptions, access: AuthAccess, fallbackMessage: string): Promise<T | null> {
    if (!path.startsWith(AUTH_PATHS.apiPrefix)) {
      throw new ApiRequestError(AUTH_MESSAGES.invalidRequest, null, AUTH_ERROR_CODES.invalidRequest)
    }
    if (!context.isClient() || !canAccess(access)) {
      if (context.isClient()) await redirect(access)
      throw new ApiRequestError(AUTH_MESSAGES.forbidden, 403, AUTH_ERROR_CODES.clientForbidden)
    }
    const startedAt = revision
    const method = (options.method || 'GET').toUpperCase()
    const headers = new Headers(options.headers)
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      headers.set(CSRF_HEADER, context.session().csrfToken || '')
    }
    try {
      const response = await requestApi<T>(path, { ...options, credentials: 'same-origin', headers }, fallbackMessage)
      // 注销、切换账号或权限刷新后的旧成功响应，不允许交给业务组件回填。
      if (revision !== startedAt) {
        throw new ApiRequestError(AUTH_MESSAGES.sessionChanged, null, AUTH_ERROR_CODES.sessionChanged)
      }
      return response.data
    } catch (error) {
      // 旧请求的失败同样不能清理新账号或触发多次重定向。
      if (revision !== startedAt || !(error instanceof ApiRequestError)) throw error
      if (error.status === 401) {
        clear()
        await redirect(access)
      } else if (error.status === 403) {
        try {
          if (!await refresh()) {
            throw new ApiRequestError(AUTH_MESSAGES.sessionChanged, null, AUTH_ERROR_CODES.sessionChanged)
          }
        } catch (refreshError) {
          if (!(refreshError instanceof ApiRequestError && refreshError.code === AUTH_ERROR_CODES.sessionChanged)) {
            await redirect(access)
          }
          throw refreshError
        }
        await redirect(access)
        if (canAccess(access) && error.code === AUTH_ERROR_CODES.csrfInvalid) {
          throw new ApiRequestError(AUTH_MESSAGES.csrf, error.status, error.code)
        }
      }
      throw error
    }
  }

  /** 注销属于通用登录能力，不要求 OWNER；同样使用统一权限及错误拦截。 */
  async function signOut() {
    if (!context.session().loggedIn) {
      clear()
      return
    }
    // 先使较早的会话查询失效，但注销失败时不伪造本地成功或提前清空登录。
    revision += 1
    refreshing = null
    await request<null>(AUTH_PATHS.session, { method: 'DELETE' }, AUTH_ACCESS.AUTHENTICATED,
      LOGIN_MESSAGES.logoutFailed)
    clear()
  }

  // 加密等请求发送前的异步准备也需绑定此会话，避免使用切换后的账号提交旧表单。
  return { accept, clear, refresh, canAccess, request, signOut, sessionRevision: () => revision }
}
