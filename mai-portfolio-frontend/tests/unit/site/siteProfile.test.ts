import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { AUTH_ACCESS } from '../../../app/constants/auth'

const { request } = vi.hoisted(() => ({ request: vi.fn() }))
vi.mock('../../../app/composables/useAuthState', () => ({ useAuthState: () => ({ request, canAccess: () => true }) }))
vi.mock('#app', () => ({ useNuxtApp: () => ({ runWithContext: (fn: () => unknown) => fn() }), useFetch: vi.fn(), clearNuxtData: vi.fn() }))

import { useSiteProfileApi, type SiteProfileResponse, type UpdateProfileRequest } from '../../../app/api/profile'
import { PROFILE_MESSAGES, PROFILE_PATHS } from '../../../app/constants/profile'

afterEach(() => request.mockReset())
beforeEach(() => setActivePinia(createPinia()))

const profile: SiteProfileResponse = {
  displayName: '阿霾', headline: 'Java 与 AI', intro: '项目复盘',
  githubUrl: null, email: null, avatarUrl: null, resumeUrl: null,
  techStack: [], avatarExpiresAt: null, resumeExpiresAt: null,
  updatedAt: '2026-10-05T01:00:00.123456',
}

describe('site profile API', () => {
  it('reads through the shared OWNER interceptor', async () => {
    request.mockResolvedValue(profile)
    expect(await useSiteProfileApi().getProfile()).toEqual(profile)
    expect(request).toHaveBeenCalledWith(PROFILE_PATHS.api, {}, AUTH_ACCESS.OWNER, PROFILE_MESSAGES.loadFailed)
  })

  it('sends a complete snapshot and preserves the exact concurrency timestamp', async () => {
    const input: UpdateProfileRequest = {
      displayName: profile.displayName, headline: profile.headline, intro: profile.intro,
      githubUrl: null, email: null, updatedAt: profile.updatedAt,
    }
    request.mockResolvedValue({ ...profile, updatedAt: '2026-10-05T01:00:01.654321' })
    const saved = await useSiteProfileApi().updateProfile(input)
    expect(saved.updatedAt).toBe('2026-10-05T01:00:01.654321')
    expect(request).toHaveBeenCalledWith(PROFILE_PATHS.api, { method: 'PATCH', body: input },
      AUTH_ACCESS.OWNER, PROFILE_MESSAGES.saveFailed)
    expect(input.updatedAt).toBe(profile.updatedAt)
  })

  it('propagates a conflict without retrying or replacing the input', async () => {
    const conflict = new Error('资料已被更新')
    request.mockRejectedValue(conflict)
    const input: UpdateProfileRequest = { ...profile, intro: '尚未保存的输入' }
    await expect(useSiteProfileApi().updateProfile(input)).rejects.toBe(conflict)
    expect(request).toHaveBeenCalledTimes(1)
    expect(input.intro).toBe('尚未保存的输入')
  })

  it('does not pretend a missing snapshot is a successful read or save', async () => {
    request.mockResolvedValue(null)
    const api = useSiteProfileApi()
    await expect(api.getProfile()).rejects.toThrow(PROFILE_MESSAGES.loadFailed)
    await expect(api.updateProfile(profile)).rejects.toThrow(PROFILE_MESSAGES.saveFailed)
  })
})
