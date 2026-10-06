/** 管理界面只描述编辑操作，权限规则仍由统一 Auth 层解释。 */
export const ADMIN_PATHS = {
  projects: '/admin/projects',
  newProject: '/admin/projects/new',
  profile: '/admin/profile',
} as const

export const ADMIN_NAVIGATION = [
  { to: ADMIN_PATHS.projects, label: '项目管理', icon: 'i-lucide-folder-kanban' },
  { to: ADMIN_PATHS.profile, label: '站长公开资料', icon: 'i-lucide-contact' },
] as const

export const ADMIN_PROJECT_STATUS = { DRAFT: 'DRAFT', PUBLISHED: 'PUBLISHED' } as const
export const ADMIN_PROJECT_FILTERS = [
  { label: '全部状态', value: 'ALL' },
  { label: '草稿', value: ADMIN_PROJECT_STATUS.DRAFT },
  { label: '已发布', value: ADMIN_PROJECT_STATUS.PUBLISHED },
] as const
export type AdminProjectFilter = typeof ADMIN_PROJECT_FILTERS[number]['value']

export const ADMIN_PROJECT_LINK_TYPES = [
  { label: '代码仓库', value: 'CODE' },
  { label: '在线演示', value: 'DEMO' },
  { label: '文档', value: 'DOCUMENTATION' },
  { label: '其他', value: 'OTHER' },
] as const
export const ADMIN_PROJECT_DEFAULT_LINK_TYPE = ADMIN_PROJECT_LINK_TYPES[0].value

export const ADMIN_PROJECT_LIMITS = {
  slug: 160, title: 200, content: 16000, timeLabel: 100, linkLabel: 100, linkUrl: 2048,
} as const

export const ADMIN_MESSAGES = {
  projectsTitle: '项目管理',
  projectsDescription: '维护项目内容、发布状态与公开展示顺序。草稿仅对站长可见。',
  newProjectTitle: '新增项目',
  editProjectTitle: '编辑项目',
  projectEditorDescription: '先保存内容，再发布到作品集。发布后保存会更新公开卡片。',
  publicProfileTitle: '站长公开资料',
  publicProfileDescription: '这些内容展示在公开作品集首页，不是当前登录账户的个人资料。',
  loadingProjects: '正在读取项目…',
  loadingProject: '正在读取项目和技术标签…',
  filteredEmpty: '当前状态下没有项目，可切换筛选条件。',
  pageEmpty: '这一页没有项目，可返回上一页。',
  unnamedDraft: '未命名草稿',
  discardProjectConfirm: '重新读取会丢弃尚未保存的项目修改，确定继续吗？',
  projectClean: '当前内容与服务器快照一致。',
  newProjectClean: '填写项目内容后保存为草稿。',
  projectDirty: '有尚未保存的修改，请先保存再发布或下架。',
  slugLocked: '项目曾经发布，标识已固定，不能修改。',
  tagsEmpty: '暂无可选技术标签。发布前需要先配置技术标签。',
  linksEmpty: '尚未添加外部入口；没有真实链接时可以留空。',
  dateLabel: '项目完成日期（可选）',
  dateHint: '选择项目完成日期，公开卡片将展示该日期；留空不显示。',
  legacyDateHint: '现有展示时间不是单个日期，未选择新日期或清除前会原样保留：',
  clearDate: '清除日期',
} as const
