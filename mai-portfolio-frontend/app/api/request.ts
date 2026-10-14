import type { ApiResponse } from './types'

const SUCCESS_CODE = 'OK'

/** 保留稳定状态和业务码供鉴权处理，不向界面暴露原始 FetchError。 */
export class ApiRequestError extends Error {
  constructor(
    message: string,
    public readonly status: number | null,
    public readonly code: string | null,
  ) {
    super(message)
    this.name = 'ApiRequestError'
  }
}

function responseCode(value: unknown): string | null {
  return typeof value === 'object' &&
    value !== null &&
    'code' in value &&
    typeof value.code === 'string' &&
    'details' in value &&
    Array.isArray(value.details)
    ? value.code
    : null
}

/** 只展示服务端统一响应中的业务提示，网络错误不向界面泄露请求地址等细节。 */
function responseMessage(value: unknown, fallbackMessage: string): string {
  if (
    typeof value === 'object' &&
    value !== null &&
    'code' in value &&
    typeof value.code === 'string' &&
    'message' in value &&
    typeof value.message === 'string' &&
    value.message.trim() &&
    'details' in value &&
    Array.isArray(value.details)
  ) {
    return value.message
  }
  return fallbackMessage
}

/** Nuxt 内置请求入口：同时处理非 2xx 的 FetchError 和统一响应中的业务失败。 */
export async function requestApi<T>(
  path: string,
  options: Parameters<typeof $fetch>[1],
  fallbackMessage: string,
): Promise<ApiResponse<T>> {
  let response: ApiResponse<T>
  try {
    // 登录等写操作不能因客户端自动重试重复执行；需要重试时由用户明确发起。
    response = await $fetch<ApiResponse<T>>(path, { ...options, retry: 0 })
  } catch (error: unknown) {
    const payload =
      typeof error === 'object' && error !== null && 'data' in error
        ? error.data
        : undefined
    const status =
      typeof error === 'object' &&
      error !== null &&
      'statusCode' in error &&
      typeof error.statusCode === 'number'
        ? error.statusCode
        : null
    throw new ApiRequestError(
      responseMessage(payload, fallbackMessage),
      status,
      responseCode(payload),
    )
  }
  if (response.code !== SUCCESS_CODE) {
    throw new ApiRequestError(
      responseMessage(response, fallbackMessage),
      null,
      responseCode(response),
    )
  }
  return response
}

/** 二进制下载仍保留统一错误码，供同一个会话拦截器处理 401/403。 */
export async function requestDownload(
  path: string,
  options: Parameters<typeof $fetch>[1],
  fallback: string,
) {
  let response
  try {
    response = await $fetch.raw<Blob>(path, {
      ...options,
      responseType: 'blob',
      ignoreResponseError: true,
      retry: 0,
    })
  } catch {
    throw new ApiRequestError(fallback, null, null)
  }
  if (!response.ok) {
    // 错误响应也以 Blob 接收，先解码统一 JSON 错误，再交给认证层处理 401/403。
    let payload: unknown
    try {
      payload = JSON.parse(await response._data!.text())
    } catch {
      payload = undefined
    }
    throw new ApiRequestError(
      responseMessage(payload, fallback),
      response.status,
      responseCode(payload),
    )
  }
  if (!response._data)
    throw new ApiRequestError(fallback, response.status, null)
  // 当前后端文件名为 ASCII；优先使用服务端命名，缺少响应头时才按容器类型兜底。
  const disposition = response.headers.get('content-disposition') || ''
  const filename =
    /filename="?([^";]+)"?/.exec(disposition)?.[1] ||
    (response.headers.get('content-type')?.includes('zip')
      ? 'article.zip'
      : 'article.md')
  return { blob: response._data, filename }
}
