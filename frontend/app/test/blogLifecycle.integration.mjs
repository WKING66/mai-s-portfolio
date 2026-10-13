import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import { loadNuxt, build } from 'nuxt'
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const frontend = fileURLToPath(new URL('../../', import.meta.url))
const output = resolve(frontend, '../.local-backups/blog-lifecycle-browser')
const owner = { loggedIn: true, username: 'fixture', roles: ['OWNER'], csrfToken: 'fixture-csrf' }
const externalUrl = process.env.EXTERNAL_ACCEPTANCE_URL
let browser, app, baseUrl, draft, published
const pageErrors = [], hydrationErrors = [], requests = []
const envelope = data => ({ code: 'OK', data, message: '', details: [] })
const fixtureHandler = async (req, res) => {
  const url = new URL(req.url, 'http://localhost')
  let body = ''; for await (const chunk of req) body += chunk
  requests.push({ method: req.method, path: url.pathname, query: Object.fromEntries(url.searchParams), body })
  res.setHeader('content-type', 'application/json')
  let data
  if (url.pathname === '/api/v1/auth/session') data = owner
  else if (url.pathname === '/api/v1/account/profile') data = { username: 'fixture', nickname: '博客测试', avatarUrl: null, avatarExpiresAt: null }
  else if (url.pathname === '/api/v1/blogs' && req.method === 'POST' || url.pathname === '/api/v1/blogs/7' && req.method === 'PATCH') {
    assert.equal(req.headers['x-csrf-token'], owner.csrfToken)
    const input = JSON.parse(body)
    draft = { ...input, id: 7, version: (draft?.version ?? 0) + 1, documentId: 70, documentVersionId: 71,
      status: published ? 'PUBLISHED' : 'DRAFT', resources: [], publishedAt: published?.publishedAt ?? null, updatedAt: '2026-10-08T00:00:00' }
    data = draft
  } else if (url.pathname === '/api/v1/blogs/7/publish' || url.pathname === '/api/v1/blogs/7/unpublish') {
    assert.equal(req.headers['x-csrf-token'], owner.csrfToken)
    draft = { ...draft, version: draft.version + 1, status: url.pathname.endsWith('/unpublish') ? 'DRAFT' : 'PUBLISHED', publishedAt: '2026-10-08T00:00:00' }
    published = draft.status === 'PUBLISHED' ? { ...draft } : null
    data = draft
  } else if (url.pathname === '/api/v1/blogs/imports') {
    assert.equal(req.headers['x-csrf-token'], owner.csrfToken)
    assert.match(req.headers['content-type'], /multipart/)
    assert.match(body, /filename="sample.md"/)
    draft = { id: 7, slug: null, title: 'Imported', summary: '', content: '# Imported\n![a](resource://image-key)',
      status: 'DRAFT', version: 1, documentId: 70, documentVersionId: 71, allowDownload: true, publishedAt: null, updatedAt: '2026-10-08T00:00:00',
      resources: [{ key: 'image-key', type: 'IMAGE', status: 'READY', assetId: 19, resourceUrl: 'https://oss.example/a.png', expiresAt: '2030-01-01T00:00:00Z', originalUri: 'a.png' }] }
    data = draft
  } else if (url.pathname === '/api/v1/blogs/7/export') {
    assert.equal(url.searchParams.get('view'), 'MANAGE')
    const zip = url.searchParams.get('includeResources') === 'true' && draft.resources.length > 0
    res.setHeader('content-type', zip ? 'application/zip' : 'text/markdown')
    res.setHeader('content-disposition', 'attachment; filename="article.' + (zip ? 'zip' : 'md') + '"')
    res.end(zip ? Buffer.from('PK-fixture') : '# Exported'); return
  } else if (url.pathname === '/api/v1/blogs/7') data = draft
  else if (url.pathname === '/api/v1/blogs') data = { view: url.searchParams.get('view') || 'PUBLIC', page: 1, size: 12,
    total: url.searchParams.get('view') === 'MANAGE' ? (draft ? 1 : 0) : (published ? 1 : 0), items: url.searchParams.get('view') === 'MANAGE' ? (draft ? [draft] : []) : (published ? [published] : []) }
  else if (url.pathname.startsWith('/api/v1/blogs/by-slug/')) {
    if (!published) { res.statusCode = 404; res.end(JSON.stringify({ code: 'NOT_FOUND', message: '未公开', details: [] })); return }
    data = published
  } else { res.statusCode = 404; data = null }
  res.end(JSON.stringify(envelope(data)))
}
const api = createServer(fixtureHandler)
before(async () => {
  if (externalUrl) {
    baseUrl = externalUrl
    browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) })
    return
  }
  await new Promise(resolve => api.listen(0, '127.0.0.1', resolve))
  const apiUrl = `http://127.0.0.1:${api.address().port}`
  const nuxt = await loadNuxt({ cwd: frontend, dev: false, overrides: {
    buildDir: output + '/.nuxt',
    routeRules: { '/api/v1/**': { proxy: apiUrl + '/api/v1/**' } },
    nitro: { output: { dir: output + '/.output', serverDir: output + '/.output/server', publicDir: output + '/.output/public' } },
  } })
  try { await build(nuxt) } finally { await nuxt.close() }
  const portProbe = createServer()
  await new Promise(resolve => portProbe.listen(0, '127.0.0.1', resolve))
  const port = portProbe.address().port
  await new Promise(resolve => portProbe.close(resolve))
  const childEnv = { ...process.env, HOST: '127.0.0.1', PORT: String(port), NITRO_HOST: '127.0.0.1', NITRO_PORT: String(port) }
  delete childEnv.NODE_TEST_CONTEXT // 应用子进程不继承 node:test 的二进制报告协议。
  let log = ''
  app = spawn(process.execPath, [output + '/.output/server/index.mjs'], {
    cwd: frontend, windowsHide: true, env: childEnv, stdio: ['ignore', 'pipe', 'pipe'],
  })
  // 从实际监听地址读取随机端口，测试不占用或重启任何现有服务。
  baseUrl = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Nuxt 启动超时: ' + log)), 10000)
    function read(chunk) {
      log += chunk
      const match = log.match(/Listening on (http:\/\/127\.0\.0\.1:\d+)/)
      if (match) { clearTimeout(timer); resolve(match[1]) }
    }
    app.stdout.on('data', read); app.stderr.on('data', read)
    app.once('exit', code => { clearTimeout(timer); reject(new Error('Nuxt 提前退出: ' + code + log)) })
  })
  browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) })
}, { timeout: 180000 })

