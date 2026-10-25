import { ref, shallowRef } from 'vue'
import type {
  Blog,
  BlogInput,
  BlogContentInput,
  BlogPropertiesInput,
  useBlogApi,
} from '../../api/blogs'
import { ApiRequestError } from '../../api/request'
import { blogSlugIssue } from '../blogSlug'
import { BLOG_INPUT_LIMITS } from '../../constants/blogs'
import type { CanvasMetadata, CanvasSaveSnapshot } from './canvasSave'

type BlogApi = Pick<
  ReturnType<typeof useBlogApi>,
  'create' | 'saveContent' | 'saveProperties' | 'get' | 'transition'
>
type Action = Parameters<BlogApi['transition']>[1]
type Write =
  | { kind: 'create'; body: BlogInput; fingerprint: string }
  | { kind: 'content'; id: number; body: BlogContentInput; fingerprint: string }
  | { kind: 'properties'; id: number; body: BlogPropertiesInput; fingerprint: string }
export type BlogSaveScope = Write['kind'] | 'transition'

/** 正文和属性独立提交；共享修订与请求队列，响应绝不替换正在编辑的文档。 */
export function createBlogPersistence(
  api: BlogApi,
  initial: Blog | null,
  accepted: (post: Blog, scope: BlogSaveScope) => void,
) {
  const post = shallowRef(initial)
  const busy = ref(false)
  const conflicted = ref(false)
  const latest = shallowRef<Blog | null>(null)
  let pending: Write | undefined
  let pendingAction:
    { action: Action; id: number; body: { revision: number; requestId: string } } | undefined
  let queue: Promise<unknown> = Promise.resolve()
  function serial<T>(task: () => Promise<T>): Promise<T> {
    const result = queue.then(async () => {
      if (conflicted.value) {
        throw new Error('存在保存冲突，请先核对服务器状态；当前输入仍保留。')
      }
      busy.value = true
      try {
        return await task()
      } finally {
        busy.value = false
      }
    })
    queue = result.catch(() => {})
    return result
  }
  function accept(value: Blog, scope: BlogSaveScope) {
    if (!Number.isSafeInteger(value.revision) || value.revision === null || value.revision < 1) {
      throw new Error('服务器没有返回有效的管理修订号，请重新读取；本地输入已保留。')
    }
    post.value = value
    accepted(value, scope)
  }
  async function failed(cause: unknown): Promise<never> {
    if (
      cause instanceof ApiRequestError &&
      cause.status === 409 &&
      cause.code !== 'BLOG_SLUG_CONFLICT'
    ) {
      conflicted.value = true
      if (post.value) {
        try {
          latest.value = await api.get(post.value.id)
        } catch {
          /* 读取失败仍保留输入与覆盖保护。 */
        }
      }
      throw new Error('服务器写入冲突，本地输入已保留。请核对后重新读取，不能直接覆盖。')
    }
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
  async function sendPending() {
    const request = pending!
    let value: Blog
    if (request.kind === 'create') {
      value = await api.create(request.body)
    } else if (request.kind === 'content') {
      value = await api.saveContent(request.id, request.body)
    } else {
      value = await api.saveProperties(request.id, request.body)
    }
    accept(value, request.kind)
    pending = undefined
  }
  async function confirmPending(fingerprint: string) {
    if (pendingAction) {
      throw new Error('上次发布状态尚未确认，请先重试该状态操作；本地输入已保留。')
    }
    if (!pending) {
      return false
    }
    const same = pending.fingerprint === fingerprint
    await sendPending()
    return same
  }
  function fields(metadata: CanvasMetadata) {
    return {
      title: metadata.title,
      summary: metadata.summary,
      slug: metadata.slug === post.value?.slug || metadata.slug.trim() ? metadata.slug : '',
      allowDownload: metadata.allowDownload,
      tagIds: Array.from(metadata.tagIds ?? []),
    }
  }
  return {
    post,
    busy,
    conflicted,
    latest,
    adapter: {
      save(snapshot: CanvasSaveSnapshot) {
        const body = {
          content: snapshot.markdown,
          remoteRightsConfirmed: snapshot.metadata.remoteRightsConfirmed,
        }
        return serial<undefined | 'unchanged'>(async () => {
          if (typeof body.content !== 'string') {
            throw new Error('正文尚未通过 Markdown 校验。')
          }
          const content = {
            content: body.content,
            remoteRightsConfirmed: body.remoteRightsConfirmed,
          }
          const fingerprint = JSON.stringify(['content', content])
          try {
            if (await confirmPending(fingerprint)) {
              return
            }
            if (post.value) {
              if (post.value.content === content.content && post.value.resourcesReady) {
                return 'unchanged'
              }
              pending = {
                kind: 'content',
                id: post.value.id,
                fingerprint,
                body: {
                  ...content,
                  revision: post.value.revision!,
                  requestId: crypto.randomUUID(),
                },
              }
            } else {
              // 正文首次保存只建空属性草稿，不夹带尚未提交的设置表单。
              pending = {
                kind: 'create',
                fingerprint,
                body: {
                  ...content,
                  title: '',
                  summary: '',
                  slug: '',
                  tagIds: [],
                  allowDownload: true,
                  requestId: crypto.randomUUID(),
                },
              }
            }
            await sendPending()
          } catch (cause) {
            await failed(cause)
          }
        })
      },
    },
    saveProperties(metadata: CanvasMetadata) {
      const properties = fields(metadata)
      return serial(async () => {
        const issue =
          properties.slug !== (post.value?.slug ?? '') ? blogSlugIssue(properties.slug) : ''
        if (issue) {
          throw new Error(issue)
        }
        if (
          properties.title.length > BLOG_INPUT_LIMITS.title ||
          properties.summary.length > BLOG_INPUT_LIMITS.summary
        ) {
          throw new Error('标题或摘要超出长度限制，请修改后保存。')
        }
        const fingerprint = JSON.stringify(['properties', properties])
        try {
          if (await confirmPending(fingerprint)) {
            return
          }
          if (post.value) {
            const current = post.value
            if (
              properties.title === current.title &&
              properties.summary === current.summary &&
              properties.slug === (current.slug ?? '') &&
              properties.allowDownload === current.allowDownload &&
              JSON.stringify([...properties.tagIds].sort()) ===
                JSON.stringify(current.tags.map((tag) => tag.id).sort())
            ) {
              return
            }
            pending = {
              kind: 'properties',
              id: current.id,
              fingerprint,
              body: { ...properties, revision: current.revision!, requestId: crypto.randomUUID() },
            }
          } else {
            pending = {
              kind: 'create',
              fingerprint,
              body: {
                ...properties,
                content: '',
                remoteRightsConfirmed: false,
                requestId: crypto.randomUUID(),
              },
            }
          }
          await sendPending()
        } catch (cause) {
          await failed(cause)
        }
      })
    },
    transition(action: Action) {
      return serial(async () => {
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
        try {
          accept(await api.transition(pendingAction.id, action, pendingAction.body), 'transition')
          pendingAction = undefined
        } catch (cause) {
          await failed(cause)
        }
      })
    },
  }
}
