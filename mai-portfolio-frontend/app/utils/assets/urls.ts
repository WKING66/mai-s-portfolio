import { ASSET_URL_REFRESH_LEAD_MS } from '../../constants/assets'

/** 同一个响应可以含头像、简历或多张封面，按最早过期的地址失效。 */
export function assetUrlsExpiry(resourceProjection: unknown): number {
  if (resourceProjection === null || typeof resourceProjection !== 'object') {
    return Infinity
  }
  if (Array.isArray(resourceProjection)) {
    return Math.min(Infinity, ...resourceProjection.map(assetUrlsExpiry))
  }
  let earliestExpiry = Infinity
  for (const [fieldName, projectedFieldValue] of Object.entries(
    resourceProjection as Record<string, unknown>,
  )) {
    if (/ExpiresAt$/.test(fieldName) || fieldName === 'expiresAt') {
      if (projectedFieldValue != null) {
        const parsedExpiry =
          typeof projectedFieldValue === 'string' ? Date.parse(projectedFieldValue) : NaN
        earliestExpiry = Math.min(earliestExpiry, Number.isFinite(parsedExpiry) ? parsedExpiry : 0)
      }
    } else {
      earliestExpiry = Math.min(earliestExpiry, assetUrlsExpiry(projectedFieldValue))
    }
  }
  return earliestExpiry
}

/** 留出 30 秒传输余量；缓存到期后重新查询业务 API，签名本身原样使用。 */
export function assetUrlsFresh(resourceProjection: unknown, now = Date.now()): boolean {
  return assetUrlsExpiry(resourceProjection) > now + ASSET_URL_REFRESH_LEAD_MS
}
