import { useAuthState } from '../composables/useAuthState'
import { AUTH_ACCESS } from '../constants/auth'

export type AssetUsage = 'AVATAR' | 'IMAGE' | 'ATTACHMENT'
export interface UploadedAsset {
  id: number; resourceUrl: string; expiresAt: string; contentType: string; byteSize: number; usage: AssetUsage
}

/** 所有用途共享上传入口；业务绑定由账号、项目等业务 API 承担。 */
export function useAssetApi() {
  const auth = useAuthState()
  async function upload(file: File, usage: AssetUsage): Promise<UploadedAsset> {
    const body = new FormData()
    body.append('file', file)
    body.append('usage', usage)
    const asset = await auth.request<UploadedAsset>('/api/v1/assets', { method: 'POST', body },
      AUTH_ACCESS.AUTHENTICATED, '文件上传失败，请重试。')
    if (!asset) throw new Error('文件上传失败，请重试。')
    return asset
  }
  async function resource(id: number): Promise<UploadedAsset> {
    const asset = await auth.request<UploadedAsset>('/api/v1/assets/' + id, {},
      AUTH_ACCESS.AUTHENTICATED, '资源地址读取失败，请重试。')
    if (!asset) throw new Error('资源地址读取失败，请重试。')
    return asset
  }
  return { upload, resource }
}
