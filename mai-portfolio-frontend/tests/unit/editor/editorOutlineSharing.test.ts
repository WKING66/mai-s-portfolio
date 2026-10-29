import { describe, expect, it } from 'vitest'
import { editorHeadingTree } from '../../../app/utils/editor/interaction/editorOutline'
const headings = [
  { id: 'root', text: '总章', depth: 1, position: 0 },
  { id: 'a', text: '第一节', depth: 2, position: 8 },
  { id: 'a1', text: '子节', depth: 3, position: 15 },
  { id: 'b', text: '第二节', depth: 2, position: 22 },
]
describe('编辑目录更新范围', () => {
  it('只改一处标题时复用无变化分支，旧树保持原样', () => {
    const before = editorHeadingTree(headings)
    const after = editorHeadingTree(
      headings.map((item) => (item.id === 'b' ? { ...item, text: '新标题' } : item)),
      before,
    )
    expect(after[0]?.children?.[0]).toBe(before[0]?.children?.[0])
    expect(after[0]?.children?.[1]?.text).toBe('新标题')
    expect(before[0]?.children?.[1]?.text).toBe('第二节')
  })
  it('只改变正文位置时复用整棵目录；层级变更仍形成正确的新结构', () => {
    const before = editorHeadingTree(headings)
    expect(
      editorHeadingTree(
        headings.map((item) => ({ ...item, position: item.position + 3 })),
        before,
      ),
    ).toBe(before)
    const after = editorHeadingTree(
      headings.map((item) => (item.id === 'b' ? { ...item, depth: 1 } : item)),
      before,
    )
    expect(after.map((item) => item.id)).toEqual(['root', 'b'])
    expect(after[0]?.children?.map((item) => item.id)).toEqual(['a'])
  })
})
