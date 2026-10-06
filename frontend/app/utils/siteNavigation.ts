import { SITE_NAVIGATION, type SiteSection } from '../constants/navigation'

export interface SectionPosition { id: SiteSection; top: number }

/** 管理/账户页面没有公开栏目高亮；项目栏目页仍对应“项目”。 */
export function navigationSection(path: string, hash: string): SiteSection | null {
  if (path === '/projects') return 'projects'
  if (path !== '/') return null
  return SITE_NAVIGATION.find(item => '#' + item.id === hash)?.id ?? 'about'
}

/** 以固定导航下方的阅读线判定当前段；不使用 NuxtLink 的首页前缀 active。 */
export function visibleSection(positions: SectionPosition[], readingLine: number, atBottom = false): SiteSection | null {
  if (!positions.length) return null
  if (atBottom) return positions[positions.length - 1]!.id
  let selected = positions[0]!.id
  for (const section of positions) {
    if (section.top > readingLine) break
    selected = section.id
  }
  return selected
}