after(async () => {
  if (browser) await browser.close()
  if (app) app.kill()
  if (api.listening) await new Promise(resolve => api.close(resolve))
  assert.deepEqual(hydrationErrors, [], '持久化恢复不能导致 SSR 水合不匹配')
  assert.deepEqual(pageErrors, [], '浏览器不能出现未处理异常')
})


async function pageIn(context) {
  if (externalUrl) {
    // 仅拦截隔离浏览器中的 API，不启动额外服务，所有写入只进入内存 fixture。
    await context.route('**/api/v1/**', async route => {
      const request = route.request(), headers = {}, chunks = []
      const req = { url: new URL(request.url()).pathname + new URL(request.url()).search, method: request.method(), headers: request.headers(),
        async *[Symbol.asyncIterator]() { yield request.postDataBuffer() || Buffer.alloc(0) } }
      const res = { statusCode: 200, setHeader: (key, value) => { headers[key] = value }, end: data => { chunks.push(Buffer.from(data || '')) } }
      await fixtureHandler(req, res)
      await route.fulfill({ status: res.statusCode, headers, body: Buffer.concat(chunks) })
    })
  }
  const page = await context.newPage()
  page.on('console', message => { if (/hydration/i.test(message.text())) hydrationErrors.push(message.text()) })
  page.on('pageerror', error => pageErrors.push(error.message))
  return page
}
async function openReader(page) {
  if (!externalUrl) return page.goto(baseUrl + '/blog/first-article')
  await page.goto(baseUrl + '/')
  await page.locator('[data-account-entry]:not([disabled])').waitFor()
  await page.evaluate(() => document.querySelector('#__nuxt').__vue_app__.config.globalProperties.$nuxt.$router.push('/blog/first-article'))
}
async function clearFeedback(page) {
  const toasts = page.locator('[data-slot="viewport"] li[data-state="open"]')
  while (await toasts.count()) await toasts.first().locator('button').click()
}
test('owner editor saves, explicitly publishes, preserves public draft boundary and unpublishes', async () => {
  draft = null; published = null; requests.length = 0
  const context = await browser.newContext(); const page = await pageIn(context)
  try {
    await page.goto(baseUrl + '/admin/blogs/new')
    await page.getByLabel('标题', { exact: true }).fill('First article')
    await page.getByLabel('文章标识', { exact: true }).fill('first-article')
    await page.getByLabel('摘要', { exact: true }).fill('Summary')
    await page.getByLabel('正文', { exact: true }).fill('# First body')
    await page.getByRole('button', { name: '保存草稿', exact: true }).click()
    await page.waitForURL('**/admin/blogs/7')
    await page.getByRole('button', { name: '发布当前草稿', exact: true }).click()
    await page.getByRole('link', { name: '查看公开文章' }).waitFor()
    await clearFeedback(page)
    await page.getByLabel('标题', { exact: true }).fill('Second draft')
    await page.getByLabel('正文', { exact: true }).fill('# Second private body')
    assert.equal(await page.getByRole('button', { name: '发布当前草稿' }).isDisabled(), true)
    await page.getByRole('button', { name: '保存草稿', exact: true }).click()
    await page.locator('[data-slot="viewport"] li[data-state="open"]').filter({ hasText: '草稿已保存。' }).first().waitFor()
    const reader = await pageIn(context); await openReader(reader)
    await reader.getByRole('heading', { name: 'First article', exact: true }).waitFor()
    await reader.locator('.blog-renderer').getByRole('heading', { name: 'First body', exact: true }).waitFor()
    assert.equal(await reader.locator('.blog-renderer').getByText('Second private body').count(), 0)
    await page.getByRole('button', { name: '已保存版本预览', exact: true }).click()
    await page.locator('.blog-renderer').getByRole('heading', { name: 'Second private body', exact: true }).waitFor()
    if (process.env.BLOG_LIFECYCLE_SCREENSHOT_DIR) await page.screenshot({ path: resolve(process.env.BLOG_LIFECYCLE_SCREENSHOT_DIR, 'management-saved-preview.png'), fullPage: true })
    await page.getByRole('button', { name: '下架文章', exact: true }).click()
    await page.locator('[data-slot="viewport"] li[data-state="open"]').filter({ hasText: '文章已下架。' }).first().waitFor()
    const response = await openReader(reader)
    if (externalUrl) await reader.getByRole('heading', { name: '这条路径，暂时没有内容' }).waitFor()
    else assert.equal(response.status(), 404)
    assert.ok(requests.some(r => r.method === 'PATCH' && r.path === '/api/v1/blogs/7' && JSON.parse(r.body).version > 1))
  } finally { await context.close() }
}, { timeout: 45000 })
test('Markdown import uses shared editor and switches md or ZIP according to resource choice', async () => {
  draft = null; published = null
  const context = await browser.newContext(); const page = await pageIn(context)
  try {
    await page.goto(baseUrl + '/admin/blogs')
    await page.locator('input[accept=".md"]').setInputFiles({ name: 'sample.md', mimeType: 'text/markdown', buffer: Buffer.from('# Imported') })
    await page.getByRole('button', { name: '导入为草稿', exact: true }).click()
    await page.waitForURL('**/admin/blogs/7')
    await page.getByLabel('正文', { exact: true }).waitFor()
    // Imported slug is empty. Export works on the saved draft, independently of publish completeness.
    const mdPending = page.waitForEvent('download'); await page.getByRole('button', { name: '导出已保存草稿' }).click()
    assert.equal((await mdPending).suggestedFilename(), 'article.md')
    await page.getByRole('checkbox', { name: '包含图片和附件（ZIP）' }).check()
    const zipPending = page.waitForEvent('download'); await page.getByRole('button', { name: '导出已保存草稿' }).click()
    assert.equal((await zipPending).suggestedFilename(), 'article.zip')
  } finally { await context.close() }
}, { timeout: 45000 })
