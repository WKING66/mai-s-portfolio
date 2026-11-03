import { describe, expect, it, vi } from 'vitest'
import {
  createMockAnswer,
  streamMockAnswer,
  type AssistantContext,
} from '../../../app/utils/site/mockAssistant'

const context: AssistantContext = {
  name: '阿霾',
  sources: [
    { title: '个人站', summary: '真实公开项目', url: '/projects', kind: 'project' },
    { title: '公开笔记', summary: '文章摘要', url: '/blog/note', kind: 'article' },
    { title: '个人介绍', summary: '大三学生', url: '/#about', kind: 'profile' },
  ],
}
describe('Mock assistant public answers and streaming', () => {
  it('uses the selected text when explaining a project', () => {
    const answer = createMockAnswer('解释这段', context, '这个项目如何实现？')
    expect(answer.sources).toEqual([context.sources[0]])
    expect(answer.content).toContain('真实公开项目')
  })
  it('declines unrelated questions without inventing sources', () => {
    expect(createMockAnswer('明天北京天气怎样', context).sources).toEqual([])
    expect(createMockAnswer('推荐文章', { name: '阿霾', sources: [] }).content).toContain('不足')
  })
  it('streams complete Unicode characters and stops before delivering another chunk', async () => {
    vi.useFakeTimers()
    try {
      const controller = new AbortController()
      const chunks: string[] = []
      const task = (async () => {
        for await (const chunk of streamMockAnswer('中文😀测试完整回复', controller.signal)) {
          chunks.push(chunk)
          controller.abort()
        }
      })()
      await vi.runAllTimersAsync()
      await task
      expect(chunks).toEqual(['中文😀'])
    } finally {
      vi.useRealTimers()
    }
  })
  it('delivers every character across multiple updates', async () => {
    vi.useFakeTimers()
    try {
      const chunks: string[] = []
      const task = (async () => {
        for await (const chunk of streamMockAnswer(
          '正文😀逐步输出',
          new AbortController().signal,
        )) {
          chunks.push(chunk)
        }
      })()
      await vi.runAllTimersAsync()
      await task
      expect(chunks.length).toBeGreaterThan(1)
      expect(chunks.join('')).toBe('正文😀逐步输出')
    } finally {
      vi.useRealTimers()
    }
  })
})
