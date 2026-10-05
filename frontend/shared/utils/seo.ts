export const SEO_SITE_NAME = '阿霾 · 个人作品集'
export const SEO_NOINDEX = 'noindex,nofollow'
export const SEO_INDEX = 'index,follow'
export const SEO_SITE_URL_UNCONFIGURED = 'Site URL is not configured'

const SITE_ORIGIN_ERROR = 'NUXT_PUBLIC_SITE_URL must be an HTTP(S) origin without credentials, path, query or fragment'
const CANONICAL_PATH_ERROR = 'Canonical path must be a local absolute path'
const PROJECT_LIST_NAME = '项目作品'
const PUBLIC_SEO_PATHS = ['/', '/projects'] as const

/** 正式域名由部署配置提供，不信任请求 Host，也不把本地地址当公开地址。 */
export function getSiteOrigin(siteUrl: string): string {
  const value = siteUrl.trim()
  if (!value) return ''
  let url: URL
  try {
    url = new URL(value)
  } catch {
    // URL 构造异常可能带回原值，统一提示避免配置凭据进入日志或响应。
    throw new Error(SITE_ORIGIN_ERROR)
  }
  if (!/^https?:\/\//i.test(value) || !['http:', 'https:'].includes(url.protocol)
    || value.includes('@') || url.username || url.password || url.pathname !== '/'
    || url.href.includes('?') || url.href.includes('#')) {
    throw new Error(SITE_ORIGIN_ERROR)
  }
  return url.origin
}

export function getCanonicalUrl(siteOrigin: string, path: string): string | undefined {
  if (!siteOrigin) return undefined
  if (!path.startsWith('/') || path.startsWith('//') || path.includes('\\')) {
    throw new Error(CANONICAL_PATH_ERROR)
  }
  const url = new URL(path, siteOrigin)
  // URL 解析会去掉控制字符，必须再校验结果，防止路径变为外站地址。
  if (url.origin !== siteOrigin) throw new Error(CANONICAL_PATH_ERROR)
  return url.href
}

/** 分页内容不同，每页规范到自身；第一页不保留冗余 page=1 参数。 */
export function getProjectCanonicalPath(page: number): string {
  return page > 1 ? '/projects?page=' + page : '/projects'
}

export function createProfileStructuredData(siteOrigin: string, profile: {
  displayName: string; headline: string; intro: string; githubUrl: string | null
} | null) {
  if (!siteOrigin || !profile) return null
  const url = getCanonicalUrl(siteOrigin, '/')
  return {
    '@context': 'https://schema.org',
    '@type': 'ProfilePage',
    url,
    name: profile.displayName + ' · ' + profile.headline,
    description: profile.intro,
    mainEntity: {
      '@type': 'Person',
      name: profile.displayName,
      description: profile.intro,
      url,
      ...(profile.githubUrl ? { sameAs: [profile.githubUrl] } : {}),
    },
  }
}

/** 只消费 PUBLIC 快照；分页位置按实际响应计算，不补造站内项目详情。 */
export function createProjectStructuredData(siteOrigin: string, projects: {
  view: 'PUBLIC' | 'MANAGE'; page: number; size: number
  items: { title: string; summary: string; links: { url: string }[] }[]
} | null) {
  if (!siteOrigin || !projects || projects.view !== 'PUBLIC' || !projects.items.length) return null
  return {
    '@context': 'https://schema.org',
    '@type': 'ItemList',
    name: PROJECT_LIST_NAME,
    url: getCanonicalUrl(siteOrigin, getProjectCanonicalPath(projects.page)),
    itemListElement: projects.items.map((project, index) => ({
      '@type': 'ListItem',
      position: (projects.page - 1) * projects.size + index + 1,
      item: {
        '@type': 'CreativeWork',
        name: project.title,
        description: project.summary,
        ...(project.links.length ? { url: project.links.map(link => link.url) } : {}),
      },
    })),
  }
}

function escapeXml(value: string): string {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;').replaceAll("'", '&apos;')
}

export function createSitemapXml(siteOrigin: string): string | null {
  if (!siteOrigin) return null
  // 只列现有公开栏目，不生成项目详情、未建博客或不可核实的 lastmod。
  const urls = PUBLIC_SEO_PATHS.map(path => '<url><loc>' + escapeXml(siteOrigin + path) + '</loc></url>')
  return '<?xml version="1.0" encoding="UTF-8"?>\n'
    + '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
    + urls.join('\n') + '\n</urlset>\n'
}

export function createRobotsText(siteOrigin: string): string {
  if (!siteOrigin) return 'User-agent: *\nDisallow: /\n'
  // 管理 HTML 壳允许抓取以读取 noindex；数据访问权限继续由后端核验。
  return 'User-agent: *\nAllow: /\nDisallow: /api/v1/admin/\n\nSitemap: '
    + siteOrigin + '/sitemap.xml\n'
}
