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
  /** 管理端资源诊断；历史记录可能为空，公开投影不返回原因。 */
  errorMessage?: string | null
  filename?: string | null
  contentType?: string | null
}
export interface BlogTag {
  id: number
  name: string
  slug: string
  kind: 'TOPIC' | 'TECH'
}
export interface BlogCard {
  id: number
  slug: string | null
  title: string
  summary: string
  status: 'DRAFT' | 'PUBLISHED'
  revision: number | null
  contentId: number
  stage: 'DRAFT' | 'PUBLISHED'
  hasDraft: boolean | null
  hasPublished: boolean
  publishedAt: string | null
}
/** 管理投影为草稿 Markdown，公开投影为已发布 Markdown；资源地址只作展示投影。 */
export interface Blog extends BlogCard {
  documentId: number
  resourcesReady: boolean
  tags: BlogTag[]
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
  revision?: number
  requestId: string
  tagIds: number[]
  slug: string
  title: string
  summary: string
  content: string
  allowDownload: boolean
  remoteRightsConfirmed: boolean
}
export interface BlogContentInput {
  revision: number
  requestId: string
  content: string
  remoteRightsConfirmed: boolean
}
export interface BlogPropertiesInput {
  revision: number
  requestId: string
  slug: string
  title: string
  summary: string
  tagIds: number[]
  allowDownload: boolean
}
/** path 是相对所选 Markdown 正文的资源路径，由选择界面明确提供。 */
export interface BlogImportResource {
  file: File
  path: string
}
/** 管理、导入和公开下载复用现有 Blog API；角色差异只交给统一认证入口处理。 */
export function useBlogApi() {
  const authState = useAuthState()
  const nuxtApp = useNuxtApp()
  async function request<T>(path: string, options: Parameters<typeof $fetch>[1] = {}) {
    const requestResult = await authState.request<T>(
      path,
      options,
      AUTH_ACCESS.OWNER,
      '博客操作失败，请重试。',
    )
    if (requestResult === null) {
      throw new Error('博客操作失败，请重试。')
    }
    return requestResult
  }
  function invalidate() {
    // 写入后清理当前 Nuxt 应用的公开投影；不引入双请求或旧接口兼容层。
    nuxtApp.runWithContext(() => clearNuxtData((key) => key.startsWith(BLOG_PUBLIC_CACHE_PREFIX)))
  }
  async function create(body: BlogInput) {
    const blogPost = await request<Blog>(BLOG_PATH, {
      method: 'POST',
      body,
    })
    invalidate()
    return blogPost
  }
  async function saveContent(id: number, body: BlogContentInput) {
    const post = await request<Blog>(BLOG_PATH + '/' + id, { method: 'PATCH', body })
    invalidate()
    return post
  }
  async function saveProperties(id: number, body: BlogPropertiesInput) {
    const post = await request<Blog>(BLOG_PATH + '/' + id + '/properties', {
      method: 'PATCH',
      body,
    })
    invalidate()
    return post
  }
  async function transition(
    id: number,
    action: 'publish' | 'unpublish' | 'restore' | 'draft',
    body: { revision: number; requestId: string },
  ) {
    const blogPost = await request<Blog>(BLOG_PATH + '/' + id + '/' + action, {
      method: action === 'draft' ? 'DELETE' : 'POST',
      body,
    })
    invalidate()
    return blogPost
  }
  async function importDocument(
    file: File,
    resources: BlogImportResource[],
    rights: boolean,
    requestId: string,
  ) {
    // 1. file 是正文；每个 resources part 的 filename 是相对正文目录的路径，不能只发叶子名称。
    const body = new FormData()
    body.append('file', file)
    body.append('requestId', requestId)
    body.append('remoteRightsConfirmed', String(rights))
    // 2. HTTP 层只序列化已确定的路径，不猜文件夹层级；服务端负责安全校验和资源绑定。
    resources.forEach(({ file, path }) => body.append('resources', file, path))
    return request<Blog>(BLOG_PATH + '/imports', { method: 'POST', body })
  }
  async function download(id: number, includeResources: boolean, manage = false) {
    // 1. PUBLIC 下载要求登录，MANAGE 还要求 OWNER；缓存会话只作界面快照，后端仍逐次鉴权。
    await authState.ensureSession()
    const requestResult = await authState.download(
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
    // 2. 当前浏览器先接收完整 Blob 再触发保存；后端流式输出不等于前端边接收边落盘。
    const downloadUrl = URL.createObjectURL(requestResult.blob)
    const downloadLink = document.createElement('a')
    downloadLink.href = downloadUrl
    downloadLink.download = requestResult.filename
    // 3. 将下载链接接入文档再触发，兼容不处理游离节点点击的浏览器；隐藏节点不影响布局。
    downloadLink.hidden = true
    document.body.append(downloadLink)
    try {
      downloadLink.click()
    } finally {
      downloadLink.remove()
      setTimeout(() => URL.revokeObjectURL(downloadUrl), BLOG_DOWNLOAD_URL_RELEASE_DELAY_MS)
    }
  }
  return {
    list: (page = BLOG_FIRST_PAGE) =>
      request<BlogPage>(BLOG_PATH, {
        query: { view: BLOG_VIEW.MANAGE, page, size: BLOG_PAGE_SIZE },
      }),
    get: (id: number) => request<Blog>(BLOG_PATH + '/' + id),
    create,
    saveContent,
    saveProperties,
    transition,
    tags: () => request<BlogTag[]>('/api/v1/tags'),
    importDocument,
    download,
  }
}
