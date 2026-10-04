import type { ApiResponse } from './types'

const SUCCESS_CODE = 'OK'

/** 只展示服务端统一响应中的业务提示，网络错误不向界面泄露请求地址等细节。 */
function responseMessage(value: unknown, fallbackMessage: string): string {
  if (typeof value === 'object' && value !== null
    && 'code' in value && typeof value.code === 'string'
    && 'message' in value && typeof value.message === 'string' && value.message.trim()
    && 'details' in value && Array.isArray(value.details)) {
    return value.message
  }
  return fallbackMessage
}

/** Nuxt 内置请求入口：同时处理非 2xx 的 FetchError 和统一响应中的业务失败。 */
export async function requestApi<T>(path: string, options: Parameters<typeof $fetch>[1],
  fallbackMessage: string): Promise<ApiResponse<T>> {
  let response: ApiResponse<T>
  try {
    // 登录等写操作不能因客户端自动重试重复执行；需要重试时由用户明确发起。
    response = await $fetch<ApiResponse<T>>(path, { ...options, retry: 0 })
  } catch (error: unknown) {
    const payload = typeof error === 'object' && error !== null && 'data' in error
      ? error.data : undefined
    throw new Error(responseMessage(payload, fallbackMessage))
  }
  if (response.code !== SUCCESS_CODE) {
    throw new Error(responseMessage(response, fallbackMessage))
  }
  return response
}
