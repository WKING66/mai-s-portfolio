import { describe, expect, it } from 'vitest'
import { useBlogImportSelection } from '../../../app/composables/useBlogImportSelection'
function file(name: string, path = '', content = '# Test') {
  const value = new File([content], name)
  Object.defineProperty(value, 'webkitRelativePath', { value: path })
  return value
}
describe('blog import selection', () => {
  it('两种模式切换清除全部临时状态和权利选项', () => {
    const state = useBlogImportSelection()
    state.markdown.value = file('a.md')
    state.extraFiles.value = [file('a.png')]
    state.rights.value = true
    state.changeMode('folder')
    expect(state.markdown.value).toBeNull()
    expect(state.extraFiles.value).toBeNull()
    expect(state.rights.value).toBe(false)
    expect(state.source.value).toBeNull()
    state.selectFolder([file('b.md', 'docs/b.md'), file('b.png', 'docs/b.png')])
    expect(state.resources.value.map((x) => x.path)).toEqual(['b.png'])
    state.changeMode('file')
    expect(state.folderFiles.value).toEqual([])
    expect(state.bodyPath.value).toBe('')
    expect(state.resources.value).toEqual([])
    expect(state.revision.value).toBe(2)
  })
  it('保留正文相对资源目录，多候选必须明确选择', () => {
    const state = useBlogImportSelection()
    state.changeMode('folder')
    state.selectFolder([
      file('a.md', 'bundle/docs/a.md'),
      file('b.md', 'bundle/b.md'),
      file('a.png', 'bundle/docs/images/a.png'),
      file('outside.png', 'bundle/outside.png'),
    ])
    expect(state.source.value).toBeNull()
    expect(state.selectionIssue.value).toContain('多份 Markdown')
    state.bodyPath.value = 'bundle/docs/a.md'
    expect(state.resources.value.map((x) => x.path)).toEqual(['images/a.png'])
  })
  it('验证空正文、不支持格式及同名附件', () => {
    const state = useBlogImportSelection()
    state.markdown.value = file('a.md', '', '')
    expect(state.selectionIssue.value).toContain('为空')
    state.markdown.value = file('a.txt')
    expect(state.selectionIssue.value).toContain('.md')
    state.markdown.value = file('a.MD')
    state.extraFiles.value = [file('a.png'), file('a.png')]
    expect(state.selectionIssue.value).toContain('同名')
  })
  it('无正文目录提示错误，取消目录选择保留现有有效来源', () => {
    const state = useBlogImportSelection()
    state.changeMode('folder')
    state.selectFolder([file('a.png', 'bundle/a.png')])
    expect(state.selectionIssue.value).toContain('没有 .md')
    const body = file('a.md', 'bundle/a.md')
    state.selectFolder([body])
    state.selectFolder([])
    expect(state.source.value).toBe(body)
  })
  it('快速切换与重选同一文件不恢复旧统计', () => {
    const state = useBlogImportSelection(),
      body = file('a.md')
    for (let i = 0; i < 8; i++) {
      state.markdown.value = body
      state.changeMode('folder')
      state.changeMode('file')
      expect(state.source.value).toBeNull()
      expect(state.resources.value).toEqual([])
    }
    state.markdown.value = body
    expect(state.source.value).toBe(body)
    const revision = state.revision.value
    state.changeMode('invalid')
    state.changeMode('file')
    expect(state.revision.value).toBe(revision)
    expect(state.source.value).toBe(body)
  })
})
