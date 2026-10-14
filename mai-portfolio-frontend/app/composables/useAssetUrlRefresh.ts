import { onMounted, onUnmounted, watch } from 'vue'
import { assetUrlsExpiry, assetUrlsFresh } from '../utils/assetUrls'
import { ASSET_URL_REFRESH_LEAD_MS } from '../constants/assets'

/** 浏览器定时器的有符号 32 位上限，避免较远到期时间溢出为立即执行。 */
const MAX_BROWSER_TIMEOUT_MS = 2 ** 31 - 1

/** 已展示资源到期前刷新；失败不循环轰炸接口，回到页面时可再次尝试。 */
export function useAssetUrlRefresh(
  source: () => unknown,
  refresh: () => Promise<unknown>,
) {
  if (!import.meta.client) return
  let timer: ReturnType<typeof setTimeout> | undefined
  let pending = false
  let disposed = false
  function cancel() {
    if (timer !== undefined) clearTimeout(timer)
    timer = undefined
  }
  async function renew() {
    if (pending || disposed || assetUrlsFresh(source())) return
    pending = true
    try {
      await refresh()
    } catch {
      /* 调用方已有请求错误状态，保留它供用户重试。 */
    } finally {
      pending = false
    }
  }
  function schedule() {
    cancel()
    const expiry = assetUrlsExpiry(source())
    // 已过期/刷新失败不立即重新安排零延时请求。
    if (
      Number.isFinite(expiry) &&
      expiry > Date.now() + ASSET_URL_REFRESH_LEAD_MS
    ) {
      timer = setTimeout(
        () => {
          void renew()
        },
        Math.min(
          expiry - Date.now() - ASSET_URL_REFRESH_LEAD_MS,
          MAX_BROWSER_TIMEOUT_MS,
        ),
      )
    }
  }
  function onVisible() {
    if (document.visibilityState === 'visible') void renew()
  }
  watch(source, schedule, { deep: true })
  onMounted(() => {
    schedule()
    window.addEventListener('focus', onVisible)
    document.addEventListener('visibilitychange', onVisible)
  })
  onUnmounted(() => {
    disposed = true
    cancel()
    window.removeEventListener('focus', onVisible)
    document.removeEventListener('visibilitychange', onVisible)
  })
}
