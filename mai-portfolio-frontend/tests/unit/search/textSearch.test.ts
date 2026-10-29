import { describe, expect, it } from 'vitest'
import { findTextMatches, nextTextMatch } from '../../../app/utils/search/textSearch'

describe('正文精确文本匹配', () => {
  it('字面字符、中文与 emoji 保持原字符位置，不解释正则', () => {
    expect(findTextMatches('甲.* 😀.* 甲.*', '.*')).toEqual([
      { from: 1, to: 3 },
      { from: 6, to: 8 },
      { from: 10, to: 12 },
    ])
    expect(findTextMatches('😀甲😀', '😀')).toEqual([
      { from: 0, to: 2 },
      { from: 3, to: 5 },
    ])
    expect(findTextMatches('aaaa', 'aa')).toEqual([
      { from: 0, to: 2 },
      { from: 2, to: 4 },
    ])
  })
  it('空查询和换行查询不产生匹配，大小写精确匹配', () => {
    expect(findTextMatches('ABC abc', 'abc')).toEqual([{ from: 4, to: 7 }])
    expect(findTextMatches('a\nb', 'a\nb')).toEqual([])
    expect(findTextMatches('abc', '')).toEqual([])
  })
  it('前后浏览循环，空结果无可选位置', () => {
    expect(nextTextMatch(2, 3, 1)).toBe(0)
    expect(nextTextMatch(-1, 3, -1)).toBe(2)
    expect(nextTextMatch(0, 3, -1)).toBe(2)
    expect(nextTextMatch(-1, 0, 1)).toBe(-1)
  })
})
