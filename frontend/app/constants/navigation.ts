/** 公开栏目与账户菜单统一使用这些文案和链接，不依赖用户名推断权限。 */
export const SITE_NAVIGATION = [
  { id: 'about', href: '/#about', label: '关于我' },
  { id: 'stack', href: '/#stack', label: '技术栈' },
  { id: 'projects', href: '/#projects', label: '项目' },
  { id: 'blog', href: '/#blog', label: '博客' },
  { id: 'contact', href: '/#contact', label: '联系' },
] as const

export type SiteSection = typeof SITE_NAVIGATION[number]['id']

export const ACCOUNT_MENU_MESSAGES = {
  account: '账户',
  verifying: '正在恢复账户…',
  login: '登录',
  personalCenter: '个人中心',
  manageProjects: '管理项目',
  logout: '退出登录',
  loggingOut: '正在退出…',
  profileFailed: '账户资料暂时无法获取，可在个人中心重试。',
  logoutFailed: '退出登录失败，请重试。',
  mobileNavigation: '打开页面导航',
} as const
