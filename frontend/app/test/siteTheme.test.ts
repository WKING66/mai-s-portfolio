import { runInNewContext } from 'node:vm'
import { describe, expect, it, vi } from 'vitest'
import { isSiteTheme, THEME_BOOTSTRAP_SCRIPT } from '../utils/siteTheme'

/** 执行真实 head 脚本，不等 Vue、权限或网络；模拟首屏绘制前的浏览器状态。 */
function paint(cookie = '', saved: string | null = null, darkSystem = false, blockedStorage = false, secure = false) {
  const root = { dataset: {} as Record<string, string>, classList: { toggle: vi.fn() }, style: {} as Record<string, string> }
  const document = { cookie, documentElement: root }
  runInNewContext(THEME_BOOTSTRAP_SCRIPT, {
    document,
    localStorage: { getItem: () => { if (blockedStorage) throw new Error('blocked'); return saved } },
    window: { matchMedia: () => ({ matches: darkSystem }), location: { protocol: secure ? 'https:' : 'http:' } },
  })
  return { root, document }
}

describe('pre-paint site theme', () => {
  it('restores the previous dark choice before hydration or session lookup', () => {
    const { root, document } = paint('', 'dark')
    expect(root.dataset.theme).toBe('dark')
    expect(root.style.backgroundColor).toBe('#071018')
    expect(root.style.colorScheme).toBe('dark')
    expect(root.classList.toggle).toHaveBeenCalledWith('dark', true)
    expect(document.cookie).toContain('portfolio-theme=dark; Path=/;')
  })
  it('uses the SSR preference cookie ahead of an older storage value', () => {
    expect(paint('other=value; portfolio-theme=light', 'dark', true).root.dataset.theme).toBe('light')
  })
  it('ignores invalid saved values and uses the system theme', () => {
    expect(paint('', 'invalid', true).root.dataset.theme).toBe('dark')
    expect(paint('', 'invalid', false).root.dataset.theme).toBe('light')
  })
  it('works when localStorage is unavailable', () => {
    expect(paint('portfolio-theme=dark', null, false, true).root.dataset.theme).toBe('dark')
    expect(paint('', null, true, true).root.dataset.theme).toBe('dark')
  })
  it('does not persist a system default as an explicit user choice', () => {
    expect(paint('', null, true).document.cookie).toBe('')
  })
  it('uses a secure preference cookie on HTTPS', () => {
    expect(paint('', 'dark', false, false, true).document.cookie).toContain('; Secure')
  })
  it.each(['dark', 'light'])('accepts only supported theme %s', value => expect(isSiteTheme(value)).toBe(true))
  it.each([null, '', 'system', '<script>'])('rejects invalid theme %s', value => expect(isSiteTheme(value)).toBe(false))
})
