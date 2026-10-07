/** 项目与前端鉴权的统一提示、分页和路径。 */
export const PROJECT_MESSAGES = {
  coverLabel: '项目封面（可选）',
  coverHint: '上传真实项目截图或封面：PNG/JPG，最大 8 MB，宽高不超过 4096 像素。上传后保存项目才会生效。',
  coverUpload: '选择并上传图片',
  coverUploading: '正在上传…',
  coverRemove: '移除封面',
  coverUploaded: '图片已上传；请保存项目完成绑定。',
  coverFailed: '图片上传失败，请重试。',
  coverInvalid: '请选择不超过 8 MB 的 PNG/JPG 图片。',
  coverBroken: '项目封面暂时无法加载',
  contribution: '我的贡献',
  outcome: '项目成果',
  loadFailed: '项目暂时无法加载，请稍后重试。',
  saveFailed: '项目保存失败，请检查输入。',
  saved: '项目已保存。',
  published: '项目已发布。',
  unpublished: '项目已下架，资料仍然保留。',
  empty: '暂时没有已发布项目。',
  manageEmpty: '还没有项目，点击“新增项目”开始。',
  publishIncomplete: '发布需填写标题、摘要、本人贡献和技术标签。',
  invalidLink: '请完整填写有效的 HTTP(S) 外部入口。',
  conflict: '项目已被其他操作修改，请重新读取；当前输入不会自动覆盖。',
  logoutFailed: '注销失败，请稍后重试。',
} as const

export const PROJECT_PAGE_SIZE = 12
/** 首页预览四个项目，超出时显示栏目入口。 */
export const HOME_PROJECT_LIMIT = 4
export const PROJECT_LIST_PATH = '/api/v1/projects'
export const ADMIN_PROJECT_PATH = '/api/v1/admin/projects'
export const PROJECT_COVER_MAX_BYTES = 8 * 1024 * 1024
/** 现有倾斜算法以中心到边缘计算，28 表示每轴边缘最大约 14°。 */
export const PROJECT_CARD_TILT_RANGE = 28
export const PROJECT_LINK_LABELS = { CODE: '代码仓库', DEMO: '在线演示', DOCUMENTATION: '项目文档', OTHER: '外部入口' } as const
