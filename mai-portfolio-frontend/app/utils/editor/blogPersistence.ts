import { ref, shallowRef } from 'vue'
import type { Blog, BlogInput, useBlogApi } from '../../api/blogs'
import { ApiRequestError } from '../../api/request'
import { blogSlugIssue } from '../blogSlug'
import { BLOG_INPUT_LIMITS } from '../../constants/blogs'
import type { CanvasSaveSnapshot } from './canvasSave'

type BlogApi = Pick<ReturnType<typeof useBlogApi>, 'save' | 'get' | 'transition'>
type Action = Parameters<BlogApi['transition']>[1]

/** 只管理服务器修订与请求身份，绝不把响应正文写回正在编辑的 PM 文档。 */
export function createBlogPersistence(
  api: BlogApi,
  initial: Blog | null,
  accepted: (post: Blog) => void,
) {
  const post = shallowRef(initial)
  const busy = ref(false)
  const conflicted = ref(false)
  const latest = shallowRef<Blog | null>(null)
  let pending: { id: number | null; body: BlogInput; fingerprint: string } | undefined
  let pendingAction:
    { action: Action; id: number; body: { revision: number; requestId: string } } | undefined

  function accept(value: Blog) {
    if (!Number.isSafeInteger(value.revision) || value.revision === null || value.revision < 1) {
      throw new Error('服务器没有返回有效的管理修订号，请重新读取；本地输入已保留。')
    }
    post.value = value
    accepted(value)
  }
  async function failed(cause: unknown): Promise<never> {
    if (cause instanceof ApiRequestError && cause.status === 409) {
      conflicted.value = true
      if (post.value) {
        try {
          latest.value = await api.get(post.value.id)
        } catch {
          // 读取失败也不解除冲突保护；用户仍可导出本地输入。
        }
      }
      throw new Error(
        '保存冲突：服务器内容或文章标识已变化。本地输入已保留，请核对后重新读取，不能直接覆盖。',
      )
    }
    // 已明确拒绝的输入没有持久化，修正后使用新的请求；断线/5xx 保留原请求以安全重试。
    if (
      cause instanceof ApiRequestError &&
      cause.status &&
      cause.status >= 400 &&
      cause.status < 500
    ) {
      pending = undefined
      pendingAction = undefined
    }
    throw cause
  }
  function requireAvailable() {
    if (conflicted.value) {
      throw new Error('存在保存冲突，请先核对服务器状态；当前输入仍保留。')
    }
    if (busy.value) {
      throw new Error('上一项文章操作尚未结束，请稍后重试。')
    }
  }
  async function sendPending() {
    const request = pending!
    const value = await api.save(request.id, request.body)
    accept(value)
    pending = undefined
  }
  return {
    post,
    busy,
    conflicted,
    latest,
    adapter: {
      async save(snapshot: CanvasSaveSnapshot) {
        requireAvailable()
        if (pendingAction) {
          throw new Error('上次发布状态尚未确认，请先重试该状态操作；本地输入已保留。')
        }
        if (typeof snapshot.markdown !== 'string') {
          throw new Error('正文尚未通过 Markdown 校验。')
        }
        const { metadata } = snapshot
        const issue = blogSlugIssue(metadata.slug)
        if (issue) {
          throw new Error(issue)
        }
        if (
          metadata.title.length > BLOG_INPUT_LIMITS.title ||
          metadata.summary.length > BLOG_INPUT_LIMITS.summary
        ) {
          throw new Error('标题或摘要超出长度限制，请修改后保存。')
        }
        const fields = {
          ...metadata,
          tagIds: metadata.tagIds ?? [],
          content: snapshot.markdown,
        }
        const fingerprint = JSON.stringify(fields)
        busy.value = true
        try {
          // 原请求可能已经在服务器成功；先用完全相同的身份/输入确认，随后才保存新编辑。
          if (pending) {
            const same = pending.fingerprint === fingerprint
            await sendPending()
            if (same) {
              return
            }
          }
          pending = {
            id: post.value?.id ?? null,
            fingerprint,
            body: {
              ...fields,
              requestId: crypto.randomUUID(),
              ...(post.value ? { revision: post.value.revision! } : {}),
            },
          }
          await sendPending()
        } catch (cause) {
          await failed(cause)
        } finally {
          busy.value = false
        }
      },
    },
    async transition(action: Action) {
      requireAvailable()
      if (!post.value || pending) {
        throw new Error('请先完成草稿保存，再操作发布状态。')
      }
      if (pendingAction && pendingAction.action !== action) {
        throw new Error('上次状态操作尚未确认，请先重试该操作。')
      }
      pendingAction ??= {
        action,
        id: post.value.id,
        body: { revision: post.value.revision!, requestId: crypto.randomUUID() },
      }
      busy.value = true
      try {
        const result = await api.transition(pendingAction.id, action, pendingAction.body)
        accept(result)
        pendingAction = undefined
      } catch (cause) {
        await failed(cause)
      } finally {
        busy.value = false
      }
    },
  }
}
