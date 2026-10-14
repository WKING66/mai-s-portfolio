import {
  AUTH_ACCESS,
  AUTH_ERROR_CODES,
  AUTH_MESSAGES,
  AUTH_PATHS,
  CSRF_HEADER,
  type AuthAccess,
} from '../constants/auth'
import { LOGIN_MESSAGES } from '../constants/messages'
import { accessRedirect, hasAccess } from './permissions'
import { ApiRequestError, requestApi, requestDownload } from './request'
import { getSession, type SessionResponse } from './session'

type RequestOptions = NonNullable<Parameters<typeof $fetch>[1]>

interface AuthContext {
  session: () => SessionResponse
  ready: () => boolean
  invalidateSession: () => void
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
  let sessionRevision = 0
  let pendingRefresh: Promise<boolean> | null = null

  function accept(session: SessionResponse) {
    sessionRevision += 1
    pendingRefresh = null
    context.setSession({ ...session, roles: Array.isArray(session.roles) ? session.roles : [] })
  }

  function clear() {
    accept(anonymousSession())
  }

  function restore(session: SessionResponse) {
    const normalizedSession = {
      ...session,
      roles: Array.isArray(session.roles) ? session.roles : [],
    }
    const currentSession = context.session()
    // 同一账号的令牌刷新不是切换账号，不能误丢弃其他正常的在途操作。
    if (
      currentSession.loggedIn !== normalizedSession.loggedIn ||
      currentSession.username !== normalizedSession.username ||
      JSON.stringify([...currentSession.roles].sort()) !==
        JSON.stringify([...normalizedSession.roles].sort())
    ) {
      sessionRevision += 1
    }
    context.setSession(normalizedSession)
  }

  /** 共享同一在途查询；返回是否实际恢复了本世代，旧查询不能影响新登录或注销。 */
  function refresh(): Promise<boolean> {
    // 1. 同一应用复用在途查询，记录开始时的世代，隔离注销和账号切换。
    if (pendingRefresh) {
      return pendingRefresh
    }
    const startedRevision = sessionRevision
    const sessionRequest = getSession()
      .then((session) => {
        // 2. 仅当前世代可以恢复会话；旧查询不能覆盖新登录。
        if (sessionRevision !== startedRevision) {
          return false
        }
        restore(session)
        return true
      })
      .catch((error) => {
        // 3. 当前查询失败才清理快照；网络故障不等同于服务端确认匿名。
        // 旧查询失败也必须显式标为过期，否则等待它的 403 分支仍会导航新账户。
        if (sessionRevision !== startedRevision) {
          throw new ApiRequestError(
            AUTH_MESSAGES.sessionChanged,
            null,
            AUTH_ERROR_CODES.sessionChanged,
          )
        }
        clear()
        // 网络/服务故障不能持久化为“服务端确认匿名”，下次初始化仍需重试真实会话。
        if (!(error instanceof ApiRequestError && (error.status === 401 || error.status === 403))) {
          context.invalidateSession()
        }
        throw error
      })
      .finally(() => {
        // 4. 只释放本次查询，不能把更新的在途请求清空。
        if (pendingRefresh === sessionRequest) {
          pendingRefresh = null
        }
      })
    pendingRefresh = sessionRequest
    return sessionRequest
  }

  function canAccess(access: AuthAccess): boolean {
    return hasAccess(context.session(), context.ready(), access)
  }

  /** 启动时恢复一次；登录已提供完整快照，普通切页不得强制查会话。 */
  function ensureSession(): Promise<boolean> {
    return context.ready() ? Promise.resolve(true) : refresh()
  }

  async function redirect(access: AuthAccess) {
    const target = accessRedirect(context.session(), context.ready(), access, context.currentPath())
    if (target) {
      await context.navigate(target)
    }
  }

