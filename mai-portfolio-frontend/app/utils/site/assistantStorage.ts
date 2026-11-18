/** 浏览器只保留匿名会话编号，正式问答由服务端恢复。 */
export const ANONYMOUS_CONVERSATION_KEY = 'portfolio-agent:visitor'

export function restoreAnonymousConversation(storage?: Storage): string | undefined {
  try {
    const browserStorage = storage ?? localStorage
    // 清理旧版账号问答缓存，账号历史统一从服务端读取。
    const oldKeys = Array.from({ length: browserStorage.length }, (_, index) =>
      browserStorage.key(index),
    )
    for (const key of oldKeys) {
      if (key?.startsWith('portfolio-agent:account:')) {
        browserStorage.removeItem(key)
      }
    }
    const saved = browserStorage.getItem(ANONYMOUS_CONVERSATION_KEY)
    if (!saved) {
      return
    }
    let id: unknown = saved
    if (saved.startsWith('{')) {
      try {
        const legacy: unknown = JSON.parse(saved)
        id = typeof legacy === 'object' && legacy !== null && 'id' in legacy ? legacy.id : null
      } catch {
        browserStorage.removeItem(ANONYMOUS_CONVERSATION_KEY)
        return
      }
    }
    if (typeof id !== 'string' || !/^[a-zA-Z0-9-]{1,100}$/.test(id)) {
      browserStorage.removeItem(ANONYMOUS_CONVERSATION_KEY)
      return
    }
    // 覆盖旧对象，移除 pending、turns 等曾缓存的正文。
    browserStorage.setItem(ANONYMOUS_CONVERSATION_KEY, id)
    return id
  } catch {
    // 存储不可用时，当前页面仍通过内存状态参与服务端会话。
    return
  }
}

export function rememberAnonymousConversation(id?: string, storage?: Storage) {
  try {
    const browserStorage = storage ?? localStorage
    if (id) {
      browserStorage.setItem(ANONYMOUS_CONVERSATION_KEY, id)
    } else {
      browserStorage.removeItem(ANONYMOUS_CONVERSATION_KEY)
    }
  } catch {
    // 浏览器限制本地存储不影响已完成的服务端操作。
  }
}
