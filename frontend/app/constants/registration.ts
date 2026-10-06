export const REGISTRATION_PATHS = {
  page: '/register',
  api: '/api/v1/auth/register',
} as const

export const REGISTRATION_RULES = {
  usernameMinLength: 3,
  usernameMaxLength: 64,
  passwordMinCodePoints: 12,
} as const

export const REGISTRATION_MESSAGES = {
  welcome: '创建访客账号，继续探索项目与技术实践。',
  usernameHint: '3–64 位英文字母、数字、下划线或连字符。',
  passwordHint: '至少 12 位，支持字母、数字、下划线及英文标点。',
  invalidPasswordCharacters: '密码仅支持英文字母、数字、下划线及英文标点符号。',
  passwordTooLong: '密码过长，请缩短后重试。',
  invalidUsername: '用户名须为 3–64 位英文字母、数字、下划线或连字符。',
  passwordTooShort: '密码至少需要 12 个字符。',
  registerFailed: '注册失败，请稍后重试。',
  success: '账号已创建，请使用新账号登录。',
  accountAccess: '访客账号可登录浏览，管理功能仅向站长开放。',
  signedIn: '你已登录。如需创建新账号，请先注销当前账号。',
} as const
