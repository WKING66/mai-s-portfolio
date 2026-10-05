/** 项目与前端鉴权的统一提示、分页和路径。 */
export const PROJECT_MESSAGES = {
  loadFailed: '项目暂时无法加载，请稍后重试。',
  saveFailed: '项目保存失败，请检查输入。',
  saved: '项目已保存。',
  published: '项目已发布。',
  unpublished: '项目已下架，资料仍然保留。',
  forbidden: '当前账号没有项目管理权限。',
  verifying: '正在验证访问权限…',
  empty: '暂时没有已发布项目。',
  manageEmpty: '还没有项目，点击“新增项目”开始。',
  csrf: '安全令牌已更新，请重新提交本次操作。',
  publishIncomplete: '发布需填写标题、摘要、本人贡献和技术标签。',
  invalidLink: '请完整填写有效的 HTTP(S) 外部入口。',
  conflict: '项目已被其他操作修改，请重新读取；当前输入不会自动覆盖。',
  logoutFailed: '注销失败，请稍后重试。',
} as const

export const PROJECT_PAGE_SIZE = 12
export const OWNER_ROLE = 'OWNER'
export const PROJECT_LIST_PATH = '/api/v1/projects'
export const ADMIN_PROJECT_PATH = '/api/v1/admin/projects'
