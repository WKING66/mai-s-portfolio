/** 个人账号能力独立于站长公开作品集资料。 */
export const ACCOUNT_PATHS = {
  page: '/account',
  profile: '/api/v1/account/profile',
  avatar: '/api/v1/account/avatar',
  password: '/api/v1/account/password',
} as const

export const ACCOUNT_RULES = {
  nicknameMaxLength: 64,
  avatarMaxBytes: 2 * 1024 * 1024,
  avatarTypes: ['image/png', 'image/jpeg'],
} as const

export const ACCOUNT_MESSAGES = {
  loadFailed: '个人资料加载失败，请重试。',
  saveFailed: '个人资料保存失败，请重试。',
  avatarFailed: '头像上传失败，请重试。',
  passwordFailed: '密码修改失败，请重试。',
  nicknameTooLong: '昵称最多 64 个字符。',
  saved: '昵称已保存。',
  avatarSaved: '头像已更新。',
  avatarHint: 'PNG 或 JPG，最大 2 MB，长宽不超过 2048 像素。',
  avatarInvalid: '请选择不超过 2 MB 的 PNG 或 JPG 图片。',
  passwordMismatch: '两次输入的新密码不一致。',
  passwordRequired: '请填写当前密码。',
  passwordChanged: '密码已更新，请重新登录。',
  nicknameHint: '留空时显示用户名。',
} as const
