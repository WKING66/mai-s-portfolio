export interface AssistantSource {
  title: string
  summary: string
  url: string
  kind: 'project' | 'article' | 'profile'
}
export interface AssistantContext {
  name: string
  sources: AssistantSource[]
}
export interface MockAnswer {
  content: string
  sources: AssistantSource[]
}

// 仅模拟站内资料问答；不请求模型，也不把未发布或管理端内容作为知识来源。
export function createMockAnswer(
  question: string,
  context: AssistantContext,
  quote = '',
): MockAnswer {
  const query = question + ' ' + quote
  let kind: AssistantSource['kind'] | undefined
  if (/文章|笔记|阅读|文档/.test(query)) {
    kind = 'article'
  } else if (/项目|作品|网站|个人站|技术|栈|Java|Python/i.test(query)) {
    kind = 'project'
  } else if (/介绍|站主|经历|学习|学生|吉他|阿霾/.test(query)) {
    kind = 'profile'
  }
  const sources = context.sources.filter((source) => source.kind === kind).slice(0, 3)
  if (!sources.length) {
    return {
      content:
        '当前公开资料还不足以回答这个问题。可以先看看站内的项目和博客，或换一个关于本站内容的问题。',
      sources: [],
    }
  }
  const introductions = {
    project: '可以从这些公开项目了解他的实践：',
    article: '可以从这些站内笔记开始阅读：',
    profile: `${context.name}的公开介绍：`,
  }
  const introduction = introductions[kind!]
  return {
    content:
      (quote ? '结合你选中的原文，' : '') +
      introduction +
      '\n\n' +
      sources
        .map((source, index) => `${index + 1}. ${source.title}\n${source.summary}`)
        .join('\n\n'),
    sources,
  }
}

// 返回真实的异步增量，界面消费方式与未来服务端流一致；取消后不再交付后续字符。
export async function* streamMockAnswer(content: string, signal: AbortSignal) {
  const characters = Array.from(content)
  for (let index = 0; index < characters.length && !signal.aborted; index += 3) {
    await new Promise<void>((resolve) => {
      const complete = () => {
        clearTimeout(timer)
        signal.removeEventListener('abort', complete)
        resolve()
      }
      const timer = setTimeout(complete, 24)
      signal.addEventListener('abort', complete, { once: true })
      if (signal.aborted) {
        complete()
      }
    })
    if (!signal.aborted) {
      yield characters.slice(index, index + 3).join('')
    }
  }
}
