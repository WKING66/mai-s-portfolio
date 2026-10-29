import { onMounted, onUnmounted, watch } from 'vue'
import { assetUrlsExpiry, assetUrlsFresh } from '../utils/assets/urls'
import { ASSET_URL_REFRESH_LEAD_MS } from '../constants/assets'

/** 浏览器定时器的有符号 32 位上限，避免较远到期时间溢出为立即执行。 */
const MAX_BROWSER_TIMEOUT_MS = 2 ** 31 - 1

/** 已展示资源到期前刷新；失败不循环轰炸接口，回到页面时可再次尝试。 */
export function useAssetUrlRefresh(
  getResourceProjection: () => unknown,
  refreshResources: () => Promise<unknown>,
) {
  // 1. 只在客户端安排计时；同一组件不并发刷新，卸载后不再发起请求。
  if (!import.meta.client) {
    return
  }
  let refreshTimer: ReturnType<typeof setTimeout> | undefined
  let isRefreshing = false
  let isDisposed = false
  function cancelRefreshTimer() {
    if (refreshTimer !== undefined) {
      clearTimeout(refreshTimer)
    }
    refreshTimer = undefined
  }
  async function refreshExpiredResources() {
    if (isRefreshing || isDisposed || assetUrlsFresh(getResourceProjection())) {
      return
    }
    isRefreshing = true
    try {
      await refreshResources()
    } catch {
      /* 调用方已有请求错误状态，保留它供用户重试。 */
    } finally {
      isRefreshing = false
    }
  }
  function scheduleRefresh() {
    // 2. 替换旧计时器，按最早到期资源留出传输余量并限制浏览器计时上限。
    cancelRefreshTimer()
    const expiresAtTimestamp = assetUrlsExpiry(getResourceProjection())
    // 已过期/刷新失败不立即重新安排零延时请求。
    if (
      Number.isFinite(expiresAtTimestamp) &&
      expiresAtTimestamp > Date.now() + ASSET_URL_REFRESH_LEAD_MS
    ) {
      refreshTimer = setTimeout(
        () => {
          void refreshExpiredResources()
        },
        Math.min(
          expiresAtTimestamp - Date.now() - ASSET_URL_REFRESH_LEAD_MS,
          MAX_BROWSER_TIMEOUT_MS,
        ),
      )
    }
  }
  function refreshWhenVisible() {
    if (document.visibilityState === 'visible') {
      void refreshExpiredResources()
    }
  }
  // 3. 投影变化时重新安排；恢复可见时检查过期，但失败不立即循环重试。
  watch(getResourceProjection, scheduleRefresh, { deep: true })
  onMounted(() => {
    scheduleRefresh()
    window.addEventListener('focus', refreshWhenVisible)
    document.addEventListener('visibilitychange', refreshWhenVisible)
  })
  onUnmounted(() => {
    // 4. 标记卸载并释放计时器和事件监听，保留原有在途请求完成语义。
    isDisposed = true
    cancelRefreshTimer()
    window.removeEventListener('focus', refreshWhenVisible)
    document.removeEventListener('visibilitychange', refreshWhenVisible)
  })
}
