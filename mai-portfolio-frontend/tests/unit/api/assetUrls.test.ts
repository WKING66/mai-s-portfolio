import { describe, it, expect } from 'vitest'
import { assetUrlsExpiry, assetUrlsFresh } from '../../../app/utils/assets/urls'

describe('signed URL expiry', () => {
  const now = Date.parse('2026-10-07T00:00:00Z')
  it('expires a response at its earliest resource address, including nested project pages', () => {
    const value = { avatarExpiresAt: '2026-10-07T00:10:00Z',
      items: [{ cover: { url: 'https://oss.test/file?Signature=secret', expiresAt: '2026-10-07T00:05:00Z' } }] }
    expect(assetUrlsExpiry(value)).toBe(now + 300_000)
    expect(assetUrlsFresh(value, now + 269_000)).toBe(true)
    expect(assetUrlsFresh(value, now + 270_000)).toBe(false)
    expect(value.items[0]!.cover.url).toBe('https://oss.test/file?Signature=secret')
  })
  it('handles absent resources and rejects invalid expiry instead of caching forever', () => {
    expect(assetUrlsFresh({ avatarUrl: null, avatarExpiresAt: null }, now)).toBe(true)
    expect(assetUrlsFresh({ expiresAt: 'invalid' }, now)).toBe(false)
    expect(assetUrlsFresh({ expiresAt: 42 }, now)).toBe(false)
  })
})
