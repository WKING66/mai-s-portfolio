import { formatBlogDate } from '../utils/blog/date'

/** SSR 与水合共享上海时区的当前年份，避免跨年时首屏文字跳变。 */
export function useBlogDate() {
  const year = useState('blog-calendar-year', () =>
    Number(
      new Intl.DateTimeFormat('en', {
        year: 'numeric',
        timeZone: 'Asia/Shanghai',
      }).format(new Date()),
    ),
  )
  return (value: string) => formatBlogDate(value, year.value)
}
