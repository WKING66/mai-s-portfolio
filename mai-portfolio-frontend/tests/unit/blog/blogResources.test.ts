import { describe, expect, it } from 'vitest'
import { resolveBlogResource, safeBlogUrl } from '../../../app/utils/blog/resources'
import type { DocumentResource } from '../../../app/api/blogs'
const resource: DocumentResource = { key: 'image', type: 'IMAGE', status: 'READY', assetId: 7, resourceUrl: 'https://oss.example/a?Signature=test%2Bvalue', expiresAt: null, originalUri: null }
describe('blog resource display projection', () => {
  it('accepts only safe Web addresses and preserves signed values', () => {
    for (const url of ['javascript:alert(1)', 'java%73cript:alert(1)', 'data:image/svg+xml,<svg/>', '//evil.example', '\\evil', 'file:///secret', 'https:\n//evil']) expect(safeBlogUrl(url)).toBeUndefined()
    expect(safeBlogUrl(resource.resourceUrl, true)).toBe(resource.resourceUrl)
    expect(safeBlogUrl('/images/example.svg', true)).toBe('/images/example.svg')
    expect(safeBlogUrl('mailto:a@example.com')).toBe('mailto:a@example.com')
    expect(safeBlogUrl('mailto:a@example.com', true)).toBeUndefined()
  })
  it('resolves only authorized resources for the current document version', () => {
    expect(resolveBlogResource('resource://image', [resource], true)).toBe(resource.resourceUrl)
    expect(resolveBlogResource('asset://7', [resource], true)).toBe(resource.resourceUrl)
    expect(resolveBlogResource('asset://8', [resource], true)).toBeUndefined()
    expect(resolveBlogResource('resource://missing', [resource], true)).toBeUndefined()
    expect(resolveBlogResource('resource://image', [{ ...resource, type: 'ATTACHMENT' }], true)).toBeUndefined()
    expect(resolveBlogResource('resource://image', [{ ...resource, status: 'INVALID' }], true)).toBeUndefined()
    expect(resolveBlogResource('resource://image', [{ ...resource, resourceUrl: 'javascript:alert(1)' }], true)).toBeUndefined()
  })
  it('updated URL projections change display without modifying Markdown references', () => {
    const source = '![a](resource://image)\n\n`asset://7`'
    const updated = { ...resource, resourceUrl: 'https://oss.example/new' }
    expect(resolveBlogResource('resource://image', [updated], true)).toBe(updated.resourceUrl)
    expect(source).toBe('![a](resource://image)\n\n`asset://7`')
  })
})
