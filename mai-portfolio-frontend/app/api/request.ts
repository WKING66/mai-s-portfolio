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
  // 1. 使用统一请求入口且关闭自动重试，避免写操作重复执行。
  let response: ApiResponse<T>
  try {
    response = await $fetch<ApiResponse<T>>(path, { ...options, retry: 0 })
  } catch (error: unknown) {
    // 2. 提取稳定 HTTP 状态与业务码；界面只显示统一错误提示。
    const errorPayload =
      typeof error === 'object' && error !== null && 'data' in error ? error.data : undefined
    const status =
      typeof error === 'object' &&
      error !== null &&
      'statusCode' in error &&
      typeof error.statusCode === 'number'
        ? error.statusCode
        : null
    throw new ApiRequestError(
      responseMessage(errorPayload, fallbackMessage),
      status,
      responseCode(errorPayload),
    )
  }
  // 3. HTTP 成功仍须核对业务码，再交给调用方使用响应数据。
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
  fallbackMessage: string,
) {
  // 1. 按 Blob 接收下载响应；错误仍由统一认证层判断。
  let response
  try {
    response = await $fetch.raw<Blob>(path, {
      ...options,
      responseType: 'blob',
      ignoreResponseError: true,
      retry: 0,
    })
  } catch {
    throw new ApiRequestError(fallbackMessage, null, null)
  }
  // 2. 下载失败时尝试读取统一 JSON 错误，无法解码则保留兜底提示。
  if (!response.ok) {
    // 错误响应也以 Blob 接收，先解码统一 JSON 错误，再交给认证层处理 401/403。
    let errorPayload: unknown
    try {
      errorPayload = JSON.parse(await response._data!.text())
    } catch {
      errorPayload = undefined
    }
    throw new ApiRequestError(
      responseMessage(errorPayload, fallbackMessage),
      response.status,
      responseCode(errorPayload),
    )
  }
  // 3. 校验实际文件数据并读取下载文件名，不把错误 Blob 当正文保存。
  if (!response._data) {
    throw new ApiRequestError(fallbackMessage, response.status, null)
  }
  // 当前后端文件名为 ASCII；优先使用服务端命名，缺少响应头时才按容器类型兜底。
  const disposition = response.headers.get('content-disposition') || ''
  const filename =
    /filename="?([^";]+)"?/.exec(disposition)?.[1] ||
    (response.headers.get('content-type')?.includes('zip') ? 'article.zip' : 'article.md')
  return { blob: response._data, filename }
}
