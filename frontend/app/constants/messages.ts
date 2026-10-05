export const SITE_MESSAGES = {
  profileLoadFailed: '资料暂时无法加载，请稍后再试。',
  resumeUnavailable: 'Resume · 待补充',
  projectsEmpty: '项目展示正在整理中。这里之后会呈现项目背景、我的贡献和代码或演示入口。',
  blogEmpty: '文章尚未发布。准备好后，这里会展示我整理的技术笔记与实践复盘。',
  contactInvitation: '欢迎交流 Java 后端、AI 智能体与项目实践。',
} as const

export const LOGIN_MESSAGES = {
  welcome: '欢迎回来，继续探索项目与技术实践。',
  encryptedSubmission: '密码会在浏览器中加密后提交。',
  accountAccess: '登录后可使用账号对应的功能。',
  cryptoUnavailable: '当前环境无法安全加密登录密码；请使用本机地址或 HTTPS。',
  publicKeyMissing: '尚未配置登录公钥，请联系站长。',
  passwordTooLong: '密码的 UTF-8 编码不能超过 190 字节。',
  loginFailed: '登录失败，请稍后重试。',
  sessionFailed: '会话状态暂时无法获取，请稍后重试。',
  logoutFailed: '注销失败，请稍后重试。',
} as const
