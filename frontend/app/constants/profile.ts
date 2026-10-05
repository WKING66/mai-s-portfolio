export const PROFILE_PATHS = {
  api: '/api/v1/admin/profile',
  page: '/admin/profile',
} as const

export const PROFILE_PUBLIC_DATA_KEY = 'profile:PUBLIC'

/** 与服务端资料 Request 的长度一致；这里只改善输入体验，不代替业务校验。 */
export const PROFILE_FIELD_LIMITS = {
  displayName: 100, headline: 200, intro: 16000, githubUrl: 2048, email: 254,
} as const

export const PROFILE_MESSAGES = {
  title: '个人资料',
  loadFailed: '个人资料读取失败，请重试。',
  saveFailed: '个人资料保存失败，请重试。',
  loading: '正在读取个人资料…',
  processing: '处理中…',
  saved: '个人资料已保存，公开首页已更新。',
  unsaved: '有尚未保存的修改。',
  clean: '当前资料与服务器快照一致。',
  discardConfirm: '重新读取会丢弃尚未保存的修改，确定继续吗？',
  contactsHint: 'GitHub 和 Email 留空并保存即可取消公开。',
  mediaHint: '本轮只编辑文字和联系方式；头像、简历上传及绑定暂不开放。',
} as const