  /** 写请求统一注入最新 CSRF；任何失败都不自动重放，避免重复提交。 */
  async function execute<T>(
    path: string,
    options: RequestOptions,
    access: AuthAccess,
    operation: (options: RequestOptions) => Promise<T>,
  ): Promise<T> {
    // 1. 校验 API 范围与访问策略，SSR 不发送依赖客户端管理快照的请求。
    if (!path.startsWith(AUTH_PATHS.apiPrefix)) {
      throw new ApiRequestError(AUTH_MESSAGES.invalidRequest, null, AUTH_ERROR_CODES.invalidRequest)
    }
    if (!context.isClient() || !canAccess(access)) {
      if (context.isClient()) {
        await redirect(access)
      }
      throw new ApiRequestError(AUTH_MESSAGES.forbidden, 403, AUTH_ERROR_CODES.clientForbidden)
    }
    // 2. 绑定会话世代，写请求注入当前 CSRF；失败不自动重放。
    const startedRevision = sessionRevision
    const method = (options.method || 'GET').toUpperCase()
    const headers = new Headers(options.headers)
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      headers.set(CSRF_HEADER, context.session().csrfToken || '')
    }
    try {
      const response = await operation({ ...options, credentials: 'same-origin', headers })
      // 注销、切换账号或权限刷新后的旧成功响应，不允许交给业务组件回填。
      if (sessionRevision !== startedRevision) {
        throw new ApiRequestError(
          AUTH_MESSAGES.sessionChanged,
          null,
          AUTH_ERROR_CODES.sessionChanged,
        )
      }
      return response
    } catch (error) {
      // 3. 拒绝旧账号的失败响应，再按 401/403 刷新真实会话和权限。
      // 旧请求的失败同样不能清理新账号或触发多次重定向。
      if (sessionRevision !== startedRevision || !(error instanceof ApiRequestError)) {
        throw error
      }
      if (error.status === 401) {
        clear()
        // 服务端已否定缓存快照，重新读取一次真实会话；旧请求不自动重放。
        try {
          if (!(await refresh())) {
            throw new ApiRequestError(
              AUTH_MESSAGES.sessionChanged,
              null,
              AUTH_ERROR_CODES.sessionChanged,
            )
          }
        } catch (refreshError) {
          if (
            refreshError instanceof ApiRequestError &&
            refreshError.code === AUTH_ERROR_CODES.sessionChanged
          ) {
            throw refreshError
          }
        }
        await redirect(access)
      } else if (
        error.status === 403 &&
        (error.code === AUTH_ERROR_CODES.csrfInvalid || error.code === AUTH_ERROR_CODES.forbidden)
      ) {
        try {
          if (!(await refresh())) {
            throw new ApiRequestError(
              AUTH_MESSAGES.sessionChanged,
              null,
              AUTH_ERROR_CODES.sessionChanged,
            )
          }
        } catch (refreshError) {
          if (!(
            refreshError instanceof ApiRequestError &&
            refreshError.code === AUTH_ERROR_CODES.sessionChanged
          )) {
            await redirect(access)
          }
          throw refreshError
        }
        // 角色撤销只更新权限，不把正常登录账号强制注销；受保护缓存由 setSession 清理。
        await redirect(access)
        if (canAccess(access) && error.code === AUTH_ERROR_CODES.csrfInvalid) {
          throw new ApiRequestError(AUTH_MESSAGES.csrf, error.status, error.code)
        }
      }
      throw error
    }
  }

  function request<T>(
    path: string,
    options: RequestOptions,
    access: AuthAccess,
    fallbackMessage: string,
  ): Promise<T | null> {
    return execute(
      path,
      options,
      access,
      async (preparedOptions) => (await requestApi<T>(path, preparedOptions, fallbackMessage)).data,
    )
  }
  function download(
    path: string,
    options: RequestOptions,
    access: AuthAccess,
    fallbackMessage: string,
  ) {
    return execute(path, options, access, (preparedOptions) =>
      requestDownload(path, preparedOptions, fallbackMessage),
    )
  }

  /** 注销属于通用登录能力，不要求 OWNER；同样使用统一权限及错误拦截。 */
  async function signOut() {
    if (!context.session().loggedIn) {
      clear()
      return
    }
    // 1. 先使较早的会话查询失效，但注销失败时不伪造本地成功或提前清空登录。
    sessionRevision += 1
    pendingRefresh = null
    // 2. 服务端注销成功后才清空本地会话，失败时保留现有登录状态。
    await request<null>(
      AUTH_PATHS.session,
      { method: 'DELETE' },
      AUTH_ACCESS.AUTHENTICATED,
      LOGIN_MESSAGES.logoutFailed,
    )
    clear()
  }

  // 加密等请求发送前的异步准备也需绑定此会话，避免使用切换后的账号提交旧表单。
  return {
    accept,
    clear,
    refresh,
    ensureSession,
    canAccess,
    request,
    download,
    signOut,
    sessionRevision: () => sessionRevision,
  }
}
