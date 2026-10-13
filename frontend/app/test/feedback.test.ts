import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiRequestError } from '../api/request'
import { AUTH_ERROR_CODES } from '../constants/auth'
import { useFeedback } from '../composables/useFeedback'

const add = vi.fn()
beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('window', {})
  vi.stubGlobal('useToast', () => ({ add }))
})

describe('Nuxt UI feedback adapter', () => {
  it('delegates success, error and info notifications without confirmation actions or custom timers', () => {
    const feedback = useFeedback()
    feedback.success('已保存'); feedback.error(new Error('读取失败')); feedback.info('提示内容')
    expect(add.mock.calls.map(([toast]) => [toast.color, toast.description])).toEqual([
      ['success', '已保存'], ['error', '读取失败'], ['info', '提示内容'],
    ])
    for (const [toast] of add.mock.calls) {
      expect(toast).not.toHaveProperty('actions')
      expect(toast).not.toHaveProperty('duration') // 使用组件库默认计时。
    }
  })
  it('delivers repeated occurrences instead of retaining a custom dismissed-message cache', () => {
    useFeedback().error('失败'); useFeedback().error('失败'); useFeedback().success('已保存')
    expect(add.mock.calls.map(([toast]) => toast.description)).toEqual(['失败', '失败', '已保存'])
  })
  it('binds each caller to the library context instead of a custom global notification store', () => {
    const first = useFeedback(), otherAdd = vi.fn()
    vi.stubGlobal('useToast', () => ({ add: otherAdd }))
    const other = useFeedback()
    first.success('当前应用'); other.error('另一应用')
    expect(add).toHaveBeenCalledOnce()
    expect(otherAdd).toHaveBeenCalledOnce()
  })
  it('does not enqueue browser notifications during SSR', () => {
    vi.stubGlobal('window', undefined)
    useFeedback().error('SSR 错误')
    expect(add).not.toHaveBeenCalled()
  })
  it('uses the business fallback for unknown or empty errors without exposing raw objects', () => {
    useFeedback().error({ response: 'internal details' }, '读取失败')
    useFeedback().error(new Error(''), '保存失败')
    expect(add.mock.calls.map(([toast]) => toast.description)).toEqual(['读取失败', '保存失败'])
  })
  it('does not present an obsolete account response as the current account failure', () => {
    useFeedback().error(new ApiRequestError('旧账号', null, AUTH_ERROR_CODES.sessionChanged))
    expect(add).not.toHaveBeenCalled()
  })
})
