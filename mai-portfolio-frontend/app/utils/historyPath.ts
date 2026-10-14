/** Vue Router 提供的前一条记录必须是站内路径，不能把浏览器历史中的外部地址变为返回入口。 */
export function safeHistoryPath(value: unknown, currentPath: string): string | null {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || /[\\\u0000-\u0020]/.test(value)) return null
  const path = new URL(value, 'https://internal.invalid')
  if (path.origin !== 'https://internal.invalid' || path.pathname === currentPath) return null
  return path.pathname + path.search + path.hash
}
