/** 按接口日期部分展示，避免时间戳时区转换导致日期跨日。 */
export function formatBlogDate(value: string, currentYear: number): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})(?:$|T)/.exec(value)
  if (!match) {
    return value
  }
  const year = Number(match[1]),
    month = Number(match[2]),
    day = Number(match[3])
  const date = new Date(Date.UTC(year, month - 1, day))
  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day
  ) {
    return value
  }
  return (year === currentYear ? '' : year + '年') + month + '月' + day + '日'
}
