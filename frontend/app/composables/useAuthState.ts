import { computed } from 'vue'
import { useState, clearNuxtData } from '#app'
import { getSession, logout, type SessionResponse } from '../api/session'
import { canManageProjects } from '../api/permissions'

/** 每次 SSR 请求独立的 Nuxt 状态；不持久化角色或读取 HttpOnly Cookie。 */
export function useAuthState() {
  const state = useState<SessionResponse>('auth-session', () => ({
    loggedIn: false, username: null, csrfToken: null, roles: [],
  }))
  const ready = useState<boolean>('auth-ready', () => false)
  const isOwner = computed(() => ready.value && canManageProjects(state.value))

  function clear() {
    state.value = { loggedIn: false, username: null, csrfToken: null, roles: [] }
    ready.value = true
    clearNuxtData(key => key.startsWith('projects:MANAGE:'))
  }
  function accept(session: SessionResponse) {
    state.value = { ...session, roles: Array.isArray(session.roles) ? session.roles : [] }
    ready.value = true
    if (!canManageProjects(state.value)) clearNuxtData(key => key.startsWith('projects:MANAGE:'))
  }
  async function refresh() {
    ready.value = false
    try { accept(await getSession()) } catch (error) { clear(); throw error }
  }
  async function signOut() {
    if (!state.value.csrfToken) { clear(); return }
    await logout(state.value.csrfToken)
    clear()
  }
  return { state, ready, isOwner, clear, accept, refresh, signOut }
}
