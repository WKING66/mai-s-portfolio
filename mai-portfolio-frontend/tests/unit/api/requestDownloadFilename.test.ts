import { afterEach, describe, expect, it, vi } from 'vitest'
import { requestDownload } from '../../../app/api/request'

afterEach(() => vi.unstubAllGlobals())

describe('export download filenames', () => {
  it.each(['数据结构与算法.zip', '中文 标题+v2.md', '文档（最终版）.zip'])(
    'uses the UTF-8 original name %s instead of the encoded fallback',
    async (filename) => {
      const blob = new Blob(['export'])
      const headers = new Headers({
        'content-disposition': 'attachment; filename="encoded-fallback"; filename*=UTF-8\'\'' + encodeURIComponent(filename),
        'content-type': 'application/zip',
      })
      vi.stubGlobal('$fetch', { raw: vi.fn().mockResolvedValue({ ok: true, status: 200, _data: blob, headers }) })
      const result = await requestDownload('/api/v1/blogs/3/export', {}, '导出失败')
      expect(result.filename).toBe(filename)
      expect(result.blob).toBe(blob)
    },
  )

  it.each([
    ['attachment; filename="article.md"', 'text/markdown', 'article.md'],
    ['attachment; filename="fallback.md"; filename*=UTF-8\'\'bad%zz', 'text/markdown', 'fallback.md'],
    ['attachment; filename*=UTF-8\'\'..%2Fescape.zip', 'application/zip', 'article.zip'],
    ['', 'application/zip', 'article.zip'],
  ])('handles a legacy or invalid header safely', async (disposition, contentType, filename) => {
    vi.stubGlobal('$fetch', { raw: vi.fn().mockResolvedValue({
      ok: true, status: 200, _data: new Blob(['export']),
      headers: new Headers({ 'content-disposition': disposition, 'content-type': contentType }),
    }) })
    expect((await requestDownload('/api/v1/blogs/3/export', {}, '导出失败')).filename).toBe(filename)
  })
})
