import { describe, expect, it } from 'vitest'
import { formatBlogDate } from '../../../app/utils/blog/date'
describe('public blog calendar dates', () => {
  it('omits this year and keeps older years', () => {
    expect(formatBlogDate('2026-11-04', 2026)).toBe('11月4日')
    expect(formatBlogDate('2025-02-03', 2026)).toBe('2025年2月3日')
  })
  it('preserves the API calendar day across timezone offsets', () => {
    expect(formatBlogDate('2026-01-01T00:01:00+08:00', 2026)).toBe('1月1日')
  })
  it('does not turn invalid dates into another day', () => {
    expect(formatBlogDate('2026-02-30', 2026)).toBe('2026-02-30')
    expect(formatBlogDate('invalid', 2026)).toBe('invalid')
  })
})
