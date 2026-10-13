import { ASSET_URL_REFRESH_LEAD_MS } from '../constants/assets'

/** 同一个响应可以含头像、简历或多张封面，按最早过期的地址失效。 */
export function assetUrlsExpiry(value: unknown): number {
  if (value === null || typeof value !== 'object') return Infinity
  if (Array.isArray(value))
    return Math.min(Infinity, ...value.map(assetUrlsExpiry))
  let expiry = Infinity
  for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
    if (/ExpiresAt$/.test(key) || key === 'expiresAt') {
      if (item != null) {
        const parsed = typeof item === 'string' ? Date.parse(item) : NaN
        expiry = Math.min(expiry, Number.isFinite(parsed) ? parsed : 0)
      }
    } else expiry = Math.min(expiry, assetUrlsExpiry(item))
  }
  return expiry
}

/** 留出 30 秒传输余量；缓存到期后重新查询业务 API，签名本身原样使用。 */
export function assetUrlsFresh(value: unknown, now = Date.now()): boolean {
  return assetUrlsExpiry(value) > now + ASSET_URL_REFRESH_LEAD_MS
}
