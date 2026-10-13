import { clearNuxtData, useNuxtApp } from '#app'
import { useAuthState } from '../composables/useAuthState'
import { AUTH_ACCESS } from '../constants/auth'
import {
  BLOG_DOWNLOAD_URL_RELEASE_DELAY_MS,
  BLOG_PAGE_SIZE,
  BLOG_FIRST_PAGE,
  BLOG_VIEW,
  BLOG_PUBLIC_CACHE_PREFIX,
} from '../constants/blogs'
export const BLOG_PATH = '/api/v1/blogs'
/** 资源引用的展示投影；resourceUrl/expiresAt 可刷新，key/assetId 才是稳定身份。 */
export interface DocumentResource {
  key: string
  type: 'IMAGE' | 'ATTACHMENT'
  status: 'READY' | 'INVALID' | 'FETCH_FAILED'
  assetId: number | null
  resourceUrl: string | null
  expiresAt: string | null
  originalUri: string | null
}
export interface BlogCard {
  id: number
  slug: string | null
  title: string
  summary: string
  status: 'DRAFT' | 'PUBLISHED'
  version: number
  publishedAt: string | null
}
/** 管理投影为草稿 Markdown，公开投影为已发布 Markdown；资源地址只作展示投影。 */
export interface Blog extends BlogCard {
  documentId: number
  documentVersionId: number
  content: string
  previewIssue: string | null
  resources: DocumentResource[]
  allowDownload: boolean
  updatedAt: string
}
export interface BlogPage {
  view: string
  page: number
  size: number
  total: number
  items: BlogCard[]
}
export interface BlogInput {
  version?: number
  slug: string
  title: string
  summary: string
  content: string
  allowDownload: boolean
  remoteRightsConfirmed: boolean
}
/** path 是相对所选 Markdown 正文的资源路径，由选择界面明确提供。 */
export interface BlogImportResource {
  file: File
  path: string
}
/** 管理、导入和公开下载复用现有 Blog API；角色差异只交给统一认证入口处理。 */
export function useBlogApi() {
  const auth = useAuthState()
  const app = useNuxtApp()
  async function request<T>(
    path: string,
    options: Parameters<typeof $fetch>[1] = {},
  ) {
    const result = await auth.request<T>(
      path,
      options,
      AUTH_ACCESS.OWNER,
      '博客操作失败，请重试。',
    )
    if (result === null) throw new Error('博客操作失败，请重试。')
    return result
  }
  function invalidate() {
    // 写入后清理当前 Nuxt 应用的公开投影；不引入双请求或旧接口兼容层。
    app.runWithContext(() =>
      clearNuxtData((key) => key.startsWith(BLOG_PUBLIC_CACHE_PREFIX)),
    )
  }
  async function save(id: number | null, body: BlogInput) {
    const value = await request<Blog>(
      id === null ? BLOG_PATH : BLOG_PATH + '/' + id,
      {
        method: id === null ? 'POST' : 'PATCH',
        body,
      },
    )
    invalidate()
    return value
  }
  async function changeStatus(id: number, version: number, published: boolean) {
    const value = await request<Blog>(
      BLOG_PATH + '/' + id + (published ? '/publish' : '/unpublish'),
      { method: 'POST', body: { version } },
    )
    invalidate()
    return value
  }
  async function importDocument(
    file: File,
    resources: BlogImportResource[],
    rights: boolean,
  ) {
    // file 是正文；每个 resources part 的 filename 是相对正文目录的路径，不能只发叶子名称。
    const body = new FormData()
    body.append('file', file)
    body.append('remoteRightsConfirmed', String(rights))
    // HTTP 层只序列化已确定的路径，不猜文件夹层级；服务端负责安全校验和资源绑定。
    resources.forEach(({ file, path }) => body.append('resources', file, path))
    return request<Blog>(BLOG_PATH + '/imports', { method: 'POST', body })
  }
  async function download(
    id: number,
    includeResources: boolean,
    manage = false,
  ) {
    // PUBLIC 下载要求登录，MANAGE 还要求 OWNER；缓存会话只作界面快照，后端仍逐次鉴权。
    await auth.ensureSession()
    const result = await auth.download(
      BLOG_PATH + '/' + id + '/export',
      {
        query: {
          includeResources,
          view: manage ? BLOG_VIEW.MANAGE : BLOG_VIEW.PUBLIC,
        },
      },
      manage ? AUTH_ACCESS.OWNER : AUTH_ACCESS.AUTHENTICATED,
      '文章导出失败，请重试。',
    )
    // 当前浏览器先接收完整 Blob 再触发保存；后端流式输出不等于前端边接收边落盘。
    const url = URL.createObjectURL(result.blob)
    const link = document.createElement('a')
    link.href = url
    link.download = result.filename
    // 将下载链接接入文档再触发，兼容不处理游离节点点击的浏览器；隐藏节点不影响布局。
    link.hidden = true
    document.body.append(link)
    try {
      link.click()
    } finally {
      link.remove()
      setTimeout(
        () => URL.revokeObjectURL(url),
        BLOG_DOWNLOAD_URL_RELEASE_DELAY_MS,
      )
    }
  }
  return {
    list: (page = BLOG_FIRST_PAGE) =>
      request<BlogPage>(BLOG_PATH, {
        query: { view: BLOG_VIEW.MANAGE, page, size: BLOG_PAGE_SIZE },
      }),
    get: (id: number) => request<Blog>(BLOG_PATH + '/' + id),
    save,
    changeStatus,
    importDocument,
    download,
  }
}
