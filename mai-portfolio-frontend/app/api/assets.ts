import { useAuthState } from '../composables/useAuthState'
import { AUTH_ACCESS } from '../constants/auth'

export type AssetUsage = 'AVATAR' | 'IMAGE' | 'ATTACHMENT'
export interface UploadedAsset {
  id: number
  resourceUrl: string
  expiresAt: string
  contentType: string
  byteSize: number
  usage: AssetUsage
}

/** 所有用途共享上传入口；业务绑定由账号、项目等业务 API 承担。 */
export function useAssetApi() {
  const authState = useAuthState()
  async function upload(file: File, usage: AssetUsage): Promise<UploadedAsset> {
    // 1. 只提交文件与用途；资源绑定继续由业务 API 完成。
    const uploadFormData = new FormData()
    uploadFormData.append('file', file)
    uploadFormData.append('usage', usage)
    // 2. 使用统一会话入口上传并校验资源响应，保留原有权限策略。
    const asset = await authState.request<UploadedAsset>(
      '/api/v1/assets',
      { method: 'POST', body: uploadFormData },
      AUTH_ACCESS.AUTHENTICATED,
      '文件上传失败，请重试。',
    )
    if (!asset) {
      throw new Error('文件上传失败，请重试。')
    }
    return asset
  }
  async function resource(id: number): Promise<UploadedAsset> {
    const asset = await authState.request<UploadedAsset>(
      '/api/v1/assets/' + id,
      {},
      AUTH_ACCESS.AUTHENTICATED,
      '资源地址读取失败，请重试。',
    )
    if (!asset) {
      throw new Error('资源地址读取失败，请重试。')
    }
    return asset
  }
  return { upload, resource }
}
