import type { InjectionKey, Ref } from 'vue'
import type { DocumentResource } from '../api/blogs'

export const BLOG_RESOURCES: InjectionKey<Ref<DocumentResource[]>> = Symbol('blog-resources')

/** API 投影同样是不可信输入；只允许 Web 地址及站内地址，拒绝脚本、data 与协议相对地址。 */
export function safeBlogUrl(value: string | null | undefined, image = false): string | undefined {
  if (!value || /[\\\u0000-\u001f]/.test(value)) return
  let decoded = value
  try { decoded = decodeURIComponent(value) } catch { /* 签名 URL 的字面值不改写。 */ }
  decoded = decoded.trim()
  if (/^(?:https?:\/\/|\/(?!\/)|#)/i.test(decoded) || !image && /^mailto:/i.test(decoded)) return value
}

/** 仅解析本次文档版本已经授权的资源，不以任意 asset ID 调接口取地址。 */
export function resolveBlogResource(uri: string | undefined, resources: DocumentResource[], image = false): string | undefined {
  if (!uri) return
  if (!/^(?:resource|asset):\/\//.test(uri)) return safeBlogUrl(uri, image)
  const resource = resources.find(item => uri.startsWith('resource://') ? item.key === uri.slice(11) : String(item.assetId) === uri.slice(8))
  if (!resource || resource.status !== 'READY' || image && resource.type !== 'IMAGE') return
  return safeBlogUrl(resource.resourceUrl, image)
}
