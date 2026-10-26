/** Vue Router 提供的前一条记录必须是站内路径，不能把浏览器历史中的外部地址变为返回入口。 */
export function safeHistoryPath(historyEntry: unknown, currentPath: string): string | null {
  // 在 URL 规范化前拒绝反斜杠及空白、控制字符，防止历史记录变为外站入口。
  if (
    typeof historyEntry !== 'string' ||
    !historyEntry.startsWith('/') ||
    historyEntry.startsWith('//') ||
    Array.from(historyEntry).some(
      (character) => character === '\\' || character.charCodeAt(0) <= 0x20,
    )
  ) {
    return null
  }
  const historyUrl = new URL(historyEntry, 'https://internal.invalid')
  if (historyUrl.origin !== 'https://internal.invalid' || historyUrl.pathname === currentPath) {
    return null
  }
  return historyUrl.pathname + historyUrl.search + historyUrl.hash
}
