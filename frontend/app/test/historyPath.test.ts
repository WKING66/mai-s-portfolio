import { describe, expect, it } from 'vitest'
import { safeHistoryPath } from '../utils/historyPath'
describe('safeHistoryPath', () => {
  it('保留站内历史的查询和锚点', () => {
    expect(safeHistoryPath('/blog?page=2#articles', '/missing')).toBe('/blog?page=2#articles')
  })
  it('拒绝外部、协议相对、反斜线和控制字符', () => {
    for (const value of [undefined, 'https://other.test/', '//other.test/', '/\\other.test', '/\nother.test', 'javascript:alert(1)']) expect(safeHistoryPath(value, '/missing')).toBeNull()
  })
  it('拒绝重复异常路径并按 URL 规范处理路径', () => {
    expect(safeHistoryPath('/missing?x=1', '/missing')).toBeNull()
    expect(safeHistoryPath('/blog/../account', '/missing')).toBe('/account')
  })
})
