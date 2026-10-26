import { SITE_NAVIGATION, type SiteSection } from '../constants/navigation'

export interface SectionPosition {
  id: SiteSection
  top: number
}

/** 管理/账户页面没有公开栏目高亮；项目栏目页仍对应“项目”。 */
export function navigationSection(path: string, hash: string): SiteSection | null {
  if (path === '/projects') {
    return 'projects'
  }
  if (path !== '/') {
    return null
  }
  return SITE_NAVIGATION.find((item) => '#' + item.id === hash)?.id ?? 'about'
}

/** 以固定导航下方的阅读线判定当前段；不使用 NuxtLink 的首页前缀 active。 */
export function visibleSection(
  sectionPositions: SectionPosition[],
  readingLine: number,
  isAtPageBottom = false,
): SiteSection | null {
  if (!sectionPositions.length) {
    return null
  }
  if (isAtPageBottom) {
    return sectionPositions[sectionPositions.length - 1]!.id
  }
  let selectedSectionId = sectionPositions[0]!.id
  for (const section of sectionPositions) {
    if (section.top > readingLine) {
      break
    }
    selectedSectionId = section.id
  }
  return selectedSectionId
}
