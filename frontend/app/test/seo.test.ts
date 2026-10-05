import { describe, expect, it } from 'vitest'
import { createHead, renderSSRHead } from 'unhead/server'
import {
  createProfileStructuredData, createProjectStructuredData, createRobotsText, createSitemapXml,
  getCanonicalUrl, getProjectCanonicalPath, getSiteOrigin,
} from '../../shared/utils/seo'

const siteOrigin = 'https://portfolio.test'
const profile = {
  displayName: '测试作者', headline: '公开定位', intro: '公开介绍',
  githubUrl: 'https://github.com/test-author', email: 'contact@example.test',
}

describe('portfolio SEO', () => {
  it('leaves the public origin unset and disables discovery before deployment', () => {
    expect(getSiteOrigin('')).toBe('')
    expect(getCanonicalUrl('', '/')).toBeUndefined()
    expect(createProfileStructuredData('', profile)).toBeNull()
    expect(createSitemapXml('')).toBeNull()
    expect(createRobotsText('')).toBe('User-agent: *\nDisallow: /\n')
  })

  it.each([
    'ftp://portfolio.test', '//portfolio.test', 'portfolio.test', 'https://user:secret@portfolio.test',
    'https://portfolio.test/path', 'https://portfolio.test/?query=1', 'https://portfolio.test/#fragment',
    'https://portfolio.test/?', 'https://portfolio.test/#', 'not a URL',
  ])('rejects unsafe or non-origin configuration without echoing it: %s', value => {
    expect(() => getSiteOrigin(value)).toThrow('NUXT_PUBLIC_SITE_URL must be an HTTP(S) origin')
    try {
      getSiteOrigin(value)
    } catch (error) {
      expect(error instanceof Error && error.message.includes(value)).toBe(false)
    }
  })

  it('normalizes root origins and keeps valid deployment ports', () => {
    expect(getSiteOrigin(' https://PORTFOLIO.test/ ')).toBe(siteOrigin)
    expect(getSiteOrigin('http://127.0.0.1:3000')).toBe('http://127.0.0.1:3000')
  })

  it('gives each project page its own canonical URL', () => {
    expect(getCanonicalUrl(siteOrigin, getProjectCanonicalPath(1))).toBe(siteOrigin + '/projects')
    expect(getCanonicalUrl(siteOrigin, getProjectCanonicalPath(2))).toBe(siteOrigin + '/projects?page=2')
  })

  it.each(['https://evil.test', '//evil.test', '/\\evil.test', '/\n/evil.test'])('rejects external canonical paths: %s', path => {
    expect(() => getCanonicalUrl(siteOrigin, path)).toThrow('Canonical path must be a local absolute path')
  })

  it('publishes only approved visible profile fields and omits revoked links', () => {
    const structuredData = createProfileStructuredData(siteOrigin, profile)
    expect(structuredData?.mainEntity).toEqual({
      '@type': 'Person', name: profile.displayName, description: profile.intro,
      url: siteOrigin + '/', sameAs: [profile.githubUrl],
    })
    expect(JSON.stringify(structuredData)).not.toContain(profile.email)
    expect(createProfileStructuredData(siteOrigin, { ...profile, githubUrl: null })?.mainEntity).not.toHaveProperty('sameAs')
    expect(createProfileStructuredData(siteOrigin, null)).toBeNull()
  })

  it('renders profile JSON-LD safely through the installed head renderer', () => {
    const intro = '</script><script>alert(1)</script>'
    const structuredData = createProfileStructuredData(siteOrigin, { ...profile, intro })
    const head = createHead()
    head.push({ script: [{ type: 'application/ld+json', textContent: JSON.stringify(structuredData) }] })
    const html = renderSSRHead(head).headTags
    expect(html).not.toContain('<script>alert(1)</script>')
    const scriptContent = html.match(/<script type="application\/ld\+json">([^]*?)<\/script>/)?.[1]
    expect(scriptContent).toBeDefined()
    expect(JSON.parse(scriptContent || '{}').mainEntity.description).toBe(intro)
  })

  it('describes the same public project page with actual positions and external links', () => {
    const projects = {
      view: 'PUBLIC' as const, page: 2, size: 12,
      items: [
        { title: '公开项目 A', summary: '真实摘要 A', links: [{ url: 'https://github.com/test-author/project-a' }] },
        { title: '公开项目 B', summary: '真实摘要 B', links: [] },
      ],
    }
    const structuredData = createProjectStructuredData(siteOrigin, projects)
    expect(structuredData?.url).toBe(siteOrigin + '/projects?page=2')
    expect(structuredData?.itemListElement).toEqual([
      { '@type': 'ListItem', position: 13, item: { '@type': 'CreativeWork', name: '公开项目 A', description: '真实摘要 A', url: ['https://github.com/test-author/project-a'] } },
      { '@type': 'ListItem', position: 14, item: { '@type': 'CreativeWork', name: '公开项目 B', description: '真实摘要 B' } },
    ])
    expect(JSON.stringify(structuredData)).not.toMatch(/datePublished|image|projects\/project-a/)
    expect(createProjectStructuredData(siteOrigin, { ...projects, view: 'MANAGE' })).toBeNull()
    expect(createProjectStructuredData(siteOrigin, { ...projects, items: [] })).toBeNull()
    expect(createProjectStructuredData(siteOrigin, null)).toBeNull()
    expect(createProjectStructuredData('', projects)).toBeNull()
  })

  it('lists only existing public columns without fabricated content dates', () => {
    const xml = createSitemapXml(siteOrigin)
    expect(xml?.match(/<loc>[^<]+<\/loc>/g)).toEqual([
      '<loc>' + siteOrigin + '/</loc>', '<loc>' + siteOrigin + '/projects</loc>',
    ])
    expect(xml).not.toMatch(/admin|blog|lastmod|priority|changefreq/)
    expect(createSitemapXml('https://a&b.test')).toContain('https://a&amp;b.test/')
  })

  it('keeps noindex HTML crawlable and declares the configured sitemap', () => {
    const robots = createRobotsText(siteOrigin)
    expect(robots).toContain('Sitemap: ' + siteOrigin + '/sitemap.xml')
    expect(robots).toContain('Disallow: /api/v1/admin/')
    expect(robots).not.toContain('Disallow: /admin')
    expect(robots).not.toMatch(/Disallow: \/\n/)
  })
})
