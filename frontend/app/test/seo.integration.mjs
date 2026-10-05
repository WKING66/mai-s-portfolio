import assert from 'node:assert/strict'
import { test } from 'node:test'

// 对已构建并运行的真实 Nuxt/后端检查 HTTP 输出，不启动服务或改动数据库。
const baseUrl = process.env.SEO_BASE_URL || 'http://127.0.0.1:3004'
const siteOrigin = process.env.SEO_EXPECTED_ORIGIN || ''
const read = path => fetch(baseUrl + path)
const structuredData = html => [...html.matchAll(/<script\b[^>]*type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/g)]
  .map(match => JSON.parse(match[1]))
const canonical = html => html.match(/<link\b[^>]*rel="canonical"[^>]*href="([^"]+)"/)?.[1]
const robots = html => html.match(/<meta\b[^>]*name="robots"[^>]*content="([^"]+)"/)?.[1]

test('公开首页的 SSR 元数据来自当前真实公开资料', async () => {
  const profileResponse = await read('/api/v1/public/profile')
  assert.equal(profileResponse.status, 200)
  const profile = (await profileResponse.json()).data
  const response = await read('/')
  assert.equal(response.status, 200)
  const html = await response.text()
  assert.doesNotMatch(html, /projects:MANAGE|<(?:input|textarea)[^>]*id="profile-name"/)
  assert.equal(canonical(html), siteOrigin ? siteOrigin + '/' : undefined)
  assert.equal(robots(html), siteOrigin ? 'index,follow' : 'noindex,nofollow')
  const json = structuredData(html)
  if (siteOrigin) {
    const author = json.find(item => item['@type'] === 'ProfilePage')?.mainEntity
    assert.equal(author?.name, profile.displayName)
    assert.equal(author?.description, profile.intro)
    assert.equal(author?.url, siteOrigin + '/')
    assert.deepEqual(author?.sameAs, profile.githubUrl ? [profile.githubUrl] : undefined)
    assert.equal(author?.email, undefined)
    assert.ok(html.includes('property="og:url" content="' + siteOrigin + '/"'))
  } else {
    assert.equal(json.length, 0)
    assert.doesNotMatch(html, /property="og:url"/)
  }
})

test('项目结构化数据只使用同一 PUBLIC 页，剔除冗余查询', async () => {
  const projects = (await (await read('/api/v1/projects?view=PUBLIC&page=1&size=12')).json()).data
  const response = await read('/projects?page=1&ignored=1')
  assert.equal(response.status, 200)
  const html = await response.text()
  assert.equal(canonical(html), siteOrigin ? siteOrigin + '/projects' : undefined)
  assert.equal(robots(html), siteOrigin ? 'index,follow' : 'noindex,nofollow')
  const list = structuredData(html).find(item => item['@type'] === 'ItemList')
  if (siteOrigin && projects.items.length) {
    assert.equal(list?.itemListElement.length, projects.items.length)
    assert.deepEqual(list.itemListElement.map(item => item.item.name), projects.items.map(item => item.title))
    assert.deepEqual(list.itemListElement.map(item => item.position), projects.items.map((_, index) => index + 1))
    assert.doesNotMatch(JSON.stringify(list), /datePublished|dateModified|"image"/)
  } else assert.equal(list, undefined)
})

test('第二页规范到自身，越界空页不可索引且无虚构条目', async () => {
  const projects = (await (await read('/api/v1/projects?view=PUBLIC&page=2&size=12')).json()).data
  const response = await read('/projects?page=2&ignored=1')
  assert.equal(response.status, 200)
  const html = await response.text()
  assert.equal(canonical(html), siteOrigin ? siteOrigin + '/projects?page=2' : undefined)
  assert.equal(robots(html), siteOrigin && projects.items.length ? 'index,follow' : 'noindex,nofollow')
  const list = structuredData(html).find(item => item['@type'] === 'ItemList')
  if (!projects.items.length || !siteOrigin) assert.equal(list, undefined)
  else assert.equal(list.itemListElement[0].position, 13)
})

test('robots 与 sitemap 跟随运行配置，不使用请求 Host', async () => {
  const robotsResponse = await read('/robots.txt')
  assert.equal(robotsResponse.status, 200)
  assert.match(robotsResponse.headers.get('content-type') || '', /text\/plain/)
  const text = await robotsResponse.text()
  const sitemapResponse = await read('/sitemap.xml')
  assert.equal(sitemapResponse.status, siteOrigin ? 200 : 503)
  const sitemap = await sitemapResponse.text()
  if (siteOrigin) {
    assert.match(text, /Allow: \/\n/)
    assert.ok(text.includes('Sitemap: ' + siteOrigin + '/sitemap.xml'))
    assert.deepEqual([...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map(match => match[1]),
      [siteOrigin + '/', siteOrigin + '/projects'])
    assert.doesNotMatch(sitemap, /admin|blog|lastmod|priority|changefreq/)
  } else {
    assert.equal(text, 'User-agent: *\nDisallow: /\n')
    assert.equal(sitemap, 'Site URL is not configured\n')
  }
  const poisoned = await fetch(baseUrl + '/sitemap.xml', {
    headers: { Host: 'host-poison.test', 'X-Forwarded-Host': 'forwarded-poison.test' },
  })
  assert.equal(poisoned.status, sitemapResponse.status)
  assert.equal(await poisoned.text(), sitemap)
})

test('私有页面的响应头与 SSR 都禁止索引，管理表单不进入 HTML', async () => {
  for (const path of ['/login', '/forbidden', '/admin/profile', '/admin/projects']) {
    const response = await read(path)
    assert.equal(response.status, 200)
    assert.equal(response.headers.get('x-robots-tag'), 'noindex,nofollow')
    const html = await response.text()
    assert.equal(robots(html), 'noindex,nofollow')
    assert.doesNotMatch(html, /<(?:input|textarea)[^>]*id="(?:profile-name|project-title)"|projects:MANAGE/)
  }
})
