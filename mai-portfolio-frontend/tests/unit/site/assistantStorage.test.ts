import { describe, expect, it } from 'vitest'
import {
  ANONYMOUS_CONVERSATION_KEY,
  rememberAnonymousConversation,
  restoreAnonymousConversation,
} from '../../../app/utils/site/assistantStorage'

function storage(initial: Record<string, string> = {}): Storage {
  const values = new Map(Object.entries(initial))
  return {
    get length() {
      return values.size
    },
    key: (index) => [...values.keys()][index] ?? null,
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => {
      values.set(key, value)
    },
    removeItem: (key) => {
      values.delete(key)
    },
    clear: () => values.clear(),
  }
}

describe('匿名会话浏览器存储', () => {
  it('升级只留下旧会话编号，退出问答正文与账号缓存', () => {
    const saved = storage({
      [ANONYMOUS_CONVERSATION_KEY]: JSON.stringify({
        id: '312',
        turns: [{ question: 'private', answer: 'private' }],
        pending: { text: 'private' },
      }),
      'portfolio-agent:account:alice': '{"turns":["private"]}',
      'portfolio-agent:account:bob': '{"turns":["private"]}',
      'portfolio-theme': 'dark',
    })
    expect(restoreAnonymousConversation(saved)).toBe('312')
    expect(saved.getItem(ANONYMOUS_CONVERSATION_KEY)).toBe('312')
    expect(saved.length).toBe(2)
    expect(saved.getItem('portfolio-theme')).toBe('dark')
  })

  it('可重复读取已迁移编号，保留大整数精度', () => {
    const saved = storage()
    rememberAnonymousConversation('9223372036854775807', saved)
    expect(restoreAnonymousConversation(saved)).toBe('9223372036854775807')
    expect(restoreAnonymousConversation(saved)).toBe('9223372036854775807')
    rememberAnonymousConversation(undefined, saved)
    expect(saved.getItem(ANONYMOUS_CONVERSATION_KEY)).toBeNull()
  })

  it.each(['{"turns":[]}', '{broken', '[]', 'hello/world', '"312"'])(
    '移除不合法旧值 %s',
    (value) => {
      const saved = storage({ [ANONYMOUS_CONVERSATION_KEY]: value })
      expect(restoreAnonymousConversation(saved)).toBeUndefined()
      expect(saved.getItem(ANONYMOUS_CONVERSATION_KEY)).toBeNull()
    },
  )
})
