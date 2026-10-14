/** 管理列表和分页控件共用，避免请求数量与页面数量不一致。 */
export const BLOG_PAGE_SIZE = 20
/** 对齐后端分页边界；首页展示数量属于当前页面需求，不是接口默认数量。 */
export const BLOG_FIRST_PAGE = 1
export const BLOG_MAX_PAGE_NUMBER = 10_000
export const BLOG_PUBLIC_PAGE_SIZE = 12
export const BLOG_HOME_ARTICLE_COUNT = 4
export const BLOG_VIEW = { PUBLIC: 'PUBLIC', MANAGE: 'MANAGE' } as const
/** 缓存只包含公开投影；账号/管理快照由认证层独立维护。 */
export const BLOG_PUBLIC_CACHE_PREFIX = 'blogs:PUBLIC:'
export const MARKDOWN_EXTENSION = '.md'
/** 后端 DocumentResource 的稳定正文引用，不能替换为带有效期的 OSS URL 后持久化。 */
export const DOCUMENT_RESOURCE_REFERENCE_PREFIX = 'resource://'
/** 对齐 AssetUsage.IMAGE 当前允许的类型；真实格式和大小由统一 Asset 服务校验。 */
export const BLOG_IMAGE_ACCEPT = 'image/png,image/jpeg'
export const DOCUMENT_RESOURCE_STATUS_LABELS = {
  READY: '已就绪',
  INVALID: '引用无效或缺少文件',
  FETCH_FAILED: '资源保存失败',
} as const

/** 与后端 BlogPolicy 对齐；这里只改善输入体验，最终校验仍由后端负责。 */
export const BLOG_INPUT_LIMITS = {
  title: 200,
  slug: 160,
  summary: 2000,
} as const

/** 给浏览器时间接管下载，再释放临时地址；不是 OSS 签名有效期。 */
export const BLOG_DOWNLOAD_URL_RELEASE_DELAY_MS = 30_000
