// 项目统一使用 pnpm，避免安装时生成另一套依赖锁文件。
if (!process.env.npm_config_user_agent?.startsWith('pnpm/')) {
  console.error('本项目统一使用 pnpm，请运行 pnpm install。')
  process.exitCode = 1
}
