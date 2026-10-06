import { defineStore } from 'pinia'
import type { SessionResponse } from '../api/session'
import type { AccountProfileVo } from '../api/account'

/** 当前应用内的账号快照；不落盘，不读取 HttpOnly Cookie，不跨 SSR 请求共享。 */
export const useAccountStore = defineStore('account', {
  state: () => ({
    session: { loggedIn: false, username: null, csrfToken: null, roles: [] } as SessionResponse,
    ready: false,
    profile: null as AccountProfileVo | null,
    profileRevision: 0,
    profileError: '',
    profilePending: false,
  }),
  actions: {
    clearProfile() {
      this.profileRevision += 1
      this.profile = null
      this.profileError = ''
      this.profilePending = false
    },
    setSession(session: SessionResponse) {
      if (this.session.loggedIn !== session.loggedIn || this.session.username !== session.username) {
        this.clearProfile()
      }
      this.session = session
      this.ready = true
    },
  },
})
