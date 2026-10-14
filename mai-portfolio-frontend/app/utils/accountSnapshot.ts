import type { SessionResponse } from '../api/session'
import type { AccountProfileVo } from '../api/account'
import { assetUrlsFresh } from './assetUrls'

export const ACCOUNT_SNAPSHOT_KEY = 'portfolio:account:v1'
interface AccountSnapshot { session: SessionResponse; profile: AccountProfileVo | null }
interface SnapshotState extends AccountSnapshot { ready: boolean }
type SnapshotStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
const nullableString = (value: unknown): value is string | null => value === null || typeof value === 'string'

/** 浏览器快照只恢复展示状态，不能替代服务端 Cookie、账号状态或角色校验。 */
export function readAccountSnapshot(storage: SnapshotStorage): AccountSnapshot | null {
  try {
    const raw = storage.getItem(ACCOUNT_SNAPSHOT_KEY)
    if (!raw) return null
    const value = JSON.parse(raw)
    const session = value?.session
    if (value?.version !== 1 || typeof session?.loggedIn !== 'boolean'
      || !Array.isArray(session.roles) || !session.roles.every((role: unknown) => typeof role === 'string')
      || !nullableString(session.username) || !nullableString(session.csrfToken)
      || (session.loggedIn && (!session.username || !session.csrfToken))) {
      storage.removeItem(ACCOUNT_SNAPSHOT_KEY)
      return null
    }
    const normalized: SessionResponse = session.loggedIn
      ? { loggedIn: true, username: session.username, csrfToken: session.csrfToken, roles: session.roles }
      : { loggedIn: false, username: null, csrfToken: null, roles: [] }
    const profile = value.profile
    const validProfile = normalized.loggedIn && profile?.username === normalized.username
      && nullableString(profile.nickname) && nullableString(profile.avatarUrl)
      && nullableString(profile.avatarExpiresAt)
      && (profile.avatarUrl === null || typeof profile.avatarExpiresAt === 'string') && assetUrlsFresh(profile)
    return { session: normalized, profile: validProfile ? {
      username: profile.username, nickname: profile.nickname,
      avatarUrl: profile.avatarUrl, avatarExpiresAt: profile.avatarExpiresAt,
    } : null }
  } catch {
    // 禁用存储、损坏 JSON 或配额不足都只能影响缓存，不影响正常登录和 API 查询。
    try { storage.removeItem(ACCOUNT_SNAPSHOT_KEY) } catch { /* 存储不可用。 */ }
    return null
  }
}

/** 明确挑选字段，不持久化 pending/error、管理实体、密码或 HttpOnly 登录 Cookie。 */
export function persistAccountSnapshot(storage: SnapshotStorage, state: SnapshotState): void {
  if (!state.ready) return // SSR 的未知状态不能覆盖浏览器已保存的快照。
  try {
    const { loggedIn, username, csrfToken, roles } = state.session
    const profile = loggedIn && state.profile?.username === username ? {
      username: state.profile.username, nickname: state.profile.nickname,
      avatarUrl: state.profile.avatarUrl, avatarExpiresAt: state.profile.avatarExpiresAt,
    } : null
    const raw = JSON.stringify({ version: 1, session: { loggedIn, username, csrfToken, roles }, profile })
    if (storage.getItem(ACCOUNT_SNAPSHOT_KEY) !== raw) storage.setItem(ACCOUNT_SNAPSHOT_KEY, raw)
  } catch { /* 当前页面仍使用 Pinia；下一次刷新按无缓存恢复。 */ }
}
