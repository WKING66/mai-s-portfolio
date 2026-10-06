import { describe, expect, it } from 'vitest'
import { isProjectDate } from '../utils/projectDate'

describe('project completion date', () => {
  it.each(['2026-10-06', '2024-02-29', '2000-02-29', '0001-01-01', '9999-12-31'])('accepts ISO calendar date %s', value => {
    expect(isProjectDate(value)).toBe(true)
  })
  it.each(['', '2026', '2024 – 2025', '2026-2-01', '2026-02-29', '1900-02-29',
    '2026-04-31', '2026-13-01', '2026-01-00', '0000-01-01', '2026-10-06T00:00:00Z'])('rejects invalid or legacy label %s', value => {
    expect(isProjectDate(value)).toBe(false)
  })
})
