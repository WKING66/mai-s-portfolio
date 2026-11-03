import { SITE_NAVIGATION, type SiteSection } from '../../constants/navigation'

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
  // 首页装配可以调整栏目顺序；几何位置才是滚动高亮的依据，且不修改调用方数组。
  const orderedSections = [...sectionPositions].sort((a, b) => a.top - b.top)
  if (isAtPageBottom) {
    return orderedSections[orderedSections.length - 1]!.id
  }
  let selectedIndex = 0
  for (let index = 0; index < orderedSections.length; index++) {
    if (orderedSections[index]!.top > readingLine) {
      break
    }
    selectedIndex = index
  }
  const selected = orderedSections[selectedIndex]!
  const next = orderedSections[selectedIndex + 1]
  // 页尾两段不足一屏时，允许较近的短栏目提前接管；长栏目仍按越过阅读线判断，
  // 避免浏览项目中段时提前高亮尚未进入视野的博客。
  if (
    next &&
    selectedIndex + 1 >= orderedSections.length - 2 &&
    Math.abs(next.top - readingLine) < Math.abs(selected.top - readingLine)
  ) {
    return next.id
  }
  return selected.id
}
