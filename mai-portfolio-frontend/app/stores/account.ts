import { defineStore } from 'pinia'
import type { SessionResponse } from '../api/session'
import type { AccountProfileVo } from '../api/account'
import { ACCOUNT_SNAPSHOT_KEY, readAccountSnapshot } from '../utils/account/snapshot'

/** 账号展示快照的唯一数据源；浏览器可持久化，不跨 SSR 请求共享。 */
export const useAccountStore = defineStore('account', {
  state: () => ({
    session: { loggedIn: false, username: null, csrfToken: null, roles: [] } as SessionResponse,
    ready: false,
    profile: null as AccountProfileVo | null,
    profileRevision: 0,
    profilePending: false,
  }),
  actions: {
    forgetSnapshot() {
      this.ready = false
      try {
        if (typeof window !== 'undefined') {
          window.localStorage.removeItem(ACCOUNT_SNAPSHOT_KEY)
        }
      } catch {
        /* 存储不可用。 */
      }
    },
    restoreSnapshot() {
      // 1. SSR 或已就绪时不读取浏览器缓存，避免跨用户恢复展示状态。
      if (this.ready || typeof window === 'undefined') {
        return
      }
      try {
        // 2. 使用统一校验后的快照恢复；存储不可用则留给正常 API 查询。
        const snapshot = readAccountSnapshot(window.localStorage)
        if (snapshot) {
          this.setSession(snapshot.session)
          this.profile = snapshot.profile
        }
      } catch {
        /* 浏览器禁止存储时回退到正常查询。 */
      }
    },
    clearProfile() {
      // 递增资料世代，使较早的头像或昵称响应无法回填新账号。
      this.profileRevision += 1
      this.profile = null
      this.profilePending = false
    },
    setSession(session: SessionResponse) {
      if (
        this.session.loggedIn !== session.loggedIn ||
        this.session.username !== session.username
      ) {
        this.clearProfile()
      }
      this.session = session
      this.ready = true
    },
  },
})
