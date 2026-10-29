import type { SessionResponse } from '../../api/session'
import type { AccountProfileVo } from '../../api/account'
import { assetUrlsFresh } from '../assets/urls'

export const ACCOUNT_SNAPSHOT_KEY = 'portfolio:account:v1'
interface AccountSnapshot {
  session: SessionResponse
  profile: AccountProfileVo | null
}
interface SnapshotState extends AccountSnapshot {
  ready: boolean
}
type SnapshotStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
const isNullableString = (storedSnapshot: unknown): storedSnapshot is string | null =>
  storedSnapshot === null || typeof storedSnapshot === 'string'

/** 浏览器快照只恢复展示状态，不能替代服务端 Cookie、账号状态或角色校验。 */
export function readAccountSnapshot(storage: SnapshotStorage): AccountSnapshot | null {
  try {
    // 1. 读取展示快照，再核对版本、会话字段和账号标识。
    const snapshotJson = storage.getItem(ACCOUNT_SNAPSHOT_KEY)
    if (!snapshotJson) {
      return null
    }
    const storedSnapshot = JSON.parse(snapshotJson)
    const snapshotSession = storedSnapshot?.session
    if (
      storedSnapshot?.version !== 1 ||
      typeof snapshotSession?.loggedIn !== 'boolean' ||
      !Array.isArray(snapshotSession.roles) ||
      !snapshotSession.roles.every((role: unknown) => typeof role === 'string') ||
      !isNullableString(snapshotSession.username) ||
      !isNullableString(snapshotSession.csrfToken) ||
      (snapshotSession.loggedIn && (!snapshotSession.username || !snapshotSession.csrfToken))
    ) {
      storage.removeItem(ACCOUNT_SNAPSHOT_KEY)
      return null
    }
    // 2. 匿名状态不保留账号信息；资料只恢复同账号且地址仍有效的字段。
    const restoredSession: SessionResponse = snapshotSession.loggedIn
      ? {
          loggedIn: true,
          username: snapshotSession.username,
          csrfToken: snapshotSession.csrfToken,
          roles: snapshotSession.roles,
        }
      : { loggedIn: false, username: null, csrfToken: null, roles: [] }
    const snapshotProfile = storedSnapshot.profile
    const isValidProfile =
      restoredSession.loggedIn &&
      snapshotProfile?.username === restoredSession.username &&
      isNullableString(snapshotProfile.nickname) &&
      isNullableString(snapshotProfile.avatarUrl) &&
      isNullableString(snapshotProfile.avatarExpiresAt) &&
      (snapshotProfile.avatarUrl === null || typeof snapshotProfile.avatarExpiresAt === 'string') &&
      assetUrlsFresh(snapshotProfile)
    return {
      session: restoredSession,
      profile: isValidProfile
        ? {
            username: snapshotProfile.username,
            nickname: snapshotProfile.nickname,
            avatarUrl: snapshotProfile.avatarUrl,
            avatarExpiresAt: snapshotProfile.avatarExpiresAt,
          }
        : null,
    }
  } catch {
    // 禁用存储、损坏 JSON 或配额不足都只能影响缓存，不影响正常登录和 API 查询。
    try {
      storage.removeItem(ACCOUNT_SNAPSHOT_KEY)
    } catch {
      /* 存储不可用。 */
    }
    return null
  }
}

/** 明确挑选字段，不持久化 pending/error、管理实体、密码或 HttpOnly 登录 Cookie。 */
export function persistAccountSnapshot(
  storage: SnapshotStorage,
  snapshotState: SnapshotState,
): void {
  // 1. 未确认的 SSR 状态不能覆盖浏览器已有快照。
  if (!snapshotState.ready) {
    return
  } // SSR 的未知状态不能覆盖浏览器已保存的快照。
  try {
    // 2. 只序列化可恢复的会话和资料字段，不保存操作状态或管理实体。
    const { loggedIn, username, csrfToken, roles } = snapshotState.session
    const snapshotProfile =
      loggedIn && snapshotState.profile?.username === username
        ? {
            username: snapshotState.profile.username,
            nickname: snapshotState.profile.nickname,
            avatarUrl: snapshotState.profile.avatarUrl,
            avatarExpiresAt: snapshotState.profile.avatarExpiresAt,
          }
        : null
    const snapshotJson = JSON.stringify({
      version: 1,
      session: { loggedIn, username, csrfToken, roles },
      profile: snapshotProfile,
    })
    // 3. 内容未变化时不写存储；存储故障不影响当前页面状态。
    if (storage.getItem(ACCOUNT_SNAPSHOT_KEY) !== snapshotJson) {
      storage.setItem(ACCOUNT_SNAPSHOT_KEY, snapshotJson)
    }
  } catch {
    /* 当前页面仍使用 Pinia；下一次刷新按无缓存恢复。 */
  }
}
